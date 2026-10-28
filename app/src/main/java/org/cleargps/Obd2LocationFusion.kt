package org.cleargps

import android.content.SharedPreferences
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** A speed PID sample with its original monotonic midpoint and request duration. */
internal data class Obd2SpeedSample(
    val speedKmh: Int,
    val elapsedRealtimeNanos: Long,
    val queryDurationMillis: Long
)

/**
 * Validates the timestamp and quality of an OBD-II PID 0D sample independently of Android.
 * Wall-clock timestamps are deliberately excluded from this policy.
 */
internal object Obd2SpeedSamplePolicy {
    const val MAXIMUM_QUERY_DURATION_MILLIS = 1_500L

    fun fresh(
        speedKmh: Int?,
        sampleElapsedRealtimeNanos: Long,
        queryDurationMillis: Long?,
        nowElapsedRealtimeNanos: Long,
        maximumAgeNanos: Long = Obd2SharedState.SPEED_MAX_AGE_NANOS
    ): Obd2SpeedSample? {
        if (speedKmh == null || speedKmh !in 0..Obd2SharedState.MAX_PLAUSIBLE_SPEED_KMH) return null
        if (sampleElapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos <= 0L ||
            sampleElapsedRealtimeNanos > nowElapsedRealtimeNanos ||
            maximumAgeNanos < 0L ||
            nowElapsedRealtimeNanos - sampleElapsedRealtimeNanos > maximumAgeNanos
        ) return null
        if (queryDurationMillis == null ||
            queryDurationMillis !in 0L..MAXIMUM_QUERY_DURATION_MILLIS
        ) return null
        return Obd2SpeedSample(speedKmh, sampleElapsedRealtimeNanos, queryDurationMillis)
    }
}

/**
 * Shared, short-lived OBD-II speed sample for the map and foreground location service.
 * The data is volatile in meaning even though SharedPreferences is used for process hand-off:
 * speed is accepted only for a few seconds and never treated as a coordinate.
 */
internal object Obd2SharedState {
    const val KEY_ADAPTER_ADDRESS = "obd2_adapter_address"
    const val KEY_SPEED_KMH = "obd2_vehicle_speed_kmh"
    const val KEY_SPEED_AT = "obd2_vehicle_speed_at"
    const val KEY_SPEED_AT_ELAPSED_REALTIME_NANOS = "obd2_vehicle_speed_at_elapsed_realtime_nanos"
    const val KEY_SPEED_QUERY_DURATION_MS = "obd2_vehicle_speed_query_duration_ms"
    const val KEY_SPEED_CORRECTION_SCALE = "obd2_session_speed_correction_scale"
    const val SPEED_MAX_AGE_MS = 5_000L
    const val SPEED_MAX_AGE_NANOS = SPEED_MAX_AGE_MS * 1_000_000L
    const val SPEED_HISTORY_MAX_AGE_NANOS = 30_000_000_000L
    const val SPEED_HISTORY_MAX_SAMPLES = 32
    const val MAX_PLAUSIBLE_SPEED_KMH = Obd2Protocol.MAX_PLAUSIBLE_SPEED_KMH

    private val speedHistory = mutableListOf<Obd2SpeedSample>()

    fun freshSpeedKmh(
        prefs: SharedPreferences,
        nowMillis: Long = System.currentTimeMillis()
    ): Int? {
        val speed = prefs.getInt(KEY_SPEED_KMH, -1)
        val updatedAt = prefs.getLong(KEY_SPEED_AT, 0L)
        return speed.takeIf {
            it in 0..MAX_PLAUSIBLE_SPEED_KMH &&
                updatedAt > 0L &&
                nowMillis >= updatedAt &&
                nowMillis - updatedAt <= SPEED_MAX_AGE_MS
        }
    }

