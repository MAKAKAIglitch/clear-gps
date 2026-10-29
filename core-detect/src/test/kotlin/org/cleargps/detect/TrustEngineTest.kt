package org.cleargps.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustEngineTest {
    private val second = 1_000_000_000L

    private fun signal(
        domain: EvidenceDomain,
        code: SignalCode = SignalCode.POSITION_DISAGREEMENT,
        strength: Double = 1.0,
        confidence: Double = 1.0,
        at: Long,
        polarity: EvidencePolarity = EvidencePolarity.INCREASE_RISK
    ) = SpoofSignal(code, domain, strength, confidence, at, polarity)

    private fun engine() = TrustEngine(
        TrustEngineConfig(smoothingTimeConstantNanos = second, minimumStateHoldNanos = 0L)
    )

    @Test fun twoIndependentHighRiskAssessmentsEnterSpoofed() {
        val engine = engine()
        val evidence = listOf(
            signal(EvidenceDomain.GNSS_RF, SignalCode.CN0_AGC_ANOMALY, at = second),
            signal(EvidenceDomain.GNSS_TIME, SignalCode.GNSS_CLOCK_DISCONTINUITY, at = second),
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = second)
        )
        val first = engine.evaluate(second, evidence)
        assertEquals(TrustState.SUSPECT, first.state)
        assertTrue(first.riskIndex >= 70)
        assertFalse(first.riskIsCalibrated)
        val secondDecision = engine.evaluate(second * 2, evidence.map {
            it.copy(observedAtElapsedRealtimeNanos = second * 2)
        })
        assertEquals(TrustState.SPOOFED, secondDecision.state)
    }

    @Test fun duplicateSignalsInOneDomainAreCapped() {
        val engine = engine()
        val one = signal(EvidenceDomain.GNSS_RF, SignalCode.CN0_AGC_ANOMALY,
            strength = 0.8, at = second)
        val single = engine.evaluate(second, listOf(one))
        engine.reset()
        val repeated = engine.evaluate(second, listOf(
            one, one.copy(code = SignalCode.CN0_CORRELATION_ANOMALY),
            one.copy(code = SignalCode.FREQUENCY_INCONSISTENCY)
        ))
        assertEquals(single.rawRiskIndex, repeated.rawRiskIndex)
        assertEquals(19, repeated.rawRiskIndex)
    }

    @Test fun staleFutureAndMalformedEvidenceAreIgnored() {
        val engine = engine()
        val now = 100L * second
        val signals = listOf(
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = second),
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = now + second),
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, strength = Double.NaN, at = now),
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, confidence = 0.0, at = now)
        )
        val result = engine.evaluate(now, signals)
        assertEquals(0, result.rawRiskIndex)
        assertEquals(TrustState.NORMAL, result.state)
    }

    @Test fun unavailableInputsDegradeWithoutClaimingZeroRisk() {
        val engine = engine()
        val evidence = listOf(
            signal(EvidenceDomain.GNSS_RF, SignalCode.CN0_AGC_ANOMALY, at = second),
            signal(EvidenceDomain.GNSS_TIME, SignalCode.GNSS_CLOCK_DISCONTINUITY, at = second),
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = second)
        )
        val before = engine.evaluate(second, evidence)
        val degraded = engine.evaluate(second * 2, emptyList(), InputAvailability.UNAVAILABLE)
        assertEquals(TrustState.DEGRADED, degraded.state)
        assertEquals(before.riskIndex, degraded.riskIndex)
        assertEquals(listOf(SignalCode.INSUFFICIENT_INPUTS), degraded.reasonCodes)
    }

    @Test fun jammingUsesASeparateState() {
        val result = engine().evaluate(second, listOf(signal(
            EvidenceDomain.JAMMING, SignalCode.JAMMING_PATTERN, strength = 0.9,
            confidence = 0.9, at = second
        )))
        assertEquals(TrustState.JAMMING, result.state)
        assertTrue(result.reasonCodes.contains(SignalCode.JAMMING_PATTERN))
    }

    @Test fun cleanEvidenceNeedsMultipleCyclesToClearSpoofedState() {
        val engine = engine()
        val high = listOf(
            signal(EvidenceDomain.GNSS_RF, SignalCode.CN0_AGC_ANOMALY, at = second),
            signal(EvidenceDomain.GNSS_TIME, SignalCode.GNSS_CLOCK_DISCONTINUITY, at = second),
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = second)
        )
        engine.evaluate(second, high)
        val spoofed = engine.evaluate(second * 2, high.map {
            it.copy(observedAtElapsedRealtimeNanos = second * 2)
        })
        assertEquals(TrustState.SPOOFED, spoofed.state)
        var result = spoofed
        for (sample in 3L..7L) result = engine.evaluate(second * sample, emptyList())
        assertEquals(TrustState.NORMAL, result.state)
    }

    @Test fun oldAssessmentCannotRollStateBack() {
        val engine = engine()
        val later = engine.evaluate(second * 2, listOf(
            signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = second * 2)
        ))
        val stale = engine.evaluate(second, listOf(
            signal(EvidenceDomain.GNSS_RF, SignalCode.CN0_AGC_ANOMALY, at = second)
        ))
        assertEquals(later, stale)
        assertEquals(second * 2, stale.evaluatedAtElapsedRealtimeNanos)
    }

    @Test fun resetClearsStateAndScore() {
        val engine = engine()
        engine.evaluate(second, listOf(signal(EvidenceDomain.LOCATION_CROSS_CHECK, at = second)))
        engine.reset()
        val clean = engine.evaluate(second, emptyList())
        assertEquals(TrustState.NORMAL, clean.state)
        assertEquals(0, clean.riskIndex)
    }
}
