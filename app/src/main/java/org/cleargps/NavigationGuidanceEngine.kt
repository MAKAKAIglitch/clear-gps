package org.cleargps

import kotlin.math.*

internal data class NavigationManeuverStep(
    val instruction: String,
    val distanceMeters: Double,
    val coordinate: NavigationCoordinate,
    val routeIndex: Int,
    val maneuverType: String,
    val modifier: String?
)

internal data class NavigationGuidanceSnapshot(
    val stepIndex: Int,
    val instruction: String,
    val distanceToManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val offRouteDistanceMeters: Double
)

internal object NavigationGuidanceEngine {
    fun snapshot(
        position: NavigationCoordinate,
        route: NavigationRouteAlternative,
        previousStepIndex: Int = 0
    ): NavigationGuidanceSnapshot? {
        if (!position.isValid() || route.coordinates.size < 2 || route.steps.isEmpty()) return null
        val nearest = nearestRoutePoint(position, route.coordinates)
        val nextIndex = route.steps.indexOfFirst { it.routeIndex > nearest.first }
            .takeIf { it >= 0 } ?: route.steps.lastIndex
        val index = max(previousStepIndex.coerceIn(0, route.steps.lastIndex), nextIndex)
        val step = route.steps[index]
        val distance = distanceMeters(position, step.coordinate).coerceAtLeast(0.0)
        val remainingDistance = distance + route.steps.drop(index).sumOf { it.distanceMeters }
        val remainingDuration = if (route.distanceMeters > 0.0) {
            route.durationSeconds * (remainingDistance / route.distanceMeters).coerceIn(0.0, 1.0)
        } else route.durationSeconds
        return NavigationGuidanceSnapshot(
            stepIndex = index,
            instruction = step.instruction,
            distanceToManeuverMeters = distance,
            remainingDistanceMeters = remainingDistance.coerceAtMost(route.distanceMeters),
            remainingDurationSeconds = remainingDuration,
            offRouteDistanceMeters = nearest.second
        )
    }

    private fun nearestRoutePoint(
        point: NavigationCoordinate,
        coordinates: List<NavigationCoordinate>
    ): Pair<Int, Double> {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        coordinates.forEachIndexed { index, candidate ->
            val distance = distanceMeters(point, candidate)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex to bestDistance
    }

    internal fun distanceMeters(a: NavigationCoordinate, b: NavigationCoordinate): Double {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2.0 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
