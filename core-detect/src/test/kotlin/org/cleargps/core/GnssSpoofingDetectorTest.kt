package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssSpoofingDetectorTest {
    private fun fix(
        lat: Double,
        lon: Double,
        atSeconds: Long,
        accuracy: Double = 10.0,
        provider: String = "gps",
        speed: Double? = null,
        speedAccuracy: Double? = null
    ) = PositionFix(
        provider = provider,
        latitude = lat,
        longitude = lon,
        accuracyMeters = accuracy,
        elapsedRealtimeNanos = atSeconds * 1_000_000_000L,
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = speedAccuracy
    )

    @Test fun plausibleWalkingOrDrivingTrackDoesNotTrigger() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1, speed = 10.0),
            fix(55.0, 37.0, 1, accuracy = 35.0, provider = "network"),
            false, 1_200_000_000L, true)
        val result = detector.assess(fix(55.0, 37.0009, 11, speed = 10.0, speedAccuracy = 2.0),
            fix(55.0, 37.0009, 10, accuracy = 35.0, provider = "network"),
            false, 11_200_000_000L, true)
        assertFalse(result.confirmed)
        assertTrue(result.score < 60)
    }

    @Test fun impossibleJumpAndNetworkDisagreementConfirmRiskImmediately() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1),
            fix(55.0, 37.0, 1, accuracy = 20.0, provider = "network"),
            false, 1_200_000_000L, true)
        val result = detector.assess(fix(56.0, 37.0, 2),
            fix(55.0, 37.0, 1, accuracy = 20.0, provider = "network"),
            false, 2_200_000_000L, true)
        assertTrue(result.confirmed)
        assertTrue(result.score >= 90)
        assertTrue(result.reasons.any { it.contains("скачок координат", ignoreCase = true) })
        assertTrue(result.reasons.any { it.contains("сетевой позицией", ignoreCase = true) })
    }

    @Test fun singleNetworkDisagreementIsSuspiciousButRequiresAnotherFixToConfirm() {
        val detector = GnssSpoofingDetector()
        val first = detector.assess(
            fix(55.0, 37.0, 1),
            fix(55.0, 37.01, 1, accuracy = 20.0, provider = "network"),
            false, 1_200_000_000L, true
        )
        assertFalse(first.confirmed)
        assertTrue(first.score >= 60)

        val second = detector.assess(
            fix(55.0, 37.00001, 6),
            fix(55.0, 37.01, 6, accuracy = 20.0, provider = "network"),
            false, 6_200_000_000L, true
        )
        assertTrue(second.confirmed)
    }

    @Test fun poorAccuracyAloneDoesNotTurnLargeDisplacementIntoCertainSpoofing() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1, accuracy = 500.0),
            fix(55.0, 37.0, 1, accuracy = 500.0, provider = "network"),
            false, 1_200_000_000L, true)
        val result = detector.assess(fix(56.0, 37.0, 2, accuracy = 500.0),
            fix(55.0, 37.0, 2, accuracy = 500.0, provider = "network"),
            false, 2_200_000_000L, true)
        assertFalse(result.confirmed)
        assertTrue(result.score < 60)
    }

    @Test fun satelliteSignalPatternAloneCannotConfirmAnAttack() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(GnssSignalSnapshot(1_100_000_000L, 9, 8, 31.0, 0.6, 3))
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        val result = detector.assess(fix(55.0, 37.00001, 2), null, false, 2_200_000_000L, true)
        assertFalse(result.confirmed)
        assertTrue(result.score < 60)
    }

    @Test fun configuredRiskZoneIsImmediateAndClearRequiresThreeCleanFixes() {
        val detector = GnssSpoofingDetector()
        val first = detector.assess(fix(55.0, 37.0, 1), null, true, 1_200_000_000L, true)
        assertTrue(first.confirmed)
        assertEquals(75, first.score)
        val clean1 = detector.assess(fix(55.0, 37.0, 2), fix(55.0, 37.0, 2, provider = "network"),
            false, 2_200_000_000L, true)
        val clean2 = detector.assess(fix(55.0, 37.0, 3), fix(55.0, 37.0, 3, provider = "network"),
            false, 3_200_000_000L, true)
        val clean3 = detector.assess(fix(55.0, 37.0, 4), fix(55.0, 37.0, 4, provider = "network"),
            false, 4_200_000_000L, true)
        assertTrue(clean1.confirmed)
        assertTrue(clean2.confirmed)
        assertFalse(clean3.confirmed)
    }

    @Test fun abruptSignalChangeIsSupportingEvidenceNotStandaloneProof() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(GnssSignalSnapshot(1_100_000_000L, 8, 7, 30.0, 4.0, 3))
        detector.observeGnssSignal(GnssSignalSnapshot(2_100_000_000L, 8, 7, 46.0, 4.0, 3))
        val result = detector.assess(fix(55.0, 37.0, 2), null, false, 2_200_000_000L, true)
        assertFalse(result.confirmed)
        assertTrue(result.score < 60)
        assertTrue(result.reasons.any { it.contains("уровень сигналов", ignoreCase = true) })
    }

    @Test fun strongSignalsWithVeryFewSatellitesUsedRaiseOnlyAWarning() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(1_100_000_000L, 10, 8, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(2_100_000_000L, 10, 1, 38.5, 5.0, 3, strongSignalCount = 8)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(3_100_000_000L, 10, 1, 38.2, 5.0, 3, strongSignalCount = 8)
        )

        val result = detector.assessSignalOnly(3_200_000_000L)
        assertFalse(result.confirmed)
        assertTrue(result.score >= 30)
        assertTrue(result.reasons.any { it.contains("не используются для фикса", ignoreCase = true) })
    }


    @Test fun underusedStrongSignalsAcrossLongGapDoNotAccumulate() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(1_100_000_000L, 10, 8, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(2_100_000_000L, 10, 1, 38.1, 5.0, 3, strongSignalCount = 8)
        )

        // The next suspicious snapshot comes 28 seconds later, outside the continuity window.
        detector.observeGnssSignal(
            GnssSignalSnapshot(30_100_000_000L, 10, 1, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        val afterGap = detector.assessSignalOnly(30_200_000_000L)
        assertEquals(0, afterGap.score)
        assertFalse(afterGap.confirmed)

        // Two genuinely consecutive suspicious snapshots can still raise a warning.
        detector.observeGnssSignal(
            GnssSignalSnapshot(31_100_000_000L, 10, 1, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        val afterConsecutiveEvidence = detector.assessSignalOnly(31_200_000_000L)
        assertTrue(afterConsecutiveEvidence.score >= 30)
        assertFalse(afterConsecutiveEvidence.confirmed)
    }

    @Test fun repeatedOppositeCn0AndAgcShiftsAreSupportingEvidenceOnly() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssMeasurements(GnssRfSnapshot(1_100_000_000L, 10, 3, 36.0, -62.0, 10))
        detector.observeGnssMeasurements(GnssRfSnapshot(2_100_000_000L, 10, 3, 43.0, -66.0, 10))
        detector.observeGnssMeasurements(GnssRfSnapshot(3_100_000_000L, 10, 3, 50.0, -70.0, 10))

        val result = detector.assessSignalOnly(3_200_000_000L)
        assertFalse(result.confirmed)
        assertTrue(result.score >= 30)
        assertTrue(result.reasons.any { it.contains("C/N₀ и AGC", ignoreCase = true) })
    }

    @Test fun ordinarySmallRfFluctuationsDoNotTriggerAgcMismatch() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssMeasurements(GnssRfSnapshot(1_100_000_000L, 10, 3, 36.0, -62.0, 10))
        detector.observeGnssMeasurements(GnssRfSnapshot(2_100_000_000L, 10, 3, 37.0, -62.5, 10))
        detector.observeGnssMeasurements(GnssRfSnapshot(3_100_000_000L, 10, 3, 37.5, -62.2, 10))

        val result = detector.assessSignalOnly(3_200_000_000L)
        assertFalse(result.confirmed)
        assertEquals(0, result.score)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun reacquisitionAfterLongGnssGapDoesNotTurnDisplacementAloneIntoSpoofing() {
        val detector = GnssSpoofingDetector()
        detector.assess(
            fix(55.0, 37.0, 1),
            fix(55.0, 37.0, 1, accuracy = 35.0, provider = "network"),
            false,
            1_200_000_000L,
            true
        )

        // The fix returns after a five-minute observation gap, as can happen after a tunnel.
        // The previous network fix is stale and must not corroborate the new GPS position.
        val reacquired = detector.assess(
            fix(55.4, 37.0, 301),
            fix(55.0, 37.0, 1, accuracy = 35.0, provider = "network"),
            false,
            301_200_000_000L,
            true
        )

        assertFalse(reacquired.confirmed)
        assertTrue(reacquired.score < 60)
        assertFalse(reacquired.reasons.any { it.contains("скачок координат", ignoreCase = true) })
    }

    @Test fun satelliteLossAloneDoesNotCreateSignalOnlyWarning() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(1_100_000_000L, 9, 8, 34.0, 4.0, 2, strongSignalCount = 6)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(2_100_000_000L, 0, 0, null, null, 0, strongSignalCount = 0)
        )

        val result = detector.assessSignalOnly(2_200_000_000L)
        assertEquals(0, result.score)
        assertFalse(result.confirmed)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun unavailableAgcIsNeutralEvenWhenCn0ChangesSharply() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssMeasurements(
            GnssRfSnapshot(1_100_000_000L, 10, 3, 36.0, null, 0)
        )
        detector.observeGnssMeasurements(
            GnssRfSnapshot(2_100_000_000L, 10, 3, 44.0, null, 0)
        )
        detector.observeGnssMeasurements(
            GnssRfSnapshot(3_100_000_000L, 10, 3, 51.0, null, 0)
        )

        val result = detector.assessSignalOnly(3_200_000_000L)
        assertEquals(0, result.score)
        assertFalse(result.confirmed)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun deviceBaselineIsUpdatedOnlyAfterNormalOutOfZoneGpsAssessment() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(GnssSignalSnapshot(1_100_000_000L, 9, 8, 34.0, 4.0, 2))
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        assertEquals(1, detector.deviceBaselineSnapshot().meanCn0DbHz.sampleCount)

        detector.observeGnssSignal(GnssSignalSnapshot(2_100_000_000L, 9, 8, 35.0, 4.0, 2))
        detector.assess(fix(55.0, 37.0, 2), null, true, 2_200_000_000L, true)
        assertEquals(1, detector.deviceBaselineSnapshot().meanCn0DbHz.sampleCount)
    }

}
