package org.cleargps

import kotlin.math.abs
import kotlin.math.max

internal enum class Obd2CalibrationStatus {
    WAITING_FOR_DATA, WAITING_FOR_TRUSTED_GNSS, WAITING_FOR_PHYSICAL_GNSS,
    LOW_GNSS_QUALITY, TIME_MISMATCH, OBD_QUERY_TOO_SLOW, WAITING_FOR_MOVEMENT,
    COLLECTING, SPEEDS_DIVERGE, CALIBRATED
}

internal data class Obd2SpeedCalibrationSnapshot(
    val status: Obd2CalibrationStatus = Obd2CalibrationStatus.WAITING_FOR_DATA,
    val acceptedPairs: Int = 0,
    /** Signed difference in km/h: GNSS speed minus raw OBD speed. */
    val differenceKmh: Double? = null,
    val candidateScale: Double? = null,
    val activeCorrectionScale: Double? = null
)

/**
 * Session-only OBD/GNSS speed comparison.
 * The OBD protocol has no exact ECU sample timestamp; the query/response midpoint is approximate.
 * Slow, stale or misaligned pairs are rejected. A discrepancy never contributes to spoofing risk.
 */
internal class Obd2SpeedCalibration {
    private data class Sample(val timestampNanos: Long, val gnssSpeedKmh: Double, val scale: Double)
    private val samples = mutableListOf<Sample>()
    private var lastProcessedObdTimestampNanos: Long? = null
    private var activeCorrectionScale: Double? = null
    private var snapshot = Obd2SpeedCalibrationSnapshot()

    fun reset() {
        samples.clear()
        lastProcessedObdTimestampNanos = null
        activeCorrectionScale = null
        snapshot = Obd2SpeedCalibrationSnapshot()
    }

    fun observe(
        gnssSpeedKmh: Double?,
        gnssAccuracyMeters: Double?,
        gnssSpeedAccuracyMetersPerSecond: Double?,
        gnssElapsedRealtimeNanos: Long,
        gnssProvider: String?,
        gnssIsMock: Boolean,
        gnssTrustedNormal: Boolean,
        obdSpeedKmh: Int?,
        obdSampleElapsedRealtimeNanos: Long?,
        obdQueryDurationMillis: Long?,
        nowElapsedRealtimeNanos: Long
    ): Obd2SpeedCalibrationSnapshot {
        val obdTimestamp = obdSampleElapsedRealtimeNanos
        if (obdTimestamp == null || obdSpeedKmh == null || obdQueryDurationMillis == null) {
            return setStatus(Obd2CalibrationStatus.WAITING_FOR_DATA)
        }
        val previousTimestamp = lastProcessedObdTimestampNanos
        if (previousTimestamp != null && obdTimestamp <= previousTimestamp) return snapshot
        lastProcessedObdTimestampNanos = obdTimestamp

        if (!gnssTrustedNormal) return setStatus(Obd2CalibrationStatus.WAITING_FOR_TRUSTED_GNSS)
        if (!gnssProvider.orEmpty().equals("gps", ignoreCase = true) || gnssIsMock) {
            return setStatus(Obd2CalibrationStatus.WAITING_FOR_PHYSICAL_GNSS)
        }

        val gpsSpeed = gnssSpeedKmh
        val gpsAccuracy = gnssAccuracyMeters
        if (gpsSpeed == null || !gpsSpeed.isFinite() ||
            gpsAccuracy == null || !gpsAccuracy.isFinite() || gpsAccuracy !in 0.0..20.0 ||
            gnssElapsedRealtimeNanos <= 0L ||
            (gnssSpeedAccuracyMetersPerSecond != null &&
                (!gnssSpeedAccuracyMetersPerSecond.isFinite() ||
                    gnssSpeedAccuracyMetersPerSecond !in 0.0..2.5))
        ) return setStatus(Obd2CalibrationStatus.LOW_GNSS_QUALITY)

        if (obdQueryDurationMillis !in 0L..MAX_OBD_QUERY_DURATION_MS) {
            return setStatus(Obd2CalibrationStatus.OBD_QUERY_TOO_SLOW)
        }
        if (obdSpeedKmh !in MIN_CALIBRATION_SPEED_KMH.toInt()..MAX_CALIBRATION_SPEED_KMH.toInt()) {
            return setStatus(Obd2CalibrationStatus.WAITING_FOR_MOVEMENT)
        }

        val gpsAge = nowElapsedRealtimeNanos - gnssElapsedRealtimeNanos
        val obdAge = nowElapsedRealtimeNanos - obdTimestamp
        if (gpsAge !in 0L..MAX_SAMPLE_AGE_NANOS || obdAge !in 0L..MAX_SAMPLE_AGE_NANOS ||
            absoluteDifference(gnssElapsedRealtimeNanos, obdTimestamp) > MAX_PAIR_SKEW_NANOS
        ) return setStatus(Obd2CalibrationStatus.TIME_MISMATCH)
        if (gpsSpeed !in MIN_CALIBRATION_SPEED_KMH..MAX_CALIBRATION_SPEED_KMH) {
            return setStatus(Obd2CalibrationStatus.WAITING_FOR_MOVEMENT)
        }

        val difference = gpsSpeed - obdSpeedKmh
        val ratio = gpsSpeed / obdSpeedKmh.toDouble()
        val maxAllowedDifference = max(
            MIN_ABSOLUTE_DIFFERENCE_KMH,
            max(gpsSpeed, obdSpeedKmh.toDouble()) * MAX_RELATIVE_DIFFERENCE
        )
        if (ratio !in MIN_CORRECTION_SCALE..MAX_CORRECTION_SCALE ||
            abs(difference) > maxAllowedDifference
        ) {
            snapshot = snapshot.copy(
                status = Obd2CalibrationStatus.SPEEDS_DIVERGE,
                differenceKmh = difference,
                candidateScale = median(samples.map { it.scale }),
                activeCorrectionScale = activeCorrectionScale
            )
            return snapshot
        }

        val lastSample = samples.lastOrNull()
        if (lastSample != null && gnssElapsedRealtimeNanos <= lastSample.timestampNanos) {
            return setStatus(Obd2CalibrationStatus.TIME_MISMATCH)
        }
        samples += Sample(gnssElapsedRealtimeNanos, gpsSpeed, ratio)
        while (samples.size > MAX_SAMPLE_WINDOW) samples.removeAt(0)

        val candidate = median(samples.map { it.scale })
        val stableCandidate = stableCandidateScale()
        if (stableCandidate != null) {
            activeCorrectionScale = if (activeCorrectionScale == null) stableCandidate else {
                (activeCorrectionScale!! * SCALE_SMOOTHING_OLD_WEIGHT +
                    stableCandidate * (1.0 - SCALE_SMOOTHING_OLD_WEIGHT))
                    .coerceIn(MIN_CORRECTION_SCALE, MAX_CORRECTION_SCALE)
            }
        }
        snapshot = Obd2SpeedCalibrationSnapshot(
            status = if (activeCorrectionScale != null) Obd2CalibrationStatus.CALIBRATED
                else Obd2CalibrationStatus.COLLECTING,
            acceptedPairs = samples.size,
            differenceKmh = difference,
            candidateScale = candidate,
            activeCorrectionScale = activeCorrectionScale
        )
        return snapshot
    }

