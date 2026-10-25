package org.cleargps.core

import kotlin.math.abs

/**
 * Reports large deviations from the device's previously accepted normal GNSS baseline.
 *
 * These are diagnostic hints, not spoofing/jamming evidence. This class never changes the
 * detector score or state. A caller must still corroborate hints with independent signals and
 * validate thresholds against labelled receiver logs before using them for decisions.
 */
enum class GnssBaselineFeature {
    MEAN_CN0_DROP,
    VISIBLE_SATELLITES_DROP,
    USED_SATELLITES_DROP,
    MEAN_AGC_CHANGE
}

data class GnssBaselineDeviation(
    val feature: GnssBaselineFeature,
    val observedValue: Double,
    val baselineMean: Double,
    val baselineStandardDeviation: Double,
    val standardizedDeviation: Double,
    val reason: String
)

data class GnssBaselineDeviationAssessment(
    val ready: Boolean,
    val deviations: List<GnssBaselineDeviation> = emptyList()
)

object GnssBaselineDeviationAnalyzer {
    private const val MIN_SIGNAL_SAMPLES = 30
    private const val MIN_AGC_SAMPLES = 30
    private const val MIN_CN0_DROP_DB = 8.0
    private const val MIN_VISIBLE_DROP = 3.0
    private const val MIN_VISIBLE_DROP_RATIO = 0.35
    private const val MIN_USED_DROP = 3.0
    private const val MIN_AGC_CHANGE_DB = 4.0
    private const val MIN_STANDARD_DEVIATIONS = 3.0
    private const val MAX_CN0_DB_HZ = 60.0
    private const val MAX_SATELLITES = 128

    /**
     * Only compares fresh, sane metrics against a sufficiently populated saved baseline.
     * Missing AGC is neutral. A baseline with no measurable spread cannot generate a z-score.
     */
    fun assess(
        signal: GnssSignalSnapshot?,
        rf: GnssRfSnapshot?,
        baseline: GnssDeviceBaselineSnapshot,
        nowElapsedRealtimeNanos: Long
    ): GnssBaselineDeviationAssessment {
        if (nowElapsedRealtimeNanos <= 0L) return GnssBaselineDeviationAssessment(ready = false)
        val signalFresh = signal != null &&
            signal.elapsedRealtimeNanos > 0L &&
            signal.elapsedRealtimeNanos <= nowElapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - signal.elapsedRealtimeNanos <= MAX_SAMPLE_AGE_NANOS &&
            signal.satellitesVisible in 1..MAX_SATELLITES &&
            signal.satellitesUsedInFix in 0..signal.satellitesVisible &&
            signal.constellationCount in 0..MAX_CONSTELLATIONS
        val rfFresh = rf != null &&
            rf.elapsedRealtimeNanos > 0L &&
            rf.elapsedRealtimeNanos <= nowElapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - rf.elapsedRealtimeNanos <= MAX_SAMPLE_AGE_NANOS &&
            rf.agcSampleCount in 0..MAX_SATELLITES &&
            rf.signalCount in 0..MAX_SATELLITES &&
            rf.constellationCount in 0..MAX_CONSTELLATIONS

        val signalReady = baseline.acceptedSignalSnapshots >= MIN_SIGNAL_SAMPLES &&
            baseline.visibleSatellites.sampleCount >= MIN_SIGNAL_SAMPLES &&
            baseline.usedSatellites.sampleCount >= MIN_SIGNAL_SAMPLES &&
            baseline.meanCn0DbHz.sampleCount >= MIN_SIGNAL_SAMPLES
        val agcReady = baseline.acceptedAgcSnapshots >= MIN_AGC_SAMPLES &&
            baseline.meanAgcDb.sampleCount >= MIN_AGC_SAMPLES
        if (!signalReady && !agcReady) return GnssBaselineDeviationAssessment(ready = false)

        val deviations = mutableListOf<GnssBaselineDeviation>()
        if (signalFresh && signalReady) {
            val current = signal!!
            current.meanCn0DbHz?.takeIf { it.isFinite() && it in 0.0..MAX_CN0_DB_HZ }?.let { observed ->
                deviation(
                    feature = GnssBaselineFeature.MEAN_CN0_DROP,
                    observed = observed,
                    baseline = baseline.meanCn0DbHz,
                    direction = Direction.DROP,
                    absoluteThreshold = MIN_CN0_DROP_DB,
                    reason = "Средний C/N₀ заметно ниже индивидуальной нормы устройства"
                )?.let(deviations::add)
            }
            deviation(
                feature = GnssBaselineFeature.VISIBLE_SATELLITES_DROP,
                observed = current.satellitesVisible.toDouble(),
                baseline = baseline.visibleSatellites,
                direction = Direction.DROP,
                absoluteThreshold = MIN_VISIBLE_DROP,
                reason = "Видимых спутников заметно меньше индивидуальной нормы устройства"
            )?.takeIf {
                it.baselineMean > 0.0 &&
                    (it.baselineMean - it.observedValue) / it.baselineMean >= MIN_VISIBLE_DROP_RATIO
            }?.let(deviations::add)
            deviation(
                feature = GnssBaselineFeature.USED_SATELLITES_DROP,
                observed = current.satellitesUsedInFix.toDouble(),
                baseline = baseline.usedSatellites,
                direction = Direction.DROP,
                absoluteThreshold = MIN_USED_DROP,
                reason = "Спутников в фиксе заметно меньше индивидуальной нормы устройства"
            )?.let(deviations::add)
        }
        if (rfFresh && agcReady) {
            val observed = rf!!.meanAgcDb
            if (rf.agcSampleCount > 0 && observed != null && observed.isFinite()) {
                deviation(
                    feature = GnssBaselineFeature.MEAN_AGC_CHANGE,
                    observed = observed,
                    baseline = baseline.meanAgcDb,
                    direction = Direction.ANY,
                    absoluteThreshold = MIN_AGC_CHANGE_DB,
                    reason = "Средний AGC заметно отличается от индивидуальной нормы устройства"
                )?.let(deviations::add)
            }
        }
        return GnssBaselineDeviationAssessment(ready = signalReady || agcReady, deviations = deviations)
    }

    private enum class Direction { DROP, ANY }

    private fun deviation(
        feature: GnssBaselineFeature,
        observed: Double,
        baseline: GnssMetricBaseline,
        direction: Direction,
        absoluteThreshold: Double,
        reason: String
    ): GnssBaselineDeviation? {
        val mean = baseline.mean ?: return null
        val sd = baseline.standardDeviation ?: return null
        if (!mean.isFinite() || !sd.isFinite() || sd <= 0.0 || !observed.isFinite()) return null
        val delta = mean - observed
        if (direction == Direction.DROP && delta <= 0.0) return null
        val magnitude = abs(observed - mean)
        if (magnitude < absoluteThreshold) return null
        val z = magnitude / sd
        if (!z.isFinite() || z < MIN_STANDARD_DEVIATIONS) return null
        return GnssBaselineDeviation(
            feature = feature,
            observedValue = observed,
            baselineMean = mean,
            baselineStandardDeviation = sd,
            standardizedDeviation = z,
            reason = reason
        )
    }

    private const val MAX_SAMPLE_AGE_NANOS = 10_000_000_000L
    private const val MAX_CONSTELLATIONS = 8
}
