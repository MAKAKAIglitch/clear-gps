package org.cleargps.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ZonePoint(val latitude: Double, val longitude: Double)

/**
 * Point-in-polygon for local risk zones. Longitudes are unwrapped relative to the query point,
 * so an antimeridian-crossing polygon does not become a world-spanning shape.
 *
 * This is intended for local zones with short edges. Polygons spanning most of the globe need
 * a geodesic polygon implementation.
 */
object ZoneGeometry {
    fun contains(point: ZonePoint, polygon: List<ZonePoint>): Boolean {
        if (polygon.size < 3 || !point.isValid() || polygon.any { !it.isValid() }) return false

        // Unwrap vertices successively so neighbouring dateline vertices stay near each other.
        val firstLongitude = polygon.first().longitude
        var previousLongitude = firstLongitude
        val relative = polygon.mapIndexed { index, vertex ->
            val longitude = if (index == 0) {
                firstLongitude
            } else {
                previousLongitude + longitudeDelta(vertex.longitude, previousLongitude)
            }
            previousLongitude = longitude
            RelativePoint(vertex.latitude, longitude)
        }
        val pointLongitude = firstLongitude + longitudeDelta(point.longitude, firstLongitude)
        val minLatitude = relative.minOf { it.latitude }
        val maxLatitude = relative.maxOf { it.latitude }
        if (point.latitude < minLatitude - EPSILON ||
            point.latitude > maxLatitude + EPSILON
        ) return false

        // Bbox prefilter in a point-relative longitude frame, including across +/-180 degrees.
        val minLongitude = relative.minOf { it.longitude }
        val maxLongitude = relative.maxOf { it.longitude }
        if (pointLongitude < minLongitude - EPSILON || pointLongitude > maxLongitude + EPSILON) return false

        var inside = false
        for (i in relative.indices) {
            val a = relative[i]
            val b = relative[(i + 1) % relative.size]
            if (onSegment(pointLongitude, point.latitude, a, b)) return true

            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossingLongitude = (b.longitude - a.longitude) *
                    (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                if (pointLongitude < crossingLongitude) inside = !inside
            }
        }
        return inside
    }

    private fun onSegment(
        pointLongitude: Double,
        pointLatitude: Double,
        a: RelativePoint,
        b: RelativePoint
    ): Boolean {
        val cross = (pointLongitude - a.longitude) * (b.latitude - a.latitude) -
            (pointLatitude - a.latitude) * (b.longitude - a.longitude)
        return abs(cross) <= EPSILON &&
            pointLongitude >= min(a.longitude, b.longitude) - EPSILON &&
            pointLongitude <= max(a.longitude, b.longitude) + EPSILON &&
            pointLatitude >= min(a.latitude, b.latitude) - EPSILON &&
            pointLatitude <= max(a.latitude, b.latitude) + EPSILON
    }

    private fun longitudeDelta(longitude: Double, reference: Double): Double {
        var delta = (longitude - reference) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private fun ZonePoint.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    private data class RelativePoint(val latitude: Double, val longitude: Double)
    private const val EPSILON = 1e-8
}
