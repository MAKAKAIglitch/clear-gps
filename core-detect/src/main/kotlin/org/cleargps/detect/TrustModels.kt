package org.cleargps.detect

enum class EvidenceDomain {
    GNSS_RF, GNSS_TIME, LOCATION_CROSS_CHECK, MOTION, RADIO, CELLULAR_CONTEXT, RISK_ZONE, JAMMING
}
enum class EvidencePolarity { INCREASE_RISK, REDUCE_RISK }
enum class SignalCode {
    POSITION_DISAGREEMENT, IMPOSSIBLE_DISPLACEMENT, GNSS_CLOCK_DISCONTINUITY,
    CN0_AGC_ANOMALY, CN0_CORRELATION_ANOMALY, FREQUENCY_INCONSISTENCY,
    SATELLITE_GEOMETRY_ANOMALY, ODOMETER_DISAGREEMENT, IMU_GNSS_DISAGREEMENT,
    RADIO_GNSS_DISAGREEMENT, CELLULAR_CONTEXT_MISMATCH, RISK_ZONE, JAMMING_PATTERN,
    CLEAN_CROSS_CHECK, INSUFFICIENT_INPUTS
}

/**
 * A normalized feature. Strength and confidence are independent values in [0, 1].
 * Timestamps use monotonic elapsed-realtime nanoseconds. Do not place coordinates, MAC addresses,
 * SSIDs, raw GNSS payloads or free-form private data into this model.
 */
data class SpoofSignal(
    val code: SignalCode,
    val domain: EvidenceDomain,
    val strength: Double,
    val confidence: Double,
    val observedAtElapsedRealtimeNanos: Long,
    val polarity: EvidencePolarity = EvidencePolarity.INCREASE_RISK
)
enum class TrustState { NORMAL, SUSPECT, SPOOFED, JAMMING, DEGRADED }
enum class InputAvailability { AVAILABLE, LIMITED, UNAVAILABLE }

/** The 0–100 risk index is uncalibrated engineering output, not a probability. */
data class TrustDecision(
    val state: TrustState,
    val riskIndex: Int,
    val rawRiskIndex: Int,
    val reasonCodes: List<SignalCode>,
    val evaluatedAtElapsedRealtimeNanos: Long,
    val riskIsCalibrated: Boolean = false
)

data class TrustEngineConfig(
    val suspectThreshold: Int = 30,
    val spoofedThreshold: Int = 70,
    val spoofedConfirmationSamples: Int = 2,
    val cleanSamplesToRecover: Int = 3,
    val jammingEvidenceThreshold: Double = 0.75,
    val minimumEvidenceConfidence: Double = 0.15,
    val maximumEvidenceAgeNanos: Long = 15_000_000_000L,
    val smoothingTimeConstantNanos: Long = 2_000_000_000L,
    val minimumStateHoldNanos: Long = 3_000_000_000L
) {
    init {
        require(suspectThreshold in 1..99)
        require(spoofedThreshold in (suspectThreshold + 1)..100)
        require(spoofedConfirmationSamples >= 1)
        require(cleanSamplesToRecover >= 1)
        require(jammingEvidenceThreshold in 0.0..1.0)
        require(minimumEvidenceConfidence in 0.0..1.0)
        require(maximumEvidenceAgeNanos > 0L)
        require(smoothingTimeConstantNanos > 0L)
        require(minimumStateHoldNanos >= 0L)
    }
}
