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

}