    /**
     * A separate monotonic sample is used by dead reckoning. It remains invalid when restoring
     * older preferences that only have a wall-clock timestamp.
     */
    fun freshSpeedSample(
        prefs: SharedPreferences,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ): Obd2SpeedSample? = Obd2SpeedSamplePolicy.fresh(
        speedKmh = prefs.getInt(KEY_SPEED_KMH, -1),
        sampleElapsedRealtimeNanos = prefs.getLong(KEY_SPEED_AT_ELAPSED_REALTIME_NANOS, 0L),
        queryDurationMillis = prefs.getLong(KEY_SPEED_QUERY_DURATION_MS, -1L).takeIf { it >= 0L },
        nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
    )

    @Synchronized
    fun speedSamplesSince(
        afterElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long
    ): List<Obd2SpeedSample> {
        if (afterElapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos <= afterElapsedRealtimeNanos) {
            return emptyList()
        }
        val selected = speedHistory.filter {
            it.elapsedRealtimeNanos > afterElapsedRealtimeNanos &&
                it.elapsedRealtimeNanos <= nowElapsedRealtimeNanos
        }
        if (selected.isEmpty()) return emptyList()
        if (nowElapsedRealtimeNanos - selected.last().elapsedRealtimeNanos > SPEED_MAX_AGE_NANOS) {
            return emptyList()
        }
        if (selected.last().elapsedRealtimeNanos - selected.first().elapsedRealtimeNanos > SPEED_HISTORY_MAX_AGE_NANOS) {
            return emptyList()
        }
        return selected.toList()
    }

    @Synchronized
    private fun recordSpeedSample(sample: Obd2SpeedSample?, nowElapsedRealtimeNanos: Long) {
        val last = speedHistory.lastOrNull()
        if (sample == null) {
            speedHistory.clear()
            return
        }
        if (last != null && sample.elapsedRealtimeNanos <= last.elapsedRealtimeNanos) {
            speedHistory.clear()
            return
        }
        if (last != null && sample.elapsedRealtimeNanos - last.elapsedRealtimeNanos > SPEED_MAX_AGE_NANOS) {
            speedHistory.clear()
        }
        speedHistory += sample
        val cutoff = (nowElapsedRealtimeNanos - SPEED_HISTORY_MAX_AGE_NANOS).coerceAtLeast(0L)
        while (speedHistory.size > SPEED_HISTORY_MAX_SAMPLES ||
            (speedHistory.isNotEmpty() && speedHistory.first().elapsedRealtimeNanos < cutoff)
        ) {
            speedHistory.removeAt(0)
        }
    }

