package org.cleargps

import android.os.Handler
import android.util.Log
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
    ATM("Банкоматы"),
    SPEED_CAMERA("Камеры скорости"),
    RED_LIGHT_CAMERA("Камеры перекрёстков"),
    TRAFFIC_CAMERA("Автомобильные камеры");

    val isCamera: Boolean
        get() = this == SPEED_CAMERA || this == RED_LIGHT_CAMERA || this == TRAFFIC_CAMERA

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
            tags["highway"] == "speed_camera" -> SPEED_CAMERA
            tags["enforcement"]?.let(::fromEnforcement) != null ->
                fromEnforcement(tags["enforcement"])
            tags["man_made"] == "surveillance" &&
                tags["surveillance"] in setOf("traffic", "ALPR", "ANPR") -> TRAFFIC_CAMERA
            tags["camera:type"] in setOf("speed", "traffic") ->
                if (tags["camera:type"] == "speed") SPEED_CAMERA else TRAFFIC_CAMERA
            else -> null
        }

        fun fromEnforcement(value: String?): NavigationPoiCategory? = when (value?.trim()?.lowercase()) {
            "maxspeed", "average_speed" -> SPEED_CAMERA
            "traffic_signals" -> RED_LIGHT_CAMERA
            "access", "check", "mobile_phone", "seatbelt", "bus_lane",
            "motor_vehicle", "weight", "height", "dimension", "hazmat" -> TRAFFIC_CAMERA
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
    val wheelchair: String? = null,
    val tags: Map<String, String> = emptyMap()
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
        // Address records are dense: request them only for close-up viewports.
        val addressQuery = if ((north - south) * (east - west) <= 0.0025) {
            """nwr["addr:housenumber"]($bbox);"""
        } else ""
        val query = """
            [out:json][timeout:18];
            (
              $addressQuery
              nwr["amenity"~"^(fuel|charging_station|pharmacy|restaurant|cafe|fast_food|bar|parking|atm)$"]($bbox);
              nwr["shop"~"^(chemist|supermarket|convenience|greengrocer|deli|mall|department_store|clothes|shoes|hardware|electronics|books|bakery|butcher|beauty|mobile_phone)$"]($bbox);
            )->.pois;
            (
              nwr["highway"="speed_camera"]($bbox);
              nwr["enforcement"]($bbox);
              nwr["man_made"="surveillance"]["surveillance"~"^(traffic|ALPR|ANPR)$"]($bbox);
              nwr["camera:type"~"^(speed|traffic)$"]($bbox);
            )->.cameraTagged;
            rel["type"="enforcement"]($bbox)->.enforcementRelations;
            (
              node(r.enforcementRelations:"device");
              way(r.enforcementRelations:"device");
            )->.cameraDevices;
            (
              .pois;
              .cameraTagged;
              .cameraDevices;
            );
            out center tags;
            .enforcementRelations out body;
        """.trimIndent()
        enqueueAtEndpoint(key, query, 0, callback)
    }

    private fun enqueueAtEndpoint(key: String, query: String, endpointIndex: Int, callback: (List<NavigationPoi>) -> Unit) {
        if (lastRequestKey != key) return
        if (endpointIndex >= OVERPASS_ENDPOINTS.size) {
            Log.e(TAG, "Все Overpass-серверы недоступны или вернули ошибку; bboxKey=$key")
            lastRequestKey = null
            mainHandler.post { callback(emptyList()) }
            return
        }
        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder().url(OVERPASS_ENDPOINTS[endpointIndex]).header("User-Agent", "ClearGPS-Android/0.4 (OpenStreetMap POI map)").post(body).build()
        val call = client.newCall(request); activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled() && lastRequestKey == key) {
                    Log.w(TAG, "Overpass ${endpointIndex + 1}/${OVERPASS_ENDPOINTS.size} network failure: ${e.javaClass.simpleName}: ${e.message}")
                    enqueueAtEndpoint(key, query, endpointIndex + 1, callback)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val status = response.code
                val parsed = response.use {
                    if (!it.isSuccessful) null
                    else runCatching { parse(it.body?.string().orEmpty()) }.getOrNull()
                }
                if (lastRequestKey != key) return
                if (parsed == null) {
                    Log.w(TAG, "Overpass ${endpointIndex + 1}/${OVERPASS_ENDPOINTS.size} HTTP $status: invalid response/timeout; trying next mirror")
                    enqueueAtEndpoint(key, query, endpointIndex + 1, callback)
                } else {
                    lastRequestKey = null
                    val cameraCount = parsed.count { it.category.isCamera }
                    val byCategory = parsed.filter { it.category.isCamera }
                        .groupingBy { it.category.name }.eachCount()
                    Log.i(TAG, "Overpass OK: endpoint=${endpointIndex + 1}, HTTP=$status, total=${parsed.size}, cameras=$cameraCount, cameraCategories=$byCategory")
                    synchronized(cache) {
                        cache[key] = Cached(System.currentTimeMillis(), parsed)
                        while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
                    }
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
        val deviceCoordinates = HashMap<String, Pair<Double, Double>>()
        val relationDevices = ArrayList<Triple<String, NavigationPoiCategory, Map<String, String>>>()
        elements.forEach { element ->
            val obj = element.asJsonObject
            val type = obj.get("type")?.asString ?: return@forEach
            val id = obj.get("id")?.asString ?: return@forEach
            val coordinateObject = if (obj.has("lat") && obj.has("lon")) obj else obj.getAsJsonObject("center")
            val lat = coordinateObject?.get("lat")?.asDouble
            val lon = coordinateObject?.get("lon")?.asDouble
            if (type in setOf("node", "way") && lat != null && lon != null &&
                lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0
            ) deviceCoordinates["$type/$id"] = lat to lon

            val tagsObject = obj.getAsJsonObject("tags")
            val tags = tagsObject?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            if (type == "relation" && tags["type"] == "enforcement") {
                val category = NavigationPoiCategory.fromEnforcement(tags["enforcement"])
                if (category != null) {
                    obj.getAsJsonArray("members")?.forEach { memberElement ->
                        val member = memberElement.asJsonObject
                        val memberType = member.get("type")?.asString
                        if (memberType in setOf("node", "way") &&
                            member.get("role")?.asString == "device"
                        ) {
                            relationDevices += Triple("${memberType}/${member.get("ref").asString}", category, tags)
                        }
                    }
                }
            }
            if (tags.isEmpty()) return@forEach
            val addressOnly = NavigationPoiCategory.fromTags(tags) == null &&
                !tags["addr:housenumber"].isNullOrBlank()
            val category = NavigationPoiCategory.fromTags(tags)
                ?: if (addressOnly) NavigationPoiCategory.SHOP else return@forEach
            val point = if (lat != null && lon != null) lat to lon else return@forEach
            val (pointLat, pointLon) = point
            if (!pointLat.isFinite() || pointLat !in -90.0..90.0 ||
                !pointLon.isFinite() || pointLon !in -180.0..180.0
            ) return@forEach
            val label = tags["name"]?.takeIf { it.isNotBlank() }
                ?: tags["brand"]?.takeIf { it.isNotBlank() }
            val houseNumber = tags["addr:housenumber"]?.takeIf { it.isNotBlank() }
            val name = label ?: if (addressOnly) "Дом $houseNumber" else category.title
            val retainedTags = if (addressOnly) tags + ("cleargps:address_only" to "yes") else tags
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
                latitude = pointLat,
                longitude = pointLon,
                address = address,
                label = label,
                openingHours = tags["opening_hours"]?.takeIf { it.isNotBlank() },
                phone = (tags["phone"] ?: tags["contact:phone"])?.takeIf { it.isNotBlank() },
                website = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.takeIf { it.isNotBlank() },
                email = (tags["email"] ?: tags["contact:email"])?.takeIf { it.isNotBlank() },
                cuisine = tags["cuisine"]?.takeIf { it.isNotBlank() },
                operator = tags["operator"]?.takeIf { it.isNotBlank() },
                wheelchair = tags["wheelchair"]?.takeIf { it.isNotBlank() },
                tags = retainedTags
            )
        }
        relationDevices.forEach { (deviceId, category, relationTags) ->
            val coordinate = deviceCoordinates[deviceId] ?: return@forEach
            val id = deviceId
            if (id in result) return@forEach
            val cameraTags = relationTags + ("camera_source" to "OSM enforcement relation")
            result[id] = NavigationPoi(
                id = id,
                name = when (category) {
                    NavigationPoiCategory.SPEED_CAMERA -> "Камера скорости"
                    NavigationPoiCategory.RED_LIGHT_CAMERA -> "Камера перекрёстка"
                    else -> "Автомобильная камера контроля"
                },
                category = category,
                latitude = coordinate.first,
                longitude = coordinate.second,
                address = null,
                label = null,
                tags = cameraTags
            )
        }
        return result.values.toList()
    }

    private companion object {
        const val TAG = "ClearGPS_POI"
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val MAX_CACHE_ENTRIES = 24
    }
}
