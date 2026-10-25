package org.cleargps.core

import kotlin.math.sqrt

/** A summary of one numeric baseline feature; spread is null until there are at least two samples. */
data class GnssMetricBaseline(
    val sampleCount: Int,
    val mean: Double?,
    val standardDeviation: Double?
)

/**
 * Aggregate, coordinate-free baseline for the current device's GNSS reception.
 *
 * It does not classify spoofing and does not set detector thresholds. The caller may contribute
 * data only when the position assessment is normal and outside a configured risk zone.
 */
data class GnssDeviceBaselineSnapshot(
    val acceptedSignalSnapshots: Int,
    val acceptedAgcSnapshots: Int,
    val firstSampleElapsedRealtimeNanos: Long?,
    val lastSampleElapsedRealtimeNanos: Long?,
    val meanCn0DbHz: GnssMetricBaseline,
    val cn0SpreadDb: GnssMetricBaseline,
    val visibleSatellites: GnssMetricBaseline,
    val usedSatellites: GnssMetricBaseline,
    val meanAgcDb: GnssMetricBaseline
)

internal class GnssDeviceBaseline {
    private val cn0 = RunningMetric()
    private val cn0Spread = RunningMetric()
    private val visibleSatellites = RunningMetric()
    private val usedSatellites = RunningMetric()
    private val agc = RunningMetric()

    private var lastSignalAtNanos = 0L
    private var lastAgcAtNanos = 0L
    private var acceptedSignalSnapshots = 0
    private var acceptedAgcSnapshots = 0
    private var firstSampleAtNanos = 0L
    private var lastSampleAtNanos = 0L

    /**
     * Returns true only when a new eligible feature sample was accepted.
     * Signal loss, missing AGC, stale/future timestamps, duplicates, and out-of-order data do not
     * become synthetic zeros in the baseline.
     */
    fun observe(
        signal: GnssSignalSnapshot?,
        rf: GnssRfSnapshot?,
        nowElapsedRealtimeNanos: Long,
        isNormal: Boolean,
        inRiskZone: Boolean
    ): Boolean {
        if (!isNormal || inRiskZone || nowElapsedRealtimeNanos <= 0L) return false
        var changed = false

        if (signal != null &&
            signal.elapsedRealtimeNanos > lastSignalAtNanos &&
            isFresh(signal.elapsedRealtimeNanos, nowElapsedRealtimeNanos) &&
            signal.satellitesVisible in 1..MAX_SATELLITES &&
            signal.satellitesUsedInFix in 0..signal.satellitesVisible
        ) {
            lastSignalAtNanos = signal.elapsedRealtimeNanos
            acceptedSignalSnapshots++
            visibleSatellites.add(signal.satellitesVisible.toDouble())
            usedSatellites.add(signal.satellitesUsedInFix.toDouble())
            signal.meanCn0DbHz?.takeIf { it.isFinite() && it in 0.0..MAX_C_N0_DB_HZ }
                ?.let(cn0::add)
            signal.cn0SpreadDb?.takeIf { it.isFinite() && it in 0.0..MAX_C_N0_SPREAD_DB }
                ?.let(cn0Spread::add)
            recordAcceptedAt(signal.elapsedRealtimeNanos)
            changed = true
        }

        if (rf != null &&
            rf.elapsedRealtimeNanos > lastAgcAtNanos &&
            isFresh(rf.elapsedRealtimeNanos, nowElapsedRealtimeNanos)
        ) {
            lastAgcAtNanos = rf.elapsedRealtimeNanos
            val validAgc = rf.meanAgcDb?.takeIf { it.isFinite() }
            if (rf.agcSampleCount > 0 && validAgc != null) {
                acceptedAgcSnapshots++
                agc.add(validAgc)
                recordAcceptedAt(rf.elapsedRealtimeNanos)
                changed = true
            }
        }
        return changed
    }

