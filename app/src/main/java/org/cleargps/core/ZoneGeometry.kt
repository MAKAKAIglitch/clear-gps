package org.cleargps.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ZonePoint(val latitude: Double, val longitude: Double)

object ZoneGeometry {
    fun contains(point: ZonePoint, polygon: List<ZonePoint>): Boolean {
        if (polygon.size < 3 || !point.isValid() || polygon.any { !it.isValid() }) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if (onSegment(point, a, b)) return true
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossing = (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                    (b.latitude - a.latitude) + a.longitude
                if (point.longitude < crossing) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun onSegment(p: ZonePoint, a: ZonePoint, b: ZonePoint): Boolean {
        val eps = 1e-8
        val cross = (p.longitude - a.longitude) * (b.latitude - a.latitude) -
            (p.latitude - a.latitude) * (b.longitude - a.longitude)
        return abs(cross) <= eps &&
            p.longitude >= min(a.longitude, b.longitude) - eps &&
            p.longitude <= max(a.longitude, b.longitude) + eps &&
            p.latitude >= min(a.latitude, b.latitude) - eps &&
            p.latitude <= max(a.latitude, b.latitude) + eps
    }

    private fun ZonePoint.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}