    fun storeTelemetry(
        prefs: SharedPreferences,
        telemetry: Obd2Telemetry,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) {
        val rawSpeed = telemetry.vehicleSpeedKmh?.takeIf { it in 0..MAX_PLAUSIBLE_SPEED_KMH }
        val sampleElapsedRealtimeNanos = telemetry.speedSampleElapsedRealtimeNanos?.takeIf {
            nowElapsedRealtimeNanos > 0L && it > 0L && it <= nowElapsedRealtimeNanos &&
                nowElapsedRealtimeNanos - it <= SPEED_MAX_AGE_NANOS
        }
        val scale = prefs.getFloat(KEY_SPEED_CORRECTION_SCALE, 1.0f).toDouble()
            .takeIf { it.isFinite() && it in Obd2SpeedCalibration.MIN_CORRECTION_SCALE..Obd2SpeedCalibration.MAX_CORRECTION_SCALE }
            ?: 1.0
        val speed = rawSpeed?.takeIf { sampleElapsedRealtimeNanos != null }
            ?.let { (it * scale).roundToInt() }
            ?.takeIf { it in 0..MAX_PLAUSIBLE_SPEED_KMH }
        val queryDuration = telemetry.speedQueryDurationMillis
        val usableSample = Obd2SpeedSamplePolicy.fresh(
            speedKmh = speed,
            sampleElapsedRealtimeNanos = sampleElapsedRealtimeNanos ?: 0L,
            queryDurationMillis = queryDuration,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
        prefs.edit()
            .putInt(KEY_SPEED_KMH, speed ?: -1)
            .putLong(KEY_SPEED_AT, if (speed != null) telemetry.updatedAt else 0L)
            .putLong(KEY_SPEED_AT_ELAPSED_REALTIME_NANOS, if (speed != null) sampleElapsedRealtimeNanos!! else 0L)
            .putLong(KEY_SPEED_QUERY_DURATION_MS, if (speed != null) queryDuration ?: -1L else -1L)
            .apply()
        recordSpeedSample(usableSample, nowElapsedRealtimeNanos)
    }

    /** Session-only service hand-off; never keyed by VIN or saved as a vehicle profile. */
    fun setSpeedCorrectionScale(prefs: SharedPreferences, scale: Double?) {
        val validated = scale?.takeIf {
            it.isFinite() && it in Obd2SpeedCalibration.MIN_CORRECTION_SCALE..Obd2SpeedCalibration.MAX_CORRECTION_SCALE
        }?.toFloat()
        val editor = prefs.edit()
        if (validated == null) editor.remove(KEY_SPEED_CORRECTION_SCALE)
        else editor.putFloat(KEY_SPEED_CORRECTION_SCALE, validated)
        editor.apply()
    }

    @Synchronized
    fun clearSpeed(prefs: SharedPreferences) {
        speedHistory.clear()
        prefs.edit()
            .putInt(KEY_SPEED_KMH, -1)
            .putLong(KEY_SPEED_AT, 0L)
            .putLong(KEY_SPEED_AT_ELAPSED_REALTIME_NANOS, 0L)
            .putLong(KEY_SPEED_QUERY_DURATION_MS, -1L)
            .remove(KEY_SPEED_CORRECTION_SCALE)
            .apply()
    }
}

internal data class Obd2PredictedPosition(
    val coordinate: MapCoordinate,
    val accuracyMeters: Double,
    val travelledMeters: Double,
    val ageMillis: Long
)

/**
 * A deliberately bounded fallback for a brief GPS outage.
 *
 * OBD-II supplies speed but not heading/coordinates. Prediction is allowed only when a recent
 * GPS fix has a bearing and a fresh, plausible vehicle-speed PID is available. It is disabled
 * when either input is stale or the projected distance would be too large. Radio-only mode
 * must not call this method because it intentionally does not use GPS bearing.
 */
internal object Obd2LocationFusion {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_FIX_AGE_NANOS = 3_000_000_000L
    private const val MAX_FIX_AGE_NANOS = 30_000_000_000L
    private const val MAX_PREDICTED_DISTANCE_METERS = 300.0
    private const val MAX_BASE_FIX_ACCURACY_METERS = 100.0
    private const val BASE_UNCERTAINTY_GROWTH_METERS_PER_SECOND = 1.5
    private const val DISTANCE_UNCERTAINTY_FACTOR = 0.12

