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
    val maneuverCoordinate: NavigationCoordinate,
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
        val nearest = nearestRouteSegment(position, route.coordinates)
        val nextIndex = route.steps.indexOfFirst { it.routeIndex > nearest.first }
            .takeIf { it >= 0 } ?: route.steps.lastIndex
        // Departure is not a useful turn instruction. Start with the first
        // actual upcoming maneuver so guidance never displays "Начните движение".
        val firstActionableIndex = route.steps.indexOfFirst { it.maneuverType != "depart" }
            .takeIf { it >= 0 } ?: route.steps.lastIndex
        val candidateIndex = max(nextIndex, firstActionableIndex)
        val index = max(previousStepIndex.coerceIn(firstActionableIndex, route.steps.lastIndex), candidateIndex)
        val step = route.steps[index]
        val distance = distanceMeters(position, step.coordinate).coerceAtLeast(0.0)
        val instruction = when {
            step.maneuverType == "depart" -> "Следуйте по маршруту"
            step.maneuverType == "arrive" && distance > 45.0 -> "Продолжайте движение к пункту назначения"
            step.instruction.contains("Начните движение", ignoreCase = true) -> "Следуйте по маршруту"
            else -> step.instruction
        }
        val remainingDistance = distance + route.steps.drop(index).sumOf { it.distanceMeters }
        val remainingDuration = if (route.distanceMeters > 0.0) {
            route.durationSeconds * (remainingDistance / route.distanceMeters).coerceIn(0.0, 1.0)
        } else route.durationSeconds
        return NavigationGuidanceSnapshot(
            stepIndex = index,
            instruction = instruction,
            maneuverCoordinate = step.coordinate,
            distanceToManeuverMeters = distance,
            remainingDistanceMeters = remainingDistance.coerceAtMost(route.distanceMeters),
            remainingDurationSeconds = remainingDuration,
            offRouteDistanceMeters = nearest.second
        )
    }

    private fun nearestRouteSegment(
        point: NavigationCoordinate,
        coordinates: List<NavigationCoordinate>
    ): Pair<Int, Double> {
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude)).coerceAtLeast(0.01)
        for (index in 0 until coordinates.lastIndex) {
            val a = coordinates[index]
            val b = coordinates[index + 1]
            val ax = (a.longitude - point.longitude) * longitudeScale
            val ay = (a.latitude - point.latitude) * latitudeScale
            val bx = (b.longitude - point.longitude) * longitudeScale
            val by = (b.latitude - point.latitude) * latitudeScale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val fraction = if (lengthSquared <= 0.0001) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val projectedX = ax + fraction * dx
            val projectedY = ay + fraction * dy
            val distance = sqrt(projectedX * projectedX + projectedY * projectedY)
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
