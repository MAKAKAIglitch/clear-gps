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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round

/** Viewport-based OSM lane renderer. Roads without lane tags are not guessed. */
internal class NavigationLaneRepository {
    data class RenderData(val laneLines: FeatureCollection, val turnArrows: FeatureCollection)
    private data class RoadWay(
        val id: String,
        val coordinates: List<Pair<Double, Double>>,
        val tags: Map<String, String>,
        val laneCount: Int
    ) {
        val startKey: String get() = endpointKey(coordinates.first())
        val endKey: String get() = endpointKey(coordinates.last())
    }
    private data class Cached(val createdAt: Long, val data: RenderData)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val cache = LinkedHashMap<String, Cached>()
    @Volatile private var activeCall: Call? = null
    @Volatile private var activeKey: String? = null

    fun load(south: Double, west: Double, north: Double, east: Double, callback: (RenderData) -> Unit) {
        if (!listOf(south, west, north, east).all { it.isFinite() } || south >= north || west >= east) return
        val key = listOf(south, west, north, east).joinToString(":") {
            (round(it * 1000.0) / 1000.0).toString()
        }
        synchronized(cache) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.createdAt < CACHE_TTL_MS }?.let {
                mainHandler.post { callback(it.data) }
                return
            }
        }
        if (activeKey == key) return
        activeCall?.cancel()
        activeKey = key
        val bbox = "$south,$west,$north,$east"
        val query = """
            [out:json][timeout:22];
            (
              way["highway"]["lanes"]($bbox);
              way["highway"]["lanes:forward"]($bbox);
              way["highway"]["lanes:backward"]($bbox);
              way["highway"]["turn:lanes"]($bbox);
              way["highway"]["turn:lanes:forward"]($bbox);
              way["highway"]["turn:lanes:backward"]($bbox);
            );
            out geom($bbox) tags;
        """.trimIndent()
        requestAtEndpoint(key, query, 0, callback)
    }

    private fun requestAtEndpoint(key: String, query: String, endpointIndex: Int, callback: (RenderData) -> Unit) {
        if (activeKey != key) return
        if (endpointIndex >= OVERPASS_ENDPOINTS.size) {
            Log.w(TAG, "No Overpass endpoint returned lane data for bbox=$key")
            activeKey = null
            mainHandler.post { callback(EMPTY_DATA) }
            return
        }
        val request = Request.Builder()
            .url(OVERPASS_ENDPOINTS[endpointIndex])
            .header("User-Agent", "ClearGPS-Android/0.4 (OpenStreetMap lane visualization)")
            .post(FormBody.Builder().add("data", query).build())
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled() && activeKey == key) {
                    Log.w(TAG, "Overpass lane endpoint ${endpointIndex + 1} failed: ${e.javaClass.simpleName}")
                    requestAtEndpoint(key, query, endpointIndex + 1, callback)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use {
                    if (!it.isSuccessful) null
                    else runCatching { parse(it.body?.string().orEmpty()) }.getOrNull()
                }
                if (activeKey != key) return
                if (parsed == null) {
                    Log.w(TAG, "Overpass lane endpoint ${endpointIndex + 1} returned invalid data; trying mirror")
                    requestAtEndpoint(key, query, endpointIndex + 1, callback)
                    return
                }
                activeKey = null
                synchronized(cache) {
                    cache[key] = Cached(System.currentTimeMillis(), parsed)
                    while (cache.size > MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
                }
                Log.i(TAG, "OSM lane data loaded: laneLines=${parsed.laneLines.features()?.size ?: 0}, arrows=${parsed.turnArrows.features()?.size ?: 0}")
                mainHandler.post { callback(parsed) }
            }
        })
    }

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
        activeKey = null
    }

    private fun parse(json: String): RenderData {
        val root = JsonParser.parseString(json).asJsonObject
        if (root.has("remark")) error("Overpass returned remark")
        val elements = root.getAsJsonArray("elements") ?: error("Missing elements")
        val roads = elements.mapNotNull { element ->
            val obj = element.asJsonObject
            if (obj.get("type")?.asString != "way") return@mapNotNull null
            val id = obj.get("id")?.asString ?: return@mapNotNull null
            val tags = obj.getAsJsonObject("tags")?.entrySet()
                ?.associate { it.key to it.value.asString }.orEmpty()
            val geometry = obj.getAsJsonArray("geometry") ?: return@mapNotNull null
            val coordinates = geometry.mapNotNull { point ->
                val p = point.asJsonObject
                val lat = p.get("lat")?.asDouble ?: return@mapNotNull null
                val lon = p.get("lon")?.asDouble ?: return@mapNotNull null
                if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) {
                    return@mapNotNull null
                }
                lon to lat
            }
            if (coordinates.size < 2) return@mapNotNull null
            val forward = tags["lanes:forward"]?.toIntOrNull()
            val backward = tags["lanes:backward"]?.toIntOrNull()
            val directionalCount = if (forward != null && backward != null) {
                forward + backward
            } else if (tags["oneway"] in setOf("yes", "1", "true") && forward != null) {
                forward
            } else if (tags["oneway"] == "-1" && backward != null) {
                backward
            } else {
                0
            }
            val turnLaneCount = listOf(tags["turn:lanes"], tags["turn:lanes:forward"], tags["turn:lanes:backward"])
                .filterNotNull().maxOfOrNull { it.split('|').size } ?: 0
            val count = (tags["lanes"]?.toIntOrNull() ?: directionalCount)
                .takeIf { it > 0 } ?: turnLaneCount
            if (count !in 1..12) return@mapNotNull null
            RoadWay(id, coordinates, tags, count)
        }

        val roadsByEndpoint = roads.flatMap { road ->
            listOf(road.startKey to road, road.endKey to road)
        }.groupBy({ it.first }, { it.second })
        val lineFeatures = ArrayList<Feature>()
        val arrowFeatures = ArrayList<Feature>()
        roads.forEach { road ->
            val startTransition = roadsByEndpoint[road.startKey].orEmpty()
                .any { it.id != road.id && it.laneCount != road.laneCount }
            val endTransition = roadsByEndpoint[road.endKey].orEmpty()
                .any { it.id != road.id && it.laneCount != road.laneCount }
            for (boundary in 1 until road.laneCount) {
                val shifted = offsetPolyline(
                    road.coordinates,
                    (boundary - road.laneCount / 2.0) * LANE_WIDTH_METERS,
                    startTransition,
                    endTransition
                )
                if (shifted.size >= 2) {
                    lineFeatures += Feature.fromGeometry(
                        LineString.fromLngLats(shifted.map { Point.fromLngLat(it.first, it.second) })
                    ).apply {
                        addStringProperty("osm_way_id", road.id)
                        addNumberProperty("lane_count", road.laneCount)
                    }
                }
            }
            addTurnArrows(road, arrowFeatures)
        }
        return RenderData(
            FeatureCollection.fromFeatures(lineFeatures.toTypedArray()),
            FeatureCollection.fromFeatures(arrowFeatures.toTypedArray())
        )
    }

    private fun addTurnArrows(road: RoadWay, output: MutableList<Feature>) {
        val forward = road.tags["turn:lanes:forward"] ?: road.tags["turn:lanes"]
        val backward = road.tags["turn:lanes:backward"]
        if (forward != null) {
            val points = if (road.tags["oneway"] == "-1") road.coordinates.asReversed() else road.coordinates
            addDirectionalArrows(points, forward, road.id, output)
        }
        if (backward != null) addDirectionalArrows(road.coordinates.asReversed(), backward, road.id, output)
    }

    private fun addDirectionalArrows(
        coordinates: List<Pair<Double, Double>>,
        laneTags: String,
        wayId: String,
        output: MutableList<Feature>
    ) {
        val lanes = laneTags.split('|')
        val length = polylineLength(coordinates)
        if (length < 24.0) return
        val center = pointAtDistance(coordinates, length * 0.78) ?: return
        lanes.forEachIndexed { index, laneValue ->
            val glyph = turnGlyph(laneValue) ?: return@forEachIndexed
            val lateral = ((lanes.size - 1) / 2.0 - index) * LANE_WIDTH_METERS
            val point = offsetPoint(center, coordinates, lateral)
            val middle = (coordinates.size / 2).coerceIn(0, coordinates.lastIndex)
            val from = coordinates[(middle - 1).coerceAtLeast(0)]
            val to = coordinates[(middle + 1).coerceAtMost(coordinates.lastIndex)]
            val latitudeRadians = Math.toRadians(center.second)
            val bearing = Math.toDegrees(
                atan2(
                    (to.first - from.first) * cos(latitudeRadians),
                    to.second - from.second
                )
            )
            output += Feature.fromGeometry(Point.fromLngLat(point.first, point.second)).apply {
                addStringProperty("glyph", glyph)
                addNumberProperty("bearing", bearing)
                addStringProperty("osm_way_id", wayId)
            }
        }
    }

    private fun turnGlyph(value: String): String? {
        val glyphs = value.split(';').mapNotNull { token ->
            when (token.trim().lowercase()) {
                "through", "straight" -> "↑"
                "left", "slight_left", "sharp_left", "merge_to_left" -> "↰"
                "right", "slight_right", "sharp_right", "merge_to_right" -> "↱"
                "reverse" -> "↶"
                else -> null
            }
        }.distinct()
        return glyphs.takeIf { it.isNotEmpty() }?.joinToString("")
    }

    private fun offsetPolyline(
        coordinates: List<Pair<Double, Double>>,
        offsetMeters: Double,
        taperAtStart: Boolean,
        taperAtEnd: Boolean
    ): List<Pair<Double, Double>> {
        val total = polylineLength(coordinates)
        if (total <= 0.0) return emptyList()
        var travelled = 0.0
        return coordinates.mapIndexed { index, point ->
            if (index > 0) travelled += distanceMeters(coordinates[index - 1], point)
            val fromStart = if (taperAtStart) (travelled / TRANSITION_METERS).coerceIn(0.0, 1.0) else 1.0
            val fromEnd = if (taperAtEnd) ((total - travelled) / TRANSITION_METERS).coerceIn(0.0, 1.0) else 1.0
            val factor = minOf(fromStart, fromEnd)
            val previous = coordinates[(index - 1).coerceAtLeast(0)]
            val next = coordinates[(index + 1).coerceAtMost(coordinates.lastIndex)]
            val latitudeRadians = Math.toRadians(point.second)
            val dx = (next.first - previous.first) * 111320.0 * cos(latitudeRadians)
            val dy = (next.second - previous.second) * 110540.0
            val length = hypot(dx, dy).coerceAtLeast(0.001)
            val shiftedX = -dy / length * offsetMeters * factor
            val shiftedY = dx / length * offsetMeters * factor
            val longitude = point.first + shiftedX / (111320.0 * cos(latitudeRadians).coerceAtLeast(0.01))
            val latitude = point.second + shiftedY / 110540.0
            longitude to latitude
        }
    }

    private fun offsetPoint(
        point: Pair<Double, Double>,
        coordinates: List<Pair<Double, Double>>,
        offsetMeters: Double
    ): Pair<Double, Double> {
        val middle = coordinates.size / 2
        val a = coordinates[(middle - 1).coerceAtLeast(0)]
        val b = coordinates[(middle + 1).coerceAtMost(coordinates.lastIndex)]
        val latitudeRadians = Math.toRadians(point.second)
        val dx = (b.first - a.first) * 111320.0 * cos(latitudeRadians)
        val dy = (b.second - a.second) * 110540.0
        val length = hypot(dx, dy).coerceAtLeast(0.001)
        return (point.first - dy / length * offsetMeters / (111320.0 * cos(latitudeRadians).coerceAtLeast(0.01))) to
            (point.second + dx / length * offsetMeters / 110540.0)
    }

    private fun pointAtDistance(coordinates: List<Pair<Double, Double>>, target: Double): Pair<Double, Double>? {
        var travelled = 0.0
        for (index in 1 until coordinates.size) {
            val a = coordinates[index - 1]
            val b = coordinates[index]
            val segment = distanceMeters(a, b)
            if (travelled + segment >= target) {
                val fraction = ((target - travelled) / segment.coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
                return (a.first + (b.first - a.first) * fraction) to
                    (a.second + (b.second - a.second) * fraction)
            }
            travelled += segment
        }
        return coordinates.lastOrNull()
    }

    private fun polylineLength(points: List<Pair<Double, Double>>): Double =
        (1 until points.size).sumOf { distanceMeters(points[it - 1], points[it]) }

    private fun distanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val meanLatitude = Math.toRadians((a.second + b.second) / 2.0)
        return hypot(
            (b.first - a.first) * 111320.0 * cos(meanLatitude),
            (b.second - a.second) * 110540.0
        )
    }

    private companion object {
        const val TAG = "ClearGPS_LANES"
        const val LANE_WIDTH_METERS = 3.2
        const val TRANSITION_METERS = 22.0
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val MAX_CACHE_ENTRIES = 20
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        val EMPTY_DATA = RenderData(
            FeatureCollection.fromFeatures(emptyArray()),
            FeatureCollection.fromFeatures(emptyArray())
        )
        fun endpointKey(point: Pair<Double, Double>): String =
            "${round(point.first * 100000.0)}:${round(point.second * 100000.0)}"
    }
}
