package org.cleargps.fusion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A world-frame horizontal acceleration sample, timestamped in the same monotonic clock as fixes.
 * It is a motion input, not an absolute position measurement.
 */
data class HorizontalAccelerationSample(
    val eastMetersPerSecondSquared: Double,
    val northMetersPerSecondSquared: Double,
    val elapsedRealtimeNanos: Long
)

/**
 * Applies a deliberately short inertial correction to the constant-velocity fusion prediction.
 *
 * The absolute-measurement timeline and its trust gates remain unchanged. Acceleration is integrated
 * only from valid, closely spaced samples after the last accepted absolute measurement, while the
 * estimate is still inside its two-second prediction horizon. A missing or noisy IMU window must
 * fall back to the caller's existing estimate rather than extend its lifetime.
 */
object PositionFusionMotionModel {
    const val MAXIMUM_PREDICTION_AGE_NANOS = 2_000_000_000L
    const val MAXIMUM_SAMPLE_GAP_NANOS = 250_000_000L

    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MAXIMUM_HORIZONTAL_ACCELERATION_MPS2 = 12.0
    private const val MAXIMUM_POSITION_CORRECTION_METERS = 15.0
    private const val ACCELERATION_UNCERTAINTY_STD_MPS2 = 1.5
    private const val ACCELERATION_DEADBAND_MPS2 = 0.12
    private const val MINIMUM_BEARING_SPEED_MPS = 0.5
    private const val MAXIMUM_SPEED_MPS = 100.0

