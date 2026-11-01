package org.cleargps.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class ZoneCircleParameters(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
)

/** Converts a saved polygon into an editable circle and back to a geodesic polygon. */
object SpoofZoneEditorPolicy {
    const val MIN_RADIUS_METERS = 25.0
    const val MAX_RADIUS_METERS = 100_000.0
    const val DEFAULT_VERTEX_COUNT = 64
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun parameters(zone: SavedRiskZone): ZoneCircleParameters? {
        val points = zone.points.filter(::isValidPoint)
        if (points.size < 3) return null

        // A vector mean is stable for small shapes that cross the antimeridian.
        var x = 0.0
        var y = 0.0
        var z = 0.0
        points.forEach { point ->
            val latitude = Math.toRadians(point.latitude)
            val longitude = Math.toRadians(point.longitude)
            x += cos(latitude) * cos(longitude)
            y += cos(latitude) * sin(longitude)
            z += sin(latitude)
        }
        val horizontal = sqrt(x * x + y * y)
        if (!horizontal.isFinite() || (horizontal < 1e-12 && kotlin.math.abs(z) < 1e-12)) return null
        val centerLatitude = Math.toDegrees(atan2(z, horizontal))
        val centerLongitude = normalizeLongitude(Math.toDegrees(atan2(y, x)))
        val center = ZonePoint(centerLatitude, centerLongitude)
        val radius = points.maxOf { distanceMeters(center, it) }
            .coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        return ZoneCircleParameters(centerLatitude, centerLongitude, radius)
    }

    fun circlePoints(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        vertexCount: Int = DEFAULT_VERTEX_COUNT
    ): List<ZonePoint> {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Некорректная широта центра зоны." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Некорректная долгота центра зоны." }
        require(radiusMeters.isFinite() && radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            "Радиус зоны должен быть от 25 до 100 000 м."
        }
        val segments = vertexCount.coerceIn(24, 128)
        val angularDistance = radiusMeters / EARTH_RADIUS_METERS
        val latitude1 = Math.toRadians(latitude)
        val longitude1 = Math.toRadians(longitude)
        return (0 until segments).map { index ->
            val bearing = -2.0 * Math.PI * index / segments
            val latitude2 = asin(
                (sin(latitude1) * cos(angularDistance) +
                    cos(latitude1) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0)
            )
            val longitude2 = longitude1 + atan2(
                sin(bearing) * sin(angularDistance) * cos(latitude1),
                cos(angularDistance) - sin(latitude1) * sin(latitude2)
            )
            ZonePoint(
                Math.toDegrees(latitude2).coerceIn(-90.0, 90.0),
                normalizeLongitude(Math.toDegrees(longitude2))
            )
        }
    }

    private fun distanceMeters(first: ZonePoint, second: ZonePoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        var deltaLon = Math.toRadians(second.longitude - first.longitude)
        if (deltaLon > Math.PI) deltaLon -= 2.0 * Math.PI
        if (deltaLon < -Math.PI) deltaLon += 2.0 * Math.PI
        val haversine = (
            sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
                cos(lat1) * cos(lat2) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
            ).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine))
    }

    private fun normalizeLongitude(longitude: Double): Double {
        var normalized = longitude % 360.0
        if (normalized > 180.0) normalized -= 360.0
        if (normalized < -180.0) normalized += 360.0
        return normalized
    }

    private fun isValidPoint(point: ZonePoint): Boolean =
        point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0
}