    /**
     * Restores aggregate statistics from private app storage.
     * Monotonic timestamps are intentionally reset because elapsedRealtime resets after reboot.
     * Invalid or inconsistent state is rejected without partially mutating this baseline.
     */
    fun restore(snapshot: GnssDeviceBaselineSnapshot): Boolean {
        if (snapshot.acceptedSignalSnapshots < 0 || snapshot.acceptedAgcSnapshots < 0) return false
        if (snapshot.visibleSatellites.sampleCount != snapshot.acceptedSignalSnapshots ||
            snapshot.usedSatellites.sampleCount != snapshot.acceptedSignalSnapshots ||
            snapshot.meanAgcDb.sampleCount != snapshot.acceptedAgcSnapshots ||
            snapshot.meanCn0DbHz.sampleCount > snapshot.acceptedSignalSnapshots ||
            snapshot.cn0SpreadDb.sampleCount > snapshot.acceptedSignalSnapshots
        ) return false
        if (!validMetric(snapshot.meanCn0DbHz, 60.0, 60.0) ||
            !validMetric(snapshot.cn0SpreadDb, 60.0, 60.0) ||
            !validMetric(snapshot.visibleSatellites, MAX_SATELLITES.toDouble(), MAX_SATELLITES.toDouble()) ||
            !validMetric(snapshot.usedSatellites, MAX_SATELLITES.toDouble(), MAX_SATELLITES.toDouble()) ||
            !validMetric(snapshot.meanAgcDb, null, null)
        ) return false

        cn0.restore(snapshot.meanCn0DbHz)
        cn0Spread.restore(snapshot.cn0SpreadDb)
        visibleSatellites.restore(snapshot.visibleSatellites)
        usedSatellites.restore(snapshot.usedSatellites)
        agc.restore(snapshot.meanAgcDb)

        acceptedSignalSnapshots = snapshot.acceptedSignalSnapshots
        acceptedAgcSnapshots = snapshot.acceptedAgcSnapshots
        // Stored elapsedRealtime values are not portable across process/device restarts.
        lastSignalAtNanos = 0L
        lastAgcAtNanos = 0L
        firstSampleAtNanos = 0L
        lastSampleAtNanos = 0L
        return true
    }

    private fun validMetric(
        metric: GnssMetricBaseline,
        maxMean: Double?,
        maxStandardDeviation: Double?
    ): Boolean {
        if (metric.sampleCount < 0) return false
        if (metric.sampleCount == 0) return metric.mean == null && metric.standardDeviation == null
        val mean = metric.mean ?: return false
        if (!mean.isFinite() || (maxMean != null && mean !in 0.0..maxMean)) return false
        if (metric.sampleCount == 1) return metric.standardDeviation == null
        val standardDeviation = metric.standardDeviation ?: return false
        if (!standardDeviation.isFinite() || standardDeviation < 0.0) return false
        if (maxStandardDeviation != null && standardDeviation > maxStandardDeviation) return false
        val reconstructedM2 = standardDeviation * standardDeviation * (metric.sampleCount - 1L)
        return reconstructedM2.isFinite()
    }

    fun snapshot(): GnssDeviceBaselineSnapshot = GnssDeviceBaselineSnapshot(
        acceptedSignalSnapshots = acceptedSignalSnapshots,
        acceptedAgcSnapshots = acceptedAgcSnapshots,
        firstSampleElapsedRealtimeNanos = firstSampleAtNanos.takeIf { it > 0L },
        lastSampleElapsedRealtimeNanos = lastSampleAtNanos.takeIf { it > 0L },
        meanCn0DbHz = cn0.snapshot(),
        cn0SpreadDb = cn0Spread.snapshot(),
        visibleSatellites = visibleSatellites.snapshot(),
        usedSatellites = usedSatellites.snapshot(),
        meanAgcDb = agc.snapshot()
    )

    private fun recordAcceptedAt(atNanos: Long) {
        if (firstSampleAtNanos == 0L || atNanos < firstSampleAtNanos) firstSampleAtNanos = atNanos
        if (atNanos > lastSampleAtNanos) lastSampleAtNanos = atNanos
    }

    private fun isFresh(sampleAtNanos: Long, nowElapsedRealtimeNanos: Long): Boolean =
        sampleAtNanos > 0L && sampleAtNanos <= nowElapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - sampleAtNanos <= MAX_SAMPLE_AGE_NANOS

    private class RunningMetric {
        private var count = 0L
        private var mean = 0.0
        private var sumSquaredDeviation = 0.0

        fun add(value: Double) {
            if (!value.isFinite()) return
            count++
            val delta = value - mean
            mean += delta / count
            sumSquaredDeviation += delta * (value - mean)
        }

        fun restore(snapshot: GnssMetricBaseline) {
            count = snapshot.sampleCount.toLong()
            mean = snapshot.mean ?: 0.0
            sumSquaredDeviation = if (count >= 2L) {
                val standardDeviation = snapshot.standardDeviation ?: 0.0
                standardDeviation * standardDeviation * (count - 1L)
            } else 0.0
        }

        fun snapshot(): GnssMetricBaseline = GnssMetricBaseline(
            sampleCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            mean = mean.takeIf { count > 0L },
            standardDeviation = if (count >= 2L) {
                sqrt((sumSquaredDeviation / (count - 1L)).coerceAtLeast(0.0))
            } else null
        )
    }

    companion object {
        private const val MAX_SAMPLE_AGE_NANOS = 10_000_000_000L
        private const val MAX_SATELLITES = 128
        private const val MAX_C_N0_DB_HZ = 60.0
        private const val MAX_C_N0_SPREAD_DB = 60.0
    }
}
