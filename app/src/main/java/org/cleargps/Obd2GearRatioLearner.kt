package org.cleargps

import kotlin.math.abs

internal enum class Obd2GearLearningStatus {
    WAITING_FOR_DATA,
    WAITING_FOR_TRUSTED_SPEED_PAIR,
    TIME_MISMATCH,
    WAITING_FOR_STEADY_DRIVING,
    LEARNING,
    RATIOS_LEARNED
}

internal data class Obd2GearRatioBand(
    /** Approximate engine RPM per km/h; it is not an actual gearbox gear number. */
    val medianRpmPerKmh: Double,
    val samples: Int
)

internal data class Obd2GearLearningSnapshot(
    val status: Obd2GearLearningStatus = Obd2GearLearningStatus.WAITING_FOR_DATA,
    val bands: List<Obd2GearRatioBand> = emptyList(),
    val currentRatioRpmPerKmh: Double? = null,
    val pendingSamples: Int = 0
)

/**
 * Learns repeatable RPM/speed ratio bands only from temporally aligned OBD samples that have
 * already passed the trusted GNSS↔OBD speed comparison. Bands are transient and descriptive:
 * without wheel circumference and final-drive data, they must not be presented as true gear IDs.
 */
internal class Obd2GearRatioLearner {
    private data class Sample(val timestampNanos: Long, val ratio: Double)
    private data class MutableBand(val ratios: MutableList<Double>)

    private val bands = mutableListOf<MutableBand>()
    private val pending = mutableListOf<Sample>()
    private var lastProcessedPairTimestampNanos: Long? = null
    private var snapshot = Obd2GearLearningSnapshot()

    fun reset() {
        bands.clear()
        pending.clear()
        lastProcessedPairTimestampNanos = null
        snapshot = Obd2GearLearningSnapshot()
    }

    internal fun currentSnapshot(): Obd2GearLearningSnapshot = snapshot

    fun observe(
        engineRpm: Int?,
        vehicleSpeedKmh: Int?,
        engineLoadPercent: Int?,
        throttlePercent: Int?,
        engineRpmSampleElapsedRealtimeNanos: Long?,
        engineRpmQueryDurationMillis: Long?,
        speedSampleElapsedRealtimeNanos: Long?,
        speedQueryDurationMillis: Long?,
        trustedSpeedPair: Boolean,
        nowElapsedRealtimeNanos: Long
    ): Obd2GearLearningSnapshot {
        val rpmAt = engineRpmSampleElapsedRealtimeNanos
        val speedAt = speedSampleElapsedRealtimeNanos
        if (engineRpm == null || vehicleSpeedKmh == null || rpmAt == null || speedAt == null ||
            engineRpmQueryDurationMillis == null || speedQueryDurationMillis == null
        ) {
            pending.clear()
            return setStatus(Obd2GearLearningStatus.WAITING_FOR_DATA)
        }

        // Validate the monotonic clock window before advancing the deduplication cursor.
        // A malformed/future timestamp must not make later valid samples look out of order.
        if (nowElapsedRealtimeNanos <= 0L ||
            rpmAt <= 0L || speedAt <= 0L ||
            rpmAt > nowElapsedRealtimeNanos || speedAt > nowElapsedRealtimeNanos
        ) {
            pending.clear()
            return setStatus(Obd2GearLearningStatus.TIME_MISMATCH)
        }
        val rpmAge = nowElapsedRealtimeNanos - rpmAt
        val speedAge = nowElapsedRealtimeNanos - speedAt
        val querySkew = absoluteDifference(rpmAt, speedAt)
        if (engineRpmQueryDurationMillis !in 0L..MAX_PID_QUERY_DURATION_MS ||
            speedQueryDurationMillis !in 0L..MAX_PID_QUERY_DURATION_MS ||
            rpmAge !in 0L..MAX_SAMPLE_AGE_NANOS ||
            speedAge !in 0L..MAX_SAMPLE_AGE_NANOS ||
            querySkew > MAX_PID_SKEW_NANOS
        ) {
            pending.clear()
            return setStatus(Obd2GearLearningStatus.TIME_MISMATCH)
        }

        val pairTimestamp = rpmAt + (speedAt - rpmAt) / 2L
        val lastTimestamp = lastProcessedPairTimestampNanos
        if (lastTimestamp != null && pairTimestamp <= lastTimestamp) return snapshot
        // Temporally valid samples are consumed even when the GPS pair is not trusted, so the
        // same OBD response cannot be replayed later under a different trust state.
        lastProcessedPairTimestampNanos = pairTimestamp

        if (!trustedSpeedPair) {
            pending.clear()
            return setStatus(Obd2GearLearningStatus.WAITING_FOR_TRUSTED_SPEED_PAIR)
        }

        if (vehicleSpeedKmh !in MIN_SPEED_KMH..MAX_SPEED_KMH ||
            engineRpm !in MIN_RPM..MAX_RPM ||
            (engineLoadPercent != null && engineLoadPercent > MAX_ENGINE_LOAD_PERCENT) ||
            (throttlePercent != null && throttlePercent > MAX_THROTTLE_PERCENT)
        ) {
            pending.clear()
            return setStatus(Obd2GearLearningStatus.WAITING_FOR_STEADY_DRIVING)
        }

        val ratio = engineRpm.toDouble() / vehicleSpeedKmh.toDouble()
        if (!ratio.isFinite() || ratio !in MIN_RATIO..MAX_RATIO) {
            pending.clear()
            return setStatus(Obd2GearLearningStatus.WAITING_FOR_STEADY_DRIVING)
        }

        val matchingBand = bands
            .mapNotNull { band -> median(band.ratios)?.let { band to it } }
            .filter { (_, center) -> relativeDifference(ratio, center) <= BAND_MATCH_TOLERANCE }
            .minByOrNull { (_, center) -> abs(ratio - center) }

        if (matchingBand != null) {
            val values = matchingBand.first.ratios
            values += ratio
            while (values.size > MAX_VALUES_PER_BAND) values.removeAt(0)
            pending.clear()
            return updateSnapshot(ratio)
        }

        val pendingCenter = median(pending.map { it.ratio })
        if (pendingCenter == null || relativeDifference(ratio, pendingCenter) > PENDING_STABILITY_TOLERANCE) {
            pending.clear()
        }
        pending += Sample(pairTimestamp, ratio)
        while (pending.size > MAX_PENDING_SAMPLES) pending.removeAt(0)

        val pendingSpanNanos = pending.last().timestampNanos - pending.first().timestampNanos
        if (pending.size >= MIN_BAND_SAMPLES && pendingSpanNanos >= MIN_BAND_SPAN_NANOS) {
            val confirmedCenter = median(pending.map { it.ratio })
            if (confirmedCenter != null &&
                bands.none { band ->
                    val center = median(band.ratios) ?: return@none false
                    relativeDifference(confirmedCenter, center) <= BAND_MATCH_TOLERANCE
                }
            ) {
                bands += MutableBand(pending.map { it.ratio }.toMutableList())
                while (bands.size > MAX_GEAR_BANDS) {
                    val leastObserved = bands.indices.minByOrNull { bands[it].ratios.size } ?: 0
                    bands.removeAt(leastObserved)
                }
            }
            pending.clear()
        }

        return updateSnapshot(ratio)
    }

