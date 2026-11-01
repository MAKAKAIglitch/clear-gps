package org.cleargps

import kotlin.math.cos

internal data class MapObservationBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double
) {
    val centerLatitude: Double
        get() = (north + south) / 2.0

    val centerLongitude: Double
        get() {
            val midpoint = (west + east) / 2.0
            return ((midpoint + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        }
}

/**
 * Produces the smallest longitude-wrapping bounds that contain every valid observation.
 * This intentionally does not apply the robust outlier rejection used by the estimated
 * centre: the camera must show every place where this radio point was measured.
 */
internal object MapObservationBoundsPolicy {
    private const val MINIMUM_SPAN_METERS = 60.0
    private const val METERS_PER_LATITUDE_DEGREE = 111_320.0

    fun calculate(coordinates: List<MapCoordinate>): MapObservationBounds? {
        val valid = coordinates.filter { coordinate ->
            coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0 &&
                coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0
        }
        if (valid.isEmpty()) return null

        var south = valid.minOf { it.latitude }
        var north = valid.maxOf { it.latitude }
        val latitudeMidpoint = (south + north) / 2.0
        val minimumLatitudeSpan = MINIMUM_SPAN_METERS / METERS_PER_LATITUDE_DEGREE
        if (north - south < minimumLatitudeSpan) {
            val halfSpan = minimumLatitudeSpan / 2.0
            south = (latitudeMidpoint - halfSpan).coerceAtLeast(-90.0)
            north = (latitudeMidpoint + halfSpan).coerceAtMost(90.0)
        }

        // Find the largest gap on the longitude circle, then use its complement.
        // That keeps observations at +179.9° and -179.9° together instead of spanning
        // almost the entire world.
        val longitudes = valid.map { normalizeLongitude(it.longitude) }.sorted()
        var largestGap = Double.NEGATIVE_INFINITY
        var largestGapEndIndex = longitudes.lastIndex
        for (index in longitudes.indices) {
            val nextIndex = (index + 1) % longitudes.size
            val nextLongitude = if (nextIndex == 0) {
                longitudes[0] + 360.0
            } else {
                longitudes[nextIndex]
            }
            val gap = nextLongitude - longitudes[index]
            if (gap > largestGap) {
                largestGap = gap
                largestGapEndIndex = index
            }
        }

        var west = longitudes[(largestGapEndIndex + 1) % longitudes.size]
        var east = longitudes[largestGapEndIndex]
        if (east < west) east += 360.0
        while (west > 180.0) {
            west -= 360.0
            east -= 360.0
        }
        while (west < -180.0) {
            west += 360.0
            east += 360.0
        }

        val metersPerLongitudeDegree = METERS_PER_LATITUDE_DEGREE *
            cos(Math.toRadians(latitudeMidpoint)).coerceAtLeast(0.01)
        val minimumLongitudeSpan = MINIMUM_SPAN_METERS / metersPerLongitudeDegree
        if (east - west < minimumLongitudeSpan) {
            val midpoint = (west + east) / 2.0
            val halfSpan = minimumLongitudeSpan / 2.0
            west = midpoint - halfSpan
            east = midpoint + halfSpan
        }

        return MapObservationBounds(
            north = north,
            east = east,
            south = south,
            west = west
        )
    }

    private fun normalizeLongitude(longitude: Double): Double =
        ((longitude % 360.0) + 360.0) % 360.0
}
