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
    val durationSeconds: Double,
    val steps: List<NavigationManeuverStep> = emptyList()
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

    private fun buildManeuverInstruction(type: String, modifier: String?, road: String, exit: Int?): String {
        val direction = when (modifier) {
            "left" -> "налево"
            "slight left" -> "плавно налево"
            "sharp left" -> "резко налево"
            "right" -> "направо"
            "slight right" -> "плавно направо"
            "sharp right" -> "резко направо"
            "uturn" -> "развернитесь"
            "straight" -> "прямо"
            else -> ""
        }
        val action = when (type) {
            "depart" -> "Начните движение"
            "arrive" -> "Вы прибыли в пункт назначения"
            "turn" -> if (direction.isBlank()) "Выполните поворот" else "Поверните $direction"
            "new name", "continue" -> if (direction.isBlank() || direction == "прямо") "Продолжайте движение" else "Продолжайте $direction"
            "merge" -> "Перестройтесь"
            "on ramp" -> "Съезжайте на съезд"
            "off ramp" -> "Съезжайте с дороги"
            "fork" -> if (direction.isBlank()) "На развилке продолжайте движение" else "На развилке держитесь $direction"
            "end of road" -> if (direction.isBlank()) "В конце дороги поверните" else "В конце дороги поверните $direction"
            "roundabout", "rotary" -> if (exit != null) "На круговом движении используйте $exit-й съезд" else "На круговом движении следуйте по маршруту"
            "roundabout turn" -> "На круговом движении выполните поворот"
            "notification" -> "Впереди манёвр"
            else -> "Продолжайте движение"
        }
        return if (road.isNotBlank() && type != "arrive") "$action на $road" else action
    }

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
            val leg = route.getAsJsonArray("legs")?.firstOrNull()?.asJsonObject
            val rawSteps = leg?.getAsJsonArray("steps")
            val steps = rawSteps?.mapNotNull { stepElement ->
                val step = stepElement.asJsonObject
                val maneuver = step.getAsJsonObject("maneuver") ?: return@mapNotNull null
                val location = maneuver.getAsJsonArray("location") ?: return@mapNotNull null
                if (location.size() < 2) return@mapNotNull null
                val coordinate = NavigationCoordinate(location[1].asDouble, location[0].asDouble)
                    .takeIf { it.isValid() } ?: return@mapNotNull null
                val type = maneuver.get("type")?.asString ?: "continue"
                val modifier = maneuver.get("modifier")?.takeIf { !it.isJsonNull }?.asString
                val road = step.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val exit = maneuver.get("exit")?.takeIf { !it.isJsonNull }?.asInt
                val instruction = buildManeuverInstruction(type, modifier, road, exit)
                val routeIndex = coordinates.indices.minByOrNull { index ->
                    NavigationGuidanceEngine.distanceMeters(coordinates[index], coordinate)
                } ?: 0
                NavigationManeuverStep(
                    instruction = instruction,
                    distanceMeters = step.get("distance")?.asDouble?.takeIf { it.isFinite() } ?: 0.0,
                    coordinate = coordinate,
                    routeIndex = routeIndex,
                    maneuverType = type,
                    modifier = modifier
                )
            } ?: emptyList()
            if (coordinates.size < 2 || !distance.isFinite() || !duration.isFinite()) null
            else NavigationRouteAlternative(coordinates, distance, duration, steps)
        }.sortedBy { it.durationSeconds }.take(3)
        if (routes.isEmpty()) throw IOException("Сервис не вернул альтернативные маршруты")
        return routes
    }
}