    fun predictFromGps(
        lastFix: MapCoordinate,
        baseAccuracyMeters: Double?,
        bearingDegrees: Double?,
        fixElapsedRealtimeNanos: Long,
        speedSamples: List<Obd2SpeedSample>,
        nowElapsedRealtimeNanos: Long
    ): Obd2PredictedPosition? {
        if (!lastFix.latitude.isFinite() || lastFix.latitude !in -90.0..90.0 ||
            !lastFix.longitude.isFinite() || lastFix.longitude !in -180.0..180.0
        ) return null
        if (fixElapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos <= 0L ||
            fixElapsedRealtimeNanos > nowElapsedRealtimeNanos
        ) return null
        val fixAgeNanos = nowElapsedRealtimeNanos - fixElapsedRealtimeNanos
        if (fixAgeNanos !in MIN_FIX_AGE_NANOS..MAX_FIX_AGE_NANOS) return null
        if (bearingDegrees == null || !bearingDegrees.isFinite() || bearingDegrees !in 0.0..360.0) return null
        if (speedSamples.isEmpty()) return null
        val samples = speedSamples.toList()
        if (samples.first().elapsedRealtimeNanos <= fixElapsedRealtimeNanos ||
            samples.last().elapsedRealtimeNanos > nowElapsedRealtimeNanos
        ) return null
        val validated = samples.mapNotNull { sample ->
            Obd2SpeedSamplePolicy.fresh(
                speedKmh = sample.speedKmh,
                sampleElapsedRealtimeNanos = sample.elapsedRealtimeNanos,
                queryDurationMillis = sample.queryDurationMillis,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                maximumAgeNanos = Obd2SharedState.SPEED_HISTORY_MAX_AGE_NANOS
            )
        }
        if (validated.size != samples.size) return null
        val firstGapNanos = samples.first().elapsedRealtimeNanos - fixElapsedRealtimeNanos
        if (firstGapNanos !in 1L..Obd2SharedState.SPEED_MAX_AGE_NANOS) return null
        if (nowElapsedRealtimeNanos - samples.last().elapsedRealtimeNanos > Obd2SharedState.SPEED_MAX_AGE_NANOS) return null
        for (index in 1 until samples.size) {
            val gap = samples[index].elapsedRealtimeNanos - samples[index - 1].elapsedRealtimeNanos
            if (gap !in 1L..Obd2SharedState.SPEED_MAX_AGE_NANOS) return null
        }

        // Approximate the short unobserved edge intervals; integrate the intervals between
        // actual PID samples trapezoidally. All gaps and the total distance remain bounded.
        var distanceMeters = samples.first().speedKmh / 3.6 *
            (firstGapNanos / 1_000_000_000.0)
        for (index in 1 until samples.size) {
            val before = samples[index - 1]
            val current = samples[index]
            val intervalSeconds = (current.elapsedRealtimeNanos - before.elapsedRealtimeNanos) / 1_000_000_000.0
            distanceMeters += ((before.speedKmh + current.speedKmh) / 2.0) / 3.6 * intervalSeconds
        }
        val trailingSeconds = (nowElapsedRealtimeNanos - samples.last().elapsedRealtimeNanos) / 1_000_000_000.0
        distanceMeters += samples.last().speedKmh / 3.6 * trailingSeconds
        if (!distanceMeters.isFinite() || distanceMeters !in 1.0..MAX_PREDICTED_DISTANCE_METERS) return null

        val lat1 = Math.toRadians(lastFix.latitude)
        val lon1 = Math.toRadians(lastFix.longitude)
        val bearing = Math.toRadians(bearingDegrees % 360.0)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val lat2 = asin(
            (sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        val longitude = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        val coordinate = MapCoordinate(Math.toDegrees(lat2), longitude)
        if (!coordinate.latitude.isFinite() || !coordinate.longitude.isFinite()) return null

        // A dead-reckoning step cannot rescue a poor or unknown starting fix. Refuse to
        // extrapolate rather than assigning an invented default precision to that position.
        val baseAccuracy = baseAccuracyMeters
            ?.takeIf { it.isFinite() && it in 1.0..MAX_BASE_FIX_ACCURACY_METERS }
            ?: return null
        val predictionAgeSeconds = fixAgeNanos / 1_000_000_000.0
        val estimatedAccuracy = (
            baseAccuracy +
                predictionAgeSeconds * BASE_UNCERTAINTY_GROWTH_METERS_PER_SECOND +
                distanceMeters * DISTANCE_UNCERTAINTY_FACTOR
            ).coerceAtMost(2_000.0)

        return Obd2PredictedPosition(
            coordinate = coordinate,
            accuracyMeters = estimatedAccuracy,
            travelledMeters = distanceMeters,
            ageMillis = fixAgeNanos / 1_000_000L
        )
    }
}