    /**
     * Returns null if motion evidence is insufficient. The service should then retain the ordinary
     * constant-velocity estimate. A successful result retains the same absolute-source metadata.
     */
    fun compensate(
        estimate: FusedPosition,
        samples: List<HorizontalAccelerationSample>,
        nowElapsedRealtimeNanos: Long
    ): FusedPosition? {
        if (nowElapsedRealtimeNanos <= 0L || estimate.elapsedRealtimeNanos != nowElapsedRealtimeNanos) return null
        if (!estimate.latitude.isFinite() || estimate.latitude !in -90.0..90.0 ||
            !estimate.longitude.isFinite() || estimate.longitude !in -180.0..180.0
        ) return null
        if (!estimate.horizontalAccuracyMeters.isFinite() || estimate.horizontalAccuracyMeters <= 0.0 ||
            !estimate.speedMetersPerSecond.isFinite() || estimate.speedMetersPerSecond < 0.0 ||
            estimate.speedMetersPerSecond > MAXIMUM_SPEED_MPS
        ) return null
        val bearing = estimate.bearingDegrees
        if (bearing != null && (!bearing.isFinite() || bearing !in 0.0..360.0)) return null
        if (bearing == null && estimate.speedMetersPerSecond > 0.05) return null
        if (estimate.lastMeasurementElapsedRealtimeNanos <= 0L ||
            estimate.lastMeasurementElapsedRealtimeNanos > nowElapsedRealtimeNanos
        ) return null

        val ageNanos = nowElapsedRealtimeNanos - estimate.lastMeasurementElapsedRealtimeNanos
        if (ageNanos > MAXIMUM_PREDICTION_AGE_NANOS) return null
        if (ageNanos == 0L) return estimate
        if (samples.isEmpty()) return null

        val anchorTime = estimate.lastMeasurementElapsedRealtimeNanos
        var previousTimestamp = 0L
        for (sample in samples) {
            if (sample.elapsedRealtimeNanos < anchorTime ||
                sample.elapsedRealtimeNanos > nowElapsedRealtimeNanos ||
                sample.elapsedRealtimeNanos <= previousTimestamp
            ) return null
            if (!sample.eastMetersPerSecondSquared.isFinite() ||
                !sample.northMetersPerSecondSquared.isFinite() ||
                hypot(sample.eastMetersPerSecondSquared, sample.northMetersPerSecondSquared) >
                    MAXIMUM_HORIZONTAL_ACCELERATION_MPS2
            ) return null
            if (previousTimestamp > 0L &&
                sample.elapsedRealtimeNanos - previousTimestamp > MAXIMUM_SAMPLE_GAP_NANOS
            ) return null
            previousTimestamp = sample.elapsedRealtimeNanos
        }
        if (samples.first().elapsedRealtimeNanos - anchorTime > MAXIMUM_SAMPLE_GAP_NANOS ||
            nowElapsedRealtimeNanos - samples.last().elapsedRealtimeNanos > MAXIMUM_SAMPLE_GAP_NANOS
        ) return null

        val initialBearingRadians = Math.toRadians(bearing ?: 0.0)
        val initialEastVelocity = estimate.speedMetersPerSecond * sin(initialBearingRadians)
        val initialNorthVelocity = estimate.speedMetersPerSecond * cos(initialBearingRadians)
        var eastCorrectionMeters = 0.0
        var northCorrectionMeters = 0.0
        var eastVelocityCorrection = 0.0
        var northVelocityCorrection = 0.0

        for (index in samples.indices) {
            val sample = samples[index]
            val start = sample.elapsedRealtimeNanos
            val end = samples.getOrNull(index + 1)?.elapsedRealtimeNanos ?: nowElapsedRealtimeNanos
            if (end <= start) continue

            val eastAcceleration = sample.eastMetersPerSecondSquared.takeIf {
                abs(it) >= ACCELERATION_DEADBAND_MPS2
            } ?: 0.0
            val northAcceleration = sample.northMetersPerSecondSquared.takeIf {
                abs(it) >= ACCELERATION_DEADBAND_MPS2
            } ?: 0.0
            val startToNowSeconds = (nowElapsedRealtimeNanos - start) / NANOS_PER_SECOND
            val endToNowSeconds = (nowElapsedRealtimeNanos - end) / NANOS_PER_SECOND
            val positionWeight = 0.5 * (
                startToNowSeconds * startToNowSeconds - endToNowSeconds * endToNowSeconds
                )
            val intervalSeconds = (end - start) / NANOS_PER_SECOND
            eastCorrectionMeters += eastAcceleration * positionWeight
            northCorrectionMeters += northAcceleration * positionWeight
            eastVelocityCorrection += eastAcceleration * intervalSeconds
            northVelocityCorrection += northAcceleration * intervalSeconds
        }

        val correctionDistance = hypot(eastCorrectionMeters, northCorrectionMeters)
        if (!correctionDistance.isFinite() || correctionDistance > MAXIMUM_POSITION_CORRECTION_METERS) return null

        val latitudeRadians = Math.toRadians(estimate.latitude)
        val cosineLatitude = cos(latitudeRadians)
        if (abs(cosineLatitude) < 1e-6 && abs(eastCorrectionMeters) > 0.0) return null
        val correctedLatitude = estimate.latitude + Math.toDegrees(northCorrectionMeters / EARTH_RADIUS_METERS)
        val longitudeCorrectionDegrees = if (abs(eastCorrectionMeters) <= 0.0) {
            0.0
        } else {
            Math.toDegrees(eastCorrectionMeters / (EARTH_RADIUS_METERS * cosineLatitude))
        }
        val correctedLongitude = normalizeLongitude(estimate.longitude + longitudeCorrectionDegrees)
        if (!correctedLatitude.isFinite() || correctedLatitude !in -90.0..90.0 ||
            !correctedLongitude.isFinite() || correctedLongitude !in -180.0..180.0
        ) return null

        val correctedEastVelocity = initialEastVelocity + eastVelocityCorrection
        val correctedNorthVelocity = initialNorthVelocity + northVelocityCorrection
        val correctedSpeed = hypot(correctedEastVelocity, correctedNorthVelocity)
        if (!correctedSpeed.isFinite() || correctedSpeed > MAXIMUM_SPEED_MPS) return null
        val correctedBearing = if (correctedSpeed < MINIMUM_BEARING_SPEED_MPS) {
            null
        } else {
            normalizeBearingDegrees(Math.toDegrees(atan2(correctedEastVelocity, correctedNorthVelocity)))
        }

        val uncertaintyGrowthMeters = 0.5 * ACCELERATION_UNCERTAINTY_STD_MPS2 *
            (ageNanos / NANOS_PER_SECOND) * (ageNanos / NANOS_PER_SECOND)
        val correctedAccuracy = hypot(estimate.horizontalAccuracyMeters, uncertaintyGrowthMeters)
        if (!correctedAccuracy.isFinite()) return null

        return estimate.copy(
            latitude = correctedLatitude,
            longitude = correctedLongitude,
            horizontalAccuracyMeters = correctedAccuracy,
            speedMetersPerSecond = correctedSpeed,
            bearingDegrees = correctedBearing,
            elapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
    }

    private fun normalizeLongitude(longitudeDegrees: Double): Double {
        val shifted = (longitudeDegrees + 180.0) % 360.0
        val positive = if (shifted < 0.0) shifted + 360.0 else shifted
        return positive - 180.0
    }

    private fun normalizeBearingDegrees(bearingDegrees: Double): Double {
        val normalized = bearingDegrees % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }
}
