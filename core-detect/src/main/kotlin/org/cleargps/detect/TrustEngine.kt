package org.cleargps.detect

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Deterministic Android-free state engine. Weights are provisional engineering defaults that must
 * be evaluated on labelled replay logs. A score is not a statistical probability.
 */
class TrustEngine(private val config: TrustEngineConfig = TrustEngineConfig()) {
    private var lastEvaluatedAtNanos = 0L
    private var smoothedRisk = 0.0
    private var state = TrustState.NORMAL
    private var stateSinceNanos = 0L
    private var highRiskStreak = 0
    private var cleanStreak = 0
    private var decision = TrustDecision(
        TrustState.NORMAL, 0, 0, emptyList(), 0L
    )

    /**
     * Non-positive or non-increasing timestamps are ignored. Callers must pass UNAVAILABLE when
     * relevant streams have failed or gone stale; missing sensors must not be submitted as evidence.
     */
    fun evaluate(
        nowElapsedRealtimeNanos: Long,
        signals: List<SpoofSignal>,
        availability: InputAvailability = InputAvailability.AVAILABLE
    ): TrustDecision {
        if (nowElapsedRealtimeNanos <= 0L ||
            (lastEvaluatedAtNanos > 0L && nowElapsedRealtimeNanos <= lastEvaluatedAtNanos)
        ) return decision.copy(reasonCodes = decision.reasonCodes.toList())

        val freshSignals = signals.filter { isUsable(it, nowElapsedRealtimeNanos) }
        if (availability == InputAvailability.UNAVAILABLE) {
            lastEvaluatedAtNanos = nowElapsedRealtimeNanos
            highRiskStreak = 0
            cleanStreak = 0
            transitionTo(TrustState.DEGRADED, nowElapsedRealtimeNanos)
            decision = TrustDecision(
                state = state,
                riskIndex = smoothedRisk.roundToInt().coerceIn(0, 100),
                rawRiskIndex = decision.rawRiskIndex,
                reasonCodes = listOf(SignalCode.INSUFFICIENT_INPUTS),
                evaluatedAtElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
            return decision.copy(reasonCodes = decision.reasonCodes.toList())
        }

        val rawRisk = calculateRiskIndex(freshSignals)
        val elapsed = if (lastEvaluatedAtNanos == 0L) 0L
            else nowElapsedRealtimeNanos - lastEvaluatedAtNanos
        if (lastEvaluatedAtNanos == 0L) {
            smoothedRisk = rawRisk.toDouble()
        } else {
            val alpha = 1.0 - exp(-elapsed.toDouble() / config.smoothingTimeConstantNanos.toDouble())
            smoothedRisk += alpha * (rawRisk.toDouble() - smoothedRisk)
        }
        lastEvaluatedAtNanos = nowElapsedRealtimeNanos

        val risk = smoothedRisk.roundToInt().coerceIn(0, 100)
        val strongJamming = freshSignals.any {
            it.code == SignalCode.JAMMING_PATTERN &&
                it.strength * it.confidence >= config.jammingEvidenceThreshold
        }
        if (risk >= config.spoofedThreshold) highRiskStreak++ else highRiskStreak = 0
        if (risk < config.suspectThreshold && !strongJamming) cleanStreak++ else cleanStreak = 0

        val candidate = when {
            strongJamming -> TrustState.JAMMING
            risk >= config.spoofedThreshold &&
                highRiskStreak >= config.spoofedConfirmationSamples -> TrustState.SPOOFED
            risk >= config.suspectThreshold -> TrustState.SUSPECT
            else -> TrustState.NORMAL
        }
        transitionTo(resolveHysteresis(candidate, risk, nowElapsedRealtimeNanos), nowElapsedRealtimeNanos)

        val reasons = freshSignals.sortedByDescending(::contribution)
            .map { it.code }.distinct().take(6)
        decision = TrustDecision(
            state = state,
            riskIndex = risk,
            rawRiskIndex = rawRisk,
            reasonCodes = reasons,
            evaluatedAtElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
        return decision.copy(reasonCodes = decision.reasonCodes.toList())
    }

    fun reset() {
        lastEvaluatedAtNanos = 0L
        smoothedRisk = 0.0
        state = TrustState.NORMAL
        stateSinceNanos = 0L
        highRiskStreak = 0
        cleanStreak = 0
        decision = TrustDecision(TrustState.NORMAL, 0, 0, emptyList(), 0L)
    }

    private fun resolveHysteresis(candidate: TrustState, risk: Int, nowNanos: Long): TrustState {
        if (candidate == TrustState.DEGRADED || candidate == TrustState.JAMMING) return candidate
        if (state == TrustState.SPOOFED && candidate != TrustState.SPOOFED &&
            cleanStreak < config.cleanSamplesToRecover) return TrustState.SPOOFED
        if (state == TrustState.JAMMING && candidate != TrustState.JAMMING &&
            cleanStreak < config.cleanSamplesToRecover) return TrustState.JAMMING
        if (state == TrustState.SUSPECT && candidate == TrustState.NORMAL &&
            cleanStreak < config.cleanSamplesToRecover) return TrustState.SUSPECT

        if (severity(candidate) < severity(state) &&
            nowNanos - stateSinceNanos < config.minimumStateHoldNanos) return state

        // A high score requires repeated assessments before entering SPOOFED.
        if (risk >= config.spoofedThreshold &&
            highRiskStreak < config.spoofedConfirmationSamples &&
            candidate == TrustState.SPOOFED) return TrustState.SUSPECT
        return candidate
    }

    private fun transitionTo(next: TrustState, nowNanos: Long) {
        if (state != next) {
            state = next
            stateSinceNanos = nowNanos
        }
    }

    private fun severity(value: TrustState): Int = when (value) {
        TrustState.NORMAL, TrustState.DEGRADED -> 0
        TrustState.SUSPECT -> 1
        TrustState.SPOOFED, TrustState.JAMMING -> 2
    }

    private fun isUsable(signal: SpoofSignal, nowNanos: Long): Boolean {
        val age = nowNanos - signal.observedAtElapsedRealtimeNanos
        return signal.observedAtElapsedRealtimeNanos > 0L &&
            age in 0L..config.maximumEvidenceAgeNanos &&
            signal.strength.isFinite() && signal.strength in 0.0..1.0 &&
            signal.confidence.isFinite() && signal.confidence in 0.0..1.0 &&
            signal.confidence >= config.minimumEvidenceConfidence
    }

    private fun calculateRiskIndex(signals: List<SpoofSignal>): Int {
        // Cap each broad domain to limit over-counting of correlated features.
        val byDomain = signals.groupBy { it.domain }
        val score = byDomain.entries.sumOf { (domain, entries) ->
            val weight = DOMAIN_WEIGHTS[domain] ?: 0.0
            val positive = entries.filter { it.polarity == EvidencePolarity.INCREASE_RISK }
                .maxOfOrNull { it.strength * it.confidence * weight } ?: 0.0
            val mitigating = entries.filter { it.polarity == EvidencePolarity.REDUCE_RISK }
                .maxOfOrNull { it.strength * it.confidence * weight } ?: 0.0
            positive - mitigating
        }
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun contribution(signal: SpoofSignal) =
        signal.strength * signal.confidence * (DOMAIN_WEIGHTS[signal.domain] ?: 0.0)

    private companion object {
        // These coefficients are not calibrated against field logs.
        val DOMAIN_WEIGHTS = mapOf(
            EvidenceDomain.GNSS_RF to 24.0,
            EvidenceDomain.GNSS_TIME to 28.0,
            EvidenceDomain.LOCATION_CROSS_CHECK to 34.0,
            EvidenceDomain.MOTION to 22.0,
            EvidenceDomain.RADIO to 25.0,
            EvidenceDomain.CELLULAR_CONTEXT to 8.0,
            EvidenceDomain.RISK_ZONE to 20.0,
            EvidenceDomain.JAMMING to 35.0
        )
    }
}
