package org.cleargps.core

import kotlin.math.cos

data class ZonePoint(val latitude: Double, val longitude: Double)

object ZoneGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val EDGE_TOLERANCE_METERS = 0.01
    private const val MAX_ACCURACY_EXPANSION_METERS = 500.0
    private const val MAX_BOUNDARY_MARGIN_METERS = 50.0

    /**
     * Even-odd point-in-polygon test in a local metre plane.
     *
     * Longitudes are unwrapped relative to the tested point before projection, so a small zone
     * crossing the antimeridian remains small instead of spanning almost the whole world.
     * Self-intersecting polygons use the even-odd fill rule. A polygon with fewer than three
     * points or any invalid coordinate is rejected.
     */
    fun contains(point: ZonePoint, polygon: List<ZonePoint>): Boolean =
        contains(point, polygon, accuracyMeters = null, boundaryMarginMeters = 0.0)

    /**
     * Returns true when the point is inside/on the polygon, or when its reported accuracy circle
     * (up to 500 m) intersects the polygon. Large/invalid accuracy values do not expand a zone:
     * they are too coarse to justify treating an intersection as confirmed zone membership.
     */
    fun contains(point: ZonePoint, polygon: List<ZonePoint>, accuracyMeters: Double?): Boolean =
        contains(point, polygon, accuracyMeters, boundaryMarginMeters = 0.0)

    /**
     * The extra margin is reserved for an already-active zone's exit hysteresis; it is not used
     * to make first entry easier. Callers should keep the margin small and bounded.
     */
    fun contains(
        point: ZonePoint,
        polygon: List<ZonePoint>,
        accuracyMeters: Double?,
        boundaryMarginMeters: Double
    ): Boolean {
        if (polygon.size < 3 || !point.isValid() || polygon.any { !it.isValid() }) return false

        val local = polygon.map { vertex -> vertex.toLocalMeters(point) }
        val minX = local.minOf { it.x }
        val maxX = local.maxOf { it.x }
        val minY = local.minOf { it.y }
        val maxY = local.maxOf { it.y }
        val accuracyRadius = accuracyMeters
            ?.takeIf { it.isFinite() && it in 0.0..MAX_ACCURACY_EXPANSION_METERS }
            ?: 0.0
        val boundaryMargin = boundaryMarginMeters
            .takeIf { it.isFinite() && it in 0.0..MAX_BOUNDARY_MARGIN_METERS }
            ?: 0.0
        val radius = accuracyRadius + boundaryMargin

        // Cheap bounding-box rejection before the polygon edge loop.
        if (minX > radius || maxX < -radius || minY > radius || maxY < -radius) return false

        if (isInsideEvenOdd(0.0, 0.0, local)) return true
        if (radius <= 0.0) return false

        val radiusSquared = radius * radius
        for (index in local.indices) {
            val a = local[index]
            val b = local[(index + 1) % local.size]
            if (distanceSquaredToSegment(0.0, 0.0, a.x, a.y, b.x, b.y) <= radiusSquared) {
                return true
            }
        }
        return false
    }

    private data class LocalPoint(val x: Double, val y: Double)

    private fun ZonePoint.toLocalMeters(origin: ZonePoint): LocalPoint {
        val latitudeRadians = Math.toRadians(origin.latitude)
        val deltaLatitudeRadians = Math.toRadians(latitude - origin.latitude)
        val deltaLongitudeRadians = Math.toRadians(shortestLongitudeDelta(longitude, origin.longitude))
        return LocalPoint(
            x = EARTH_RADIUS_METERS * deltaLongitudeRadians * cos(latitudeRadians),
            y = EARTH_RADIUS_METERS * deltaLatitudeRadians
        )
    }

    private fun shortestLongitudeDelta(longitude: Double, reference: Double): Double {
        var delta = (longitude - reference) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private fun isInsideEvenOdd(x: Double, y: Double, polygon: List<LocalPoint>): Boolean {
        var inside = false
        var previousIndex = polygon.lastIndex
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[previousIndex]
            if (distanceSquaredToSegment(x, y, a.x, a.y, b.x, b.y) <=
                EDGE_TOLERANCE_METERS * EDGE_TOLERANCE_METERS
            ) {
                return true
            }
            if ((a.y > y) != (b.y > y)) {
                val crossingX = (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
                if (x < crossingX) inside = !inside
            }
            previousIndex = index
        }
        return inside
    }

    private fun distanceSquaredToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-12) {
            val x = px - ax
            val y = py - ay
            return x * x + y * y
        }
        val projection = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val nearestX = ax + projection * dx
        val nearestY = ay + projection * dy
        val x = px - nearestX
        val y = py - nearestY
        return x * x + y * y
    }

    private fun ZonePoint.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/**
 * Stateful membership for the live GNSS detector. Entering a zone uses the fix accuracy circle;
 * after entry, a small bounded margin prevents noisy coordinates near the boundary from toggling
 * the risk flag on every callback. An empty/disabled zone list clears membership.
 */
class RiskZoneMembershipTracker(exitHysteresisMeters: Double = 25.0) {
    private val exitMarginMeters = exitHysteresisMeters
        .takeIf { it.isFinite() && it in 0.0..50.0 } ?: 25.0
    private var currentlyInside = false

    @Synchronized
    fun update(
        point: ZonePoint,
        enabledZones: List<List<ZonePoint>>,
        accuracyMeters: Double? = null
    ): Boolean {
        val margin = if (currentlyInside) exitMarginMeters else 0.0
        currentlyInside = enabledZones.any { polygon ->
            ZoneGeometry.contains(point, polygon, accuracyMeters, margin)
        }
        return currentlyInside
    }

    @Synchronized
    fun reset() {
        currentlyInside = false
    }
}
