package org.cleargps.detect

/**
 * Coarse context hint only. This class cannot prove spoofing, jamming, a tunnel or an indoor
 * location. Its result must never reduce the risk of independent spoofing evidence by itself.
 */
data class EnvironmentSnapshot(
    val visibleSatellites: Int? = null,
    val usedSatellites: Int? = null,
    val meanCn0DbHz: Double? = null,
    val radioObservationCount: Int? = null
)
enum class EnvironmentClass { OPEN_SKY_LIKELY, OBSTRUCTED_OR_MULTIPATH_POSSIBLE, UNKNOWN }
enum class EnvironmentReason {
    CONSISTENT_MULTI_SATELLITE_SIGNAL, WEAK_GNSS_WITH_DENSE_RADIO,
    LOW_GNSS_CAUSE_AMBIGUOUS, MIXED_OR_INSUFFICIENT_METRICS, INVALID_METRICS, NO_METRICS
}
data class EnvironmentAssessment(
    val environment: EnvironmentClass,
    val confidence: Double,
    val reason: EnvironmentReason
)

class EnvironmentClassifier(private val denseRadioThreshold: Int = 8) {
    init { require(denseRadioThreshold >= 1) }

    fun classify(snapshot: EnvironmentSnapshot): EnvironmentAssessment {
        if (snapshot.visibleSatellites?.let { it !in 0..200 } == true ||
            snapshot.usedSatellites?.let { it !in 0..200 } == true ||
            snapshot.meanCn0DbHz?.let { !it.isFinite() || it !in 0.0..70.0 } == true ||
            snapshot.radioObservationCount?.let { it !in 0..10_000 } == true
        ) return unknown(0.0, EnvironmentReason.INVALID_METRICS)

        val hasMetrics = snapshot.visibleSatellites != null ||
            snapshot.usedSatellites != null || snapshot.meanCn0DbHz != null ||
            snapshot.radioObservationCount != null
        if (!hasMetrics) return unknown(0.0, EnvironmentReason.NO_METRICS)

        val strongGnss = (snapshot.visibleSatellites ?: 0) >= 8 &&
            (snapshot.usedSatellites ?: 0) >= 5 && (snapshot.meanCn0DbHz ?: 0.0) >= 27.0
        if (strongGnss) {
            return EnvironmentAssessment(
                EnvironmentClass.OPEN_SKY_LIKELY, 0.70,
                EnvironmentReason.CONSISTENT_MULTI_SATELLITE_SIGNAL
            )
        }

        val weakGnss = snapshot.visibleSatellites?.let { it <= 4 } == true ||
            snapshot.usedSatellites?.let { it <= 2 } == true ||
            snapshot.meanCn0DbHz?.let { it <= 20.0 } == true
        if (weakGnss && (snapshot.radioObservationCount ?: 0) >= denseRadioThreshold) {
            return EnvironmentAssessment(
                EnvironmentClass.OBSTRUCTED_OR_MULTIPATH_POSSIBLE, 0.55,
                EnvironmentReason.WEAK_GNSS_WITH_DENSE_RADIO
            )
        }
        if (weakGnss) return unknown(0.25, EnvironmentReason.LOW_GNSS_CAUSE_AMBIGUOUS)
        return unknown(0.20, EnvironmentReason.MIXED_OR_INSUFFICIENT_METRICS)
    }

    private fun unknown(confidence: Double, reason: EnvironmentReason) =
        EnvironmentAssessment(EnvironmentClass.UNKNOWN, confidence, reason)
}
