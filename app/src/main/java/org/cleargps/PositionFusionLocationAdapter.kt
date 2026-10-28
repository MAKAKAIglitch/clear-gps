package org.cleargps

import android.location.Location
import android.location.LocationManager
import org.cleargps.core.FilterState
import org.cleargps.core.LocationFilterEngine
import org.cleargps.fusion.FusedPosition
import org.cleargps.fusion.PositionMeasurement
import org.cleargps.fusion.PositionMeasurementSource
import kotlin.math.max

/**
 * Android boundary for the platform-independent fusion core.
 *
 * Trust decisions stay outside this adapter. A GNSS fix is eligible only after the existing
 * location filter has accepted it as NORMAL GPS and the location is not a mock or zone fix.
 */
internal object PositionFusionLocationAdapter {
    const val MAX_MEASUREMENT_UNCERTAINTY_METERS = 5_000.0
    const val MAX_OUTPUT_AGE_NANOS = 2_000_000_000L
    const val MAX_RADIO_EVIDENCE_AGE_NANOS = 45_000_000_000L

    private const val GNSS_UNCERTAINTY_MULTIPLIER = 2.0
    private const val MINIMUM_GNSS_UNCERTAINTY_METERS = 3.0
    private const val MINIMUM_RADIO_UNCERTAINTY_METERS = 10.0
    private const val MAX_OUTPUT_ACCURACY_METERS = 50_000.0
    private const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 100.0

    fun isTrustedGpsDecision(
        trustState: FilterState,
        decisionSource: String,
        inRiskZone: Boolean
    ): Boolean = trustState == FilterState.NORMAL && decisionSource == "GPS" && !inRiskZone

    /** A GPS-labelled candidate is unsafe to publish unless its trust decision is NORMAL and outside zones. */
    fun shouldSuppressGpsOutput(
        trustState: FilterState,
        decisionSource: String,
        inRiskZone: Boolean
    ): Boolean = decisionSource == "GPS" && !isTrustedGpsDecision(trustState, decisionSource, inRiskZone)

    fun trustedGnssMeasurement(
        location: Location,
        trustState: FilterState,
        decisionSource: String,
        inRiskZone: Boolean
    ): PositionMeasurement? {
        if (!isTrustedGpsDecision(trustState, decisionSource, inRiskZone)) return null
        if (!location.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)) return null
        if (LocationFilterEngine.isMockLocation(location)) return null
        if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            location.elapsedRealtimeNanos <= 0L ||
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy <= 0f
        ) return null

        val uncertainty = max(
            MINIMUM_GNSS_UNCERTAINTY_METERS,
            location.accuracy.toDouble() * GNSS_UNCERTAINTY_MULTIPLIER
        )
        if (!uncertainty.isFinite() || uncertainty > MAX_MEASUREMENT_UNCERTAINTY_METERS) return null

        return PositionMeasurement(
            source = PositionMeasurementSource.GNSS,
            latitude = location.latitude,
            longitude = location.longitude,
            horizontalUncertaintyMeters = uncertainty,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos
        )
    }

    /**
     * Converts only a radio estimate that retains timestamps from its actual observations.
     * The wall-clock [RadioEstimate.updatedAt] is deliberately not used to manufacture monotonic
     * time; estimates restored from preferences therefore cannot enter live fusion.
     */
    fun radioMeasurement(
        estimate: RadioEstimate,
        nowElapsedRealtimeNanos: Long
    ): PositionMeasurement? {
        val measurementTime = estimate.measurementElapsedRealtimeNanos ?: return null
        if (nowElapsedRealtimeNanos <= 0L || measurementTime <= 0L ||
            measurementTime > nowElapsedRealtimeNanos ||
            nowElapsedRealtimeNanos - measurementTime > MAX_RADIO_EVIDENCE_AGE_NANOS
        ) return null

        if (!estimate.latitude.isFinite() || estimate.latitude !in -90.0..90.0 ||
            !estimate.longitude.isFinite() || estimate.longitude !in -180.0..180.0
        ) return null

        val uncertainty = estimate.accuracyMeters
        if (!uncertainty.isFinite() || uncertainty <= 0.0 ||
            uncertainty > MAX_MEASUREMENT_UNCERTAINTY_METERS ||
            estimate.matchedCount < 2 || estimate.matchedRadioTypes.isEmpty()
        ) return null

        return PositionMeasurement(
            source = PositionMeasurementSource.RADIO,
            latitude = estimate.latitude,
            longitude = estimate.longitude,
            horizontalUncertaintyMeters = max(MINIMUM_RADIO_UNCERTAINTY_METERS, uncertainty),
            elapsedRealtimeNanos = measurementTime
        )
    }

    /**
     * Replaces position and horizontal accuracy only. Available altitude, speed, bearing and
     * provider metadata are preserved; a missing speed/bearing is filled only after the filter
     * has seen enough positions to estimate motion.
     */
    fun toLocation(
        estimate: FusedPosition,
        template: Location,
        wallTimeMillis: Long,
        nowElapsedRealtimeNanos: Long
    ): Location? {
        val age = nowElapsedRealtimeNanos - estimate.elapsedRealtimeNanos
        if (nowElapsedRealtimeNanos <= 0L || estimate.elapsedRealtimeNanos <= 0L ||
            age < 0L || age > MAX_OUTPUT_AGE_NANOS
        ) return null
        if (!estimate.latitude.isFinite() || estimate.latitude !in -90.0..90.0 ||
            !estimate.longitude.isFinite() || estimate.longitude !in -180.0..180.0 ||
            !estimate.horizontalAccuracyMeters.isFinite() ||
            estimate.horizontalAccuracyMeters <= 0.0 ||
            estimate.horizontalAccuracyMeters > MAX_OUTPUT_ACCURACY_METERS
        ) return null

        return Location(template).apply {
            latitude = estimate.latitude
            longitude = estimate.longitude
            accuracy = estimate.horizontalAccuracyMeters.toFloat().coerceAtLeast(1f)
            time = wallTimeMillis
            elapsedRealtimeNanos = estimate.elapsedRealtimeNanos

            if (!hasSpeed() && estimate.acceptedMeasurementCount >= 2 &&
                estimate.speedMetersPerSecond.isFinite() &&
                estimate.speedMetersPerSecond in 0.0..MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND
            ) {
                speed = estimate.speedMetersPerSecond.toFloat()
            }
            if (!hasBearing() && estimate.acceptedMeasurementCount >= 2) {
                estimate.bearingDegrees?.takeIf {
                    it.isFinite() && it >= 0.0 && it < 360.0
                }?.let { bearing = it.toFloat() }
            }
        }
    }
}