    private fun setStatus(status: Obd2CalibrationStatus): Obd2SpeedCalibrationSnapshot {
        snapshot = snapshot.copy(status = status, differenceKmh = null, activeCorrectionScale = activeCorrectionScale)
        return snapshot
    }

    private fun stableCandidateScale(): Double? {
        if (samples.size < MIN_CALIBRATION_SAMPLES) return null
        if (samples.last().timestampNanos - samples.first().timestampNanos < MIN_CALIBRATION_SPAN_NANOS) return null
        val speeds = samples.map { it.gnssSpeedKmh }
        if ((speeds.maxOrNull() ?: 0.0) - (speeds.minOrNull() ?: 0.0) < MIN_SPEED_SPREAD_KMH) return null
        val ratios = samples.map { it.scale }
        val center = median(ratios) ?: return null
        if (center !in MIN_CORRECTION_SCALE..MAX_CORRECTION_SCALE || center <= 0.0) return null
        val mad = median(ratios.map { abs(it - center) }) ?: return null
        if (mad / center > MAX_RELATIVE_MEDIAN_ABSOLUTE_DEVIATION) return null
        return center
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun absoluteDifference(a: Long, b: Long): Long = if (a >= b) a - b else b - a

    companion object {
        const val MIN_CALIBRATION_SAMPLES = 20
        const val MAX_SAMPLE_WINDOW = 120
        const val MIN_CORRECTION_SCALE = 0.88
        const val MAX_CORRECTION_SCALE = 1.12
        private const val MIN_CALIBRATION_SPEED_KMH = 8.0
        private const val MAX_CALIBRATION_SPEED_KMH = 160.0
        private const val MIN_ABSOLUTE_DIFFERENCE_KMH = 4.0
        private const val MAX_RELATIVE_DIFFERENCE = 0.12
        private const val MAX_RELATIVE_MEDIAN_ABSOLUTE_DEVIATION = 0.04
        private const val MIN_CALIBRATION_SPAN_NANOS = 30_000_000_000L
        private const val MIN_SPEED_SPREAD_KMH = 15.0
        private const val MAX_PAIR_SKEW_NANOS = 2_500_000_000L
        private const val MAX_SAMPLE_AGE_NANOS = 5_000_000_000L
        private const val MAX_OBD_QUERY_DURATION_MS = 1_500L
        private const val SCALE_SMOOTHING_OLD_WEIGHT = 0.9
    }
}
