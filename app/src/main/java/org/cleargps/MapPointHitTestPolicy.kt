package org.cleargps

import kotlin.math.abs
import kotlin.math.sqrt

/** A rendered radio marker and its projected screen-space center. */
internal data class MapPointHitCandidate(
    val pointId: Long,
    val screenX: Float,
    val screenY: Float
)

internal data class MapPointHitSelection(
    val pointIds: List<Long>,
    val nearestDistancePx: Float
)

/**
 * Resolves a finger tap to the nearest visible radio marker while preserving a square
 * 48 dp touch target. Markers whose centers nearly coincide are returned together for
 * a "what's here" list instead of arbitrarily choosing one overlapping record.
 */
internal object MapPointHitTestPolicy {
    const val MIN_TARGET_SIZE_DP = 48f
    const val COLOCATED_RADIUS_DP = 6f

    fun selectNearest(
        candidates: List<MapPointHitCandidate>,
        tapX: Float,
        tapY: Float,
        density: Float
    ): MapPointHitSelection? {
        if (!tapX.isFinite() || !tapY.isFinite() || !density.isFinite() || density <= 0f) {
            return null
        }

        val uniqueById = LinkedHashMap<Long, MapPointHitCandidate>()
        for (candidate in candidates) {
            if (candidate.pointId <= 0L ||
                !candidate.screenX.isFinite() ||
                !candidate.screenY.isFinite()
            ) continue

            val current = uniqueById[candidate.pointId]
            if (current == null ||
                distanceSquared(candidate.screenX, candidate.screenY, tapX, tapY) <
                distanceSquared(current.screenX, current.screenY, tapX, tapY)
            ) {
                uniqueById[candidate.pointId] = candidate
            }
        }
        if (uniqueById.isEmpty()) return null

        val targetRadiusPx = MIN_TARGET_SIZE_DP * density / 2f
        val eligible = uniqueById.values.filter {
            abs(it.screenX - tapX) <= targetRadiusPx &&
                abs(it.screenY - tapY) <= targetRadiusPx
        }
        val nearest = eligible.minWithOrNull(
            compareBy<MapPointHitCandidate> {
                distanceSquared(it.screenX, it.screenY, tapX, tapY)
            }.thenBy { it.pointId }
        ) ?: return null

        val nearestDistanceSquared = distanceSquared(nearest.screenX, nearest.screenY, tapX, tapY)
        val colocatedRadiusPx = COLOCATED_RADIUS_DP * density
        val colocatedRadiusSquared = colocatedRadiusPx * colocatedRadiusPx
        val grouped = eligible
            .filter {
                distanceSquared(it.screenX, it.screenY, nearest.screenX, nearest.screenY) <=
                    colocatedRadiusSquared
            }
            .sortedWith(
                compareBy<MapPointHitCandidate> {
                    distanceSquared(it.screenX, it.screenY, tapX, tapY)
                }.thenBy { it.pointId }
            )
            .map { it.pointId }

        return MapPointHitSelection(
            pointIds = grouped,
            nearestDistancePx = sqrt(nearestDistanceSquared)
        )
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}
