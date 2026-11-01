package org.cleargps

import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID

/** Separate, offline-only JSON and GeoJSON interchange for user-authored spoofing zones. */
internal object SpoofZoneExportCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ZONES = 100
    const val MAX_POINTS_PER_ZONE = 1_000
    private const val FORMAT = "clear-gps-spoof-zones"
    private const val VERSION = 1

    fun encodeJson(zones: List<SavedRiskZone>): String {
        validateZones(zones)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("zones", JSONArray().apply {
                zones.forEach { zone ->
                    put(JSONObject()
                        .put("id", zone.id)
                        .put("name", zone.name)
                        .put("enabled", zone.enabled)
                        .put("createdAt", zone.createdAt)
                        .put("points", JSONArray().apply {
                            zone.points.forEach { point ->
                                put(JSONObject().put("lat", point.latitude).put("lon", point.longitude))
                            }
                        })
                    )
                }
            })
        return bounded(root.toString())
    }

    fun encodeGeoJson(zones: List<SavedRiskZone>): String {
        validateZones(zones)
        val root = JSONObject()
            .put("type", "FeatureCollection")
            .put("name", "Clear GPS spoofing zones")
            .put("properties", JSONObject().put("format", FORMAT).put("version", VERSION))
            .put("features", JSONArray().apply {
                zones.forEach { zone ->
                    val ringPoints = counterClockwise(zone.points)
                    val ring = JSONArray().apply {
                        ringPoints.forEach { point ->
                            put(JSONArray().put(point.longitude).put(point.latitude))
                        }
                        val first = ringPoints.first()
                        put(JSONArray().put(first.longitude).put(first.latitude))
                    }
                    put(JSONObject()
                        .put("type", "Feature")
                        .put("id", zone.id)
                        .put("properties", JSONObject()
                            .put("id", zone.id)
                            .put("name", zone.name)
                            .put("enabled", zone.enabled)
                            .put("createdAt", zone.createdAt)
                        )
                        .put("geometry", JSONObject()
                            .put("type", "Polygon")
                            .put("coordinates", JSONArray().put(ring))
                        )
                    )
                }
            })
        return bounded(root.toString())
    }

    fun decode(text: String): List<SavedRiskZone> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Файл зон превышает ограничение 2 МиБ."
        }
        val root = JSONObject(text.removePrefix("\uFEFF"))
        val zones = when (root.optString("type")) {
            "FeatureCollection" -> decodeGeoJson(root)
            else -> {
                require(root.optString("format") == FORMAT && root.optInt("version") == VERSION) {
                    "Неизвестный формат JSON/GeoJSON зон."
                }
                decodeJsonZones(root.optJSONArray("zones")
                    ?: throw IllegalArgumentException("В файле нет списка зон."))
            }
        }
        validateZones(zones)
        return zones
    }

    fun readBounded(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) {
                    "Файл зон превышает ограничение 2 МиБ."
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun counterClockwise(points: List<ZonePoint>): List<ZonePoint> {
        val origin = points.first().longitude
        val unwrappedLongitudes = points.map { point ->
            val delta = ((point.longitude - origin + 540.0) % 360.0) - 180.0
            origin + delta
        }
        var signedArea = 0.0
        for (index in points.indices) {
            val next = (index + 1) % points.size
            signedArea += unwrappedLongitudes[index] * points[next].latitude -
                unwrappedLongitudes[next] * points[index].latitude
        }
        return if (signedArea >= 0.0) points else points.reversed()
    }

    private fun decodeJsonZones(array: JSONArray): List<SavedRiskZone> {
        require(array.length() <= MAX_ZONES) { "В файле слишком много зон." }
        return buildList {
            for (index in 0 until array.length()) {
                val zone = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("Некорректная запись зоны.")
                val points = zone.optJSONArray("points")
                    ?: throw IllegalArgumentException("У зоны отсутствуют вершины.")
                add(
                    SavedRiskZone(
                        id = zone.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = zone.optString("name").ifBlank { "Imported zone ${index + 1}" },
                        points = parsePoints(points, longitudeFirst = false),
                        enabled = zone.optBoolean("enabled", true),
                        createdAt = zone.optLong("createdAt", 0L)
                    )
                )
            }
        }
    }

    private fun decodeGeoJson(root: JSONObject): List<SavedRiskZone> {
        val features = root.optJSONArray("features")
            ?: throw IllegalArgumentException("GeoJSON не содержит features.")
        require(features.length() <= MAX_ZONES) { "В файле слишком много зон." }
        return buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index)
                    ?: throw IllegalArgumentException("Некорректный GeoJSON Feature.")
                require(feature.optString("type") == "Feature") { "Ожидался GeoJSON Feature." }
                val geometry = feature.optJSONObject("geometry")
                    ?: throw IllegalArgumentException("У зоны отсутствует geometry.")
                require(geometry.optString("type") == "Polygon") {
                    "Поддерживаются только Polygon-зоны GeoJSON."
                }
                val coordinates = geometry.optJSONArray("coordinates")
                    ?: throw IllegalArgumentException("У полигона нет coordinates.")
                val outerRing = coordinates.optJSONArray(0)
                    ?: throw IllegalArgumentException("У полигона нет внешнего кольца.")
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                val id = properties.optString("id").ifBlank {
                    feature.optString("id").ifBlank { UUID.randomUUID().toString() }
                }
                add(
                    SavedRiskZone(
                        id = id,
                        name = properties.optString("name").ifBlank { "Imported zone ${index + 1}" },
                        points = parsePoints(outerRing, longitudeFirst = true),
                        enabled = properties.optBoolean("enabled", true),
                        createdAt = properties.optLong("createdAt", 0L)
                    )
                )
            }
        }
    }

    private fun parsePoints(array: JSONArray, longitudeFirst: Boolean): List<ZonePoint> {
        require(array.length() in 3..(MAX_POINTS_PER_ZONE + 1)) {
            "Некорректное число вершин полигона."
        }
        val points = buildList {
            for (index in 0 until array.length()) {
                val point = array.opt(index)
                val latitude: Double
                val longitude: Double
                if (longitudeFirst) {
                    val pair = point as? JSONArray
                        ?: throw IllegalArgumentException("Координата GeoJSON должна быть массивом [lon, lat].")
                    require(pair.length() >= 2) { "В паре координат отсутствует широта или долгота." }
                    longitude = pair.optDouble(0, Double.NaN)
                    latitude = pair.optDouble(1, Double.NaN)
                } else {
                    val value = point as? JSONObject
                        ?: throw IllegalArgumentException("Координата JSON должна быть объектом.")
                    latitude = value.optDouble("lat", Double.NaN)
                    longitude = value.optDouble("lon", Double.NaN)
                }
                require(latitude.isFinite() && latitude in -90.0..90.0 &&
                    longitude.isFinite() && longitude in -180.0..180.0
                ) { "В файле найдены некорректные координаты зоны." }
                add(ZonePoint(latitude, longitude))
            }
        }.toMutableList()
        if (points.size > 3 && points.first() == points.last()) points.removeAt(points.lastIndex)
        require(points.size in 3..MAX_POINTS_PER_ZONE) { "У зоны должно быть от 3 до 1 000 вершин." }
        return points
    }

    private fun validateZones(zones: List<SavedRiskZone>) {
        require(zones.size <= MAX_ZONES) { "В файле может быть не более 100 зон." }
        val ids = mutableSetOf<String>()
        zones.forEach { zone ->
            require(zone.id.isNotBlank() && zone.id.length <= 128) { "Некорректный ID зоны." }
            require(ids.add(zone.id)) { "В файле повторяется ID зоны." }
            require(zone.name.isNotBlank() && zone.name.length <= 80) { "Название зоны должно содержать до 80 символов." }
            require(zone.createdAt >= 0L) { "Некорректная дата создания зоны." }
            require(zone.points.size in 3..MAX_POINTS_PER_ZONE) { "У зоны должно быть от 3 до 1 000 вершин." }
            require(zone.points.all { point ->
                point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                    point.longitude.isFinite() && point.longitude in -180.0..180.0
            }) { "В зоне найдены некорректные координаты." }
        }
    }

    private fun bounded(text: String): String {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Экспорт зон превышает ограничение 2 МиБ."
        }
        return text
    }
}