    private fun updateSnapshot(currentRatio: Double?): Obd2GearLearningSnapshot {
        val publishedBands = bands.mapNotNull { band ->
            median(band.ratios)?.let { Obd2GearRatioBand(it, band.ratios.size) }
        }.sortedByDescending { it.medianRpmPerKmh }
        snapshot = Obd2GearLearningSnapshot(
            status = if (publishedBands.size >= 2) Obd2GearLearningStatus.RATIOS_LEARNED
                else Obd2GearLearningStatus.LEARNING,
            bands = publishedBands,
            currentRatioRpmPerKmh = currentRatio,
            pendingSamples = pending.size
        )
        return snapshot
    }

    private fun setStatus(status: Obd2GearLearningStatus): Obd2GearLearningSnapshot {
        val publishedBands = bands.mapNotNull { band ->
            median(band.ratios)?.let { Obd2GearRatioBand(it, band.ratios.size) }
        }.sortedByDescending { it.medianRpmPerKmh }
        snapshot = snapshot.copy(status = status, bands = publishedBands, currentRatioRpmPerKmh = null, pendingSamples = pending.size)
        return snapshot
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun relativeDifference(value: Double, center: Double): Double =
        if (center == 0.0) Double.POSITIVE_INFINITY else abs(value - center) / abs(center)

    private fun absoluteDifference(a: Long, b: Long): Long = if (a >= b) a - b else b - a

    companion object {
        const val MIN_BAND_SAMPLES = 5
        const val MAX_GEAR_BANDS = 8
        const val MAX_VALUES_PER_BAND = 60
        private const val MAX_PENDING_SAMPLES = 20
        private const val MIN_BAND_SPAN_NANOS = 6_000_000_000L
        private const val MAX_PID_QUERY_DURATION_MS = 1_500L
        private const val MAX_SAMPLE_AGE_NANOS = 5_000_000_000L
        private const val MAX_PID_SKEW_NANOS = 1_200_000_000L
        private const val MIN_SPEED_KMH = 15
        private const val MAX_SPEED_KMH = 160
        private const val MIN_RPM = 900
        private const val MAX_RPM = 6_500
        private const val MAX_ENGINE_LOAD_PERCENT = 85
        private const val MAX_THROTTLE_PERCENT = 75
        private const val MIN_RATIO = 5.0
        private const val MAX_RATIO = 450.0
        private const val BAND_MATCH_TOLERANCE = 0.06
        private const val PENDING_STABILITY_TOLERANCE = 0.05
    }
}
