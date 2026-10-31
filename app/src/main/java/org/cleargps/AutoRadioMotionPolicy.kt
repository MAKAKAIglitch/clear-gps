package org.cleargps

import org.cleargps.fusion.PositionMeasurement
import org.cleargps.fusion.PositionMeasurementSource
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Bounds automatic RADIO movement using elapsed time and a capped uncertainty allowance. */
internal object AutoRadioMotionPolicy {
    const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 83.3
    const val MAX_UNCERTAINTY_SLACK_METERS = 500.0
    const val MIN_UNCERTAINTY_SLACK_METERS = 25.0
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MAX_MEASUREMENT_UNCERTAINTY_METERS = 50_000.0

    fun allows(previous: PositionMeasurement?, incoming: PositionMeasurement): Boolean {
        if (!isValidRadioMeasurement(incoming)) return false
        if (previous == null) return true
        if (!isValidRadioMeasurement(previous) || incoming.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) return false
        val seconds = (incoming.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
        if (!seconds.isFinite() || seconds <= 0.0) return false
        val distance = distanceMeters(previous.latitude, previous.longitude, incoming.latitude, incoming.longitude)
        if (!distance.isFinite()) return false
        val slack = hypot(previous.horizontalUncertaintyMeters, incoming.horizontalUncertaintyMeters)
            .coerceIn(MIN_UNCERTAINTY_SLACK_METERS, MAX_UNCERTAINTY_SLACK_METERS)
        return distance <= MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND * seconds + slack
    }

    private fun isValidRadioMeasurement(m: PositionMeasurement) =
        m.source == PositionMeasurementSource.RADIO &&
            m.latitude.isFinite() && m.latitude in -90.0..90.0 &&
            m.longitude.isFinite() && m.longitude in -180.0..180.0 &&
            m.horizontalUncertaintyMeters.isFinite() &&
            m.horizontalUncertaintyMeters in 0.1..MAX_MEASUREMENT_UNCERTAINTY_METERS &&
            m.elapsedRealtimeNanos > 0L

    private fun distanceMeters(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val dLat = Math.toRadians(latB - latA)
        val dLon = Math.toRadians(lonB - lonA)
        val a = (
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(Math.toRadians(latA)) * cos(Math.toRadians(latB)) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
            ).coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2.0 * asin(sqrt(a))
    }
}
