package org.cleargps

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class NavigationRouteAlternative(
    val coordinates: List<NavigationCoordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

/** Read-only OSRM route lookup. The public demo service is for prototype use, not an SLA. */
internal class NavigationRoutingRepository {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            NetworkGate.validateRequest(request.url.toString(), request.method, NetworkGate.Operation.ROUTE_LOOKUP)
            chain.proceed(request)
        }
        .build()
    @Volatile private var activeCall: Call? = null

    fun findRoutes(origin: NavigationCoordinate, destination: NavigationCoordinate, callback: (Result<List<NavigationRouteAlternative>>) -> Unit) {
        if (!origin.isValid() || !destination.isValid()) {
            mainHandler.post { callback(Result.failure(IllegalArgumentException("Некорректные координаты"))) }
            return
        }
        activeCall?.cancel()
        val url = "https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?alternatives=2&steps=true&overview=full&geometries=geojson"
        val request = Request.Builder().url(url).header("User-Agent", "ClearGPS-Android/0.4 (route preview)").header("Accept", "application/json").get().build()
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) mainHandler.post { callback(Result.failure(e)) }
            }
            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    if (!it.isSuccessful) Result.failure<List<NavigationRouteAlternative>>(IOException("Сервис маршрутов ответил HTTP ${it.code}"))
                    else runCatching { parse(it.body?.string().orEmpty()) }
                }
                if (!call.isCanceled()) mainHandler.post { callback(result) }
            }
        })
    }

    fun cancel() { activeCall?.cancel(); activeCall = null }

    private fun parse(json: String): List<NavigationRouteAlternative> {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("code")?.asString != "Ok") throw IOException("Не удалось построить маршрут: ${root.get("code")?.asString ?: "unknown"}")
        val array = root.getAsJsonArray("routes") ?: throw IOException("Маршруты отсутствуют")
        val routes = array.mapNotNull { element ->
            val route = element.asJsonObject
            val distance = route.get("distance")?.asDouble ?: return@mapNotNull null
            val duration = route.get("duration")?.asDouble ?: return@mapNotNull null
            val points = route.getAsJsonObject("geometry")?.getAsJsonArray("coordinates") ?: return@mapNotNull null
            val coordinates = points.mapNotNull { point ->
                val values = point.asJsonArray
                if (values.size() < 2) null else NavigationCoordinate(values[1].asDouble, values[0].asDouble).takeIf { it.isValid() }
            }
            if (coordinates.size < 2 || !distance.isFinite() || !duration.isFinite()) null
            else NavigationRouteAlternative(coordinates, distance, duration)
        }.sortedBy { it.durationSeconds }.take(3)
        if (routes.isEmpty()) throw IOException("Сервис не вернул альтернативные маршруты")
        return routes
    }
}