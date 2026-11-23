package org.cleargps

import android.content.Context
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Photon-backed global place search. Network access is independently consent-gated. */
internal class NavigationGeocodingRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val requestId = AtomicLong()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var activeCall: okhttp3.Call? = null

    fun search(
        query: String,
        bias: NavigationCoordinate?,
        callback: (List<NavigationGeocodingResult>, String?) -> Unit
    ) {
        val id = requestId.incrementAndGet()
        activeCall?.cancel()
        if (!preferences.getBoolean("navigation_geocoding_search_enabled", false)) {
            callback(emptyList(), "Разрешите интернет-поиск, чтобы находить города и адреса.")
            return
        }
        val normalized = query.trim().take(240)
        if (normalized.length < 2) {
            callback(emptyList(), null)
            return
        }
        val builder = HttpUrl.Builder()
            .scheme("https").host("photon.komoot.io").addPathSegment("api")
            .addQueryParameter("q", normalized)
            .addQueryParameter("limit", "10")
            .addQueryParameter("lang", Locale.getDefault().language.takeIf { it in setOf("ru", "en", "fi", "sv", "de") } ?: "ru")
        bias?.takeIf { it.isValid() }?.let {
            builder.addQueryParameter("lat", it.latitude.toString())
                .addQueryParameter("lon", it.longitude.toString())
                .addQueryParameter("zoom", "12")
                .addQueryParameter("location_bias_scale", "0.2")
        }
        val url = builder.build()
        try {
            NetworkGate.validateRequest(
                url.toString(), "GET", NetworkGate.Operation.GEOCODING_SEARCH,
                consentGranted = true
            )
        } catch (error: Exception) {
            callback(emptyList(), "Поиск заблокирован сетевой политикой ClearGPS.")
            return
        }
        val request = Request.Builder().url(url)
            .header("Accept", "application/geo+json")
            .header("User-Agent", "ClearGPS-Android/0.4 (OpenStreetMap place search)")
            .get().build()
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (id == requestId.get() && !call.isCanceled()) {
                    mainHandler.post { if (id == requestId.get()) callback(emptyList(), "Не удалось выполнить поиск. Проверьте подключение к интернету.") }
                }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val parsed = response.use {
                    if (!it.isSuccessful) null else runCatching { parse(it.body?.string().orEmpty()) }.getOrNull()
                }
                if (id != requestId.get()) return
                mainHandler.post {
                    if (id == requestId.get()) {
                        if (parsed == null) callback(emptyList(), "Сервис поиска временно недоступен. Попробуйте ещё раз.")
                        else callback(parsed, null)
                    }
                }
            }
        })
    }

    fun cancel() {
        requestId.incrementAndGet()
        activeCall?.cancel()
        activeCall = null
    }

    private fun parse(json: String): List<NavigationGeocodingResult> {
        val features = JsonParser.parseString(json).asJsonObject.getAsJsonArray("features")
            ?: return emptyList()
        return features.mapNotNull { feature ->
            runCatching {
                val item = feature.asJsonObject
                val properties = item.getAsJsonObject("properties")
                val coordinates = item.getAsJsonObject("geometry").getAsJsonArray("coordinates")
                val lon = coordinates[0].asDouble
                val lat = coordinates[1].asDouble
                if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@runCatching null
                val name = properties.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                val locality = listOf("district", "city", "county", "state", "country")
                    .mapNotNull { key -> properties.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } }
                    .distinct().filterNot { it.equals(name, true) }
                val street = properties.get("street")?.takeIf { !it.isJsonNull }?.asString
                val house = properties.get("housenumber")?.takeIf { !it.isJsonNull }?.asString
                val address = listOfNotNull(listOfNotNull(street, house).joinToString(" ").takeIf { it.isNotBlank() }, locality.joinToString(", ").takeIf { it.isNotBlank() })
                    .joinToString(", ")
                val kind = properties.get("type")?.takeIf { !it.isJsonNull }?.asString
                    ?: properties.get("osm_value")?.takeIf { !it.isJsonNull }?.asString
                    ?: "Место"
                NavigationGeocodingResult(name, address.ifBlank { kind }, lat, lon, kind)
            }.getOrNull()
        }.distinctBy { "${it.name.lowercase(Locale.ROOT)}:${it.latitude}:${it.longitude}" }
            .take(10)
    }
}
