package org.cleargps

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.round

internal enum class NavigationPoiCategory(val title: String) {
    FUEL("Заправки"),
    ELECTRIC("Электрозаправки"),
    PHARMACY("Аптеки"),
    GROCERY("Продукты"),
    SHOP("Магазины"),
    FOOD("Кафе и рестораны"),
    PARKING("Парковки"),
    ATM("Банкоматы");

    companion object {
        fun fromTags(tags: Map<String, String>): NavigationPoiCategory? = when {
            tags["amenity"] == "fuel" -> FUEL
            tags["amenity"] == "charging_station" -> ELECTRIC
            tags["amenity"] == "pharmacy" || tags["shop"] == "chemist" -> PHARMACY
            tags["shop"] in setOf("supermarket", "convenience", "greengrocer", "deli") -> GROCERY
            tags["shop"] in setOf("mall", "department_store", "clothes", "shoes", "hardware", "electronics", "books", "bakery", "butcher", "beauty", "mobile_phone") -> SHOP
            tags["amenity"] in setOf("restaurant", "cafe", "fast_food", "bar") -> FOOD
            tags["amenity"] == "parking" -> PARKING
            tags["amenity"] == "atm" -> ATM
            else -> null
        }
    }
}

internal data class NavigationPoi(
    val id: String,
    val name: String,
    val category: NavigationPoiCategory,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val label: String? = null,
    val openingHours: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val email: String? = null,
    val cuisine: String? = null,
    val operator: String? = null,
    val wheelchair: String? = null
) {
    val coordinate: NavigationCoordinate
        get() = NavigationCoordinate(latitude, longitude)
}

/**
 * Small viewport-based OSM POI loader. Requests are serialized, cached and cancelled
 * when the map is detached. The public Overpass endpoint is used conservatively.
 */
internal class NavigationPoiRepository {
    private data class Cached(val createdAt: Long, val items: List<NavigationPoi>)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val cache = LinkedHashMap<String, Cached>()
    @Volatile private var activeCall: Call? = null
    @Volatile private var lastRequestKey: String? = null

    fun load(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        callback: (List<NavigationPoi>) -> Unit
    ) {
        if (!listOf(south, west, north, east).all { it.isFinite() }) return
        val key = listOf(south, west, north, east).joinToString(":") {
            (round(it * 1000.0) / 1000.0).toString()
        }
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null && System.currentTimeMillis() - cached.createdAt < CACHE_TTL_MS) {
                mainHandler.post { callback(cached.items) }
                return
            }
        }
        if (lastRequestKey == key) return
        activeCall?.cancel()
        lastRequestKey = key

        val bbox = "$south,$west,$north,$east"
        val query = """
            [out:json][timeout:18];
            (
              nwr["amenity"~"^(fuel|charging_station|pharmacy|restaurant|cafe|fast_food|bar|parking|atm)$"]($bbox);
              nwr["shop"~"^(chemist|supermarket|convenience|greengrocer|deli|mall|department_store|clothes|shoes|hardware|electronics|books|bakery|butcher|beauty|mobile_phone)$"]($bbox);
            );
            out center tags;
        """.trimIndent()
        enqueueAtEndpoint(key, query, 0, callback)
    }

    private fun enqueueAtEndpoint(key: String, query: String, endpointIndex: Int, callback: (List<NavigationPoi>) -> Unit) {
        if (lastRequestKey != key) return
        if (endpointIndex >= OVERPASS_ENDPOINTS.size) { lastRequestKey = null; mainHandler.post { callback(emptyList()) }; return }
        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder().url(OVERPASS_ENDPOINTS[endpointIndex]).header("User-Agent", "ClearGPS-Android/0.4 (OpenStreetMap POI map)").post(body).build()
        val call = client.newCall(request); activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled() && lastRequestKey == key) enqueueAtEndpoint(key, query, endpointIndex + 1, callback) }
            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use { if (!it.isSuccessful) null else runCatching { parse(it.body?.string().orEmpty()) }.getOrNull() }
                if (lastRequestKey != key) return
                if (parsed == null) enqueueAtEndpoint(key, query, endpointIndex + 1, callback) else {
                    lastRequestKey = null
                    synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), parsed); while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first()) }
                    mainHandler.post { callback(parsed) }
                }
            }
        })
    }
    fun cancel() {
        activeCall?.cancel()
        activeCall = null
        lastRequestKey = null
    }

    private fun parse(json: String): List<NavigationPoi>? {
        // Overpass may answer HTTP 200 with a JSON "remark" instead of data
        // (for example, a timeout). Treat that as a failed endpoint so the
        // repository tries the next mirror rather than caching a false empty map.
        val root = JsonParser.parseString(json).asJsonObject
        val elements = root.getAsJsonArray("elements") ?: return null
        val result = LinkedHashMap<String, NavigationPoi>()
        elements.forEach { element ->
            val obj = element.asJsonObject
            val tagsObject = obj.getAsJsonObject("tags") ?: return@forEach
            val tags = tagsObject.entrySet().associate { it.key to it.value.asString }
            val category = NavigationPoiCategory.fromTags(tags) ?: return@forEach
            val coordinateObject = if (obj.has("lat") && obj.has("lon")) obj else obj.getAsJsonObject("center")
                ?: return@forEach
            val lat = coordinateObject.get("lat")?.asDouble ?: return@forEach
            val lon = coordinateObject.get("lon")?.asDouble ?: return@forEach
            if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@forEach
            val type = obj.get("type")?.asString ?: "osm"
            val id = obj.get("id")?.asString ?: return@forEach
            val label = tags["name"]?.takeIf { it.isNotBlank() }
                ?: tags["brand"]?.takeIf { it.isNotBlank() }
            val name = label ?: category.title
            val street = tags["addr:street"]
            val house = tags["addr:housenumber"]
            val city = tags["addr:city"]
            val address = listOfNotNull(
                listOfNotNull(street, house).joinToString(" ").takeIf { it.isNotBlank() },
                city?.takeIf { it.isNotBlank() }
            ).joinToString(", ").takeIf { it.isNotBlank() }
            result["$type/$id"] = NavigationPoi(
                id = "$type/$id",
                name = name,
                category = category,
                latitude = lat,
                longitude = lon,
                address = address,
                label = label,
                openingHours = tags["opening_hours"]?.takeIf { it.isNotBlank() },
                phone = (tags["phone"] ?: tags["contact:phone"])?.takeIf { it.isNotBlank() },
                website = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.takeIf { it.isNotBlank() },
                email = (tags["email"] ?: tags["contact:email"])?.takeIf { it.isNotBlank() },
                cuisine = tags["cuisine"]?.takeIf { it.isNotBlank() },
                operator = tags["operator"]?.takeIf { it.isNotBlank() },
                wheelchair = tags["wheelchair"]?.takeIf { it.isNotBlank() }
            )
        }
        return result.values.toList()
    }

    private companion object {
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val MAX_CACHE_ENTRIES = 24
    }
}
