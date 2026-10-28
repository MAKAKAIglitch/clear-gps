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

    private fun obdSpeed(speedKmh: Int, atSeconds: Long, queryDurationMillis: Long = 500L) =
        ObdVehicleSpeedSnapshot(speedKmh, atSeconds * 1_000_000_000L, queryDurationMillis)

    @Test fun singleObdGnssSpeedDisagreementIsWeakAndCannotConfirmSpoofing() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        detector.observeObdVehicleSpeed(obdSpeed(30, 2))
        val result = detector.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = 0.5),
            null, false, 3_200_000_000L, true)
        assertEquals(15, result.breakdown.obdSpeedScore)
        assertTrue(result.breakdown.obdSpeedEvaluated)
        assertFalse(result.confirmed)
        assertTrue(result.score < 60)
    }

    @Test fun distinctObdSpeedSamplesIncreaseOnlyCappedSupportingEvidence() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        val results = listOf(2L, 4L, 6L).map { sampleSecond ->
            detector.observeObdVehicleSpeed(obdSpeed(30, sampleSecond))
            detector.assess(fix(55.0, 37.0, sampleSecond + 1, speed = 20.0, speedAccuracy = 0.5),
                null, false, (sampleSecond + 1) * 1_000_000_000L + 200_000_000L, true)
        }
        assertEquals(listOf(15, 25, 35), results.map { it.breakdown.obdSpeedScore })
        assertFalse(results.last().confirmed)
        assertTrue(results.last().score < 60)
    }

    @Test fun repeatedGpsFixesCannotCountTheSameObdSampleMoreThanOnce() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        val sample = obdSpeed(30, 2)
        detector.observeObdVehicleSpeed(sample)
        val first = detector.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = 0.5),
            null, false, 3_200_000_000L, true)
        detector.observeObdVehicleSpeed(sample)
        val second = detector.assess(fix(55.0, 37.0, 4, speed = 20.0, speedAccuracy = 0.5),
            null, false, 4_200_000_000L, true)
        assertEquals(15, first.breakdown.obdSpeedScore)
        assertEquals(15, second.breakdown.obdSpeedScore)
        assertFalse(second.confirmed)
    }

    @Test fun aNewConsistentObdSampleResetsTheDisagreementStreak() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        detector.observeObdVehicleSpeed(obdSpeed(30, 2))
        val mismatch = detector.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = 0.5),
            null, false, 3_200_000_000L, true)
        detector.observeObdVehicleSpeed(obdSpeed(72, 4))
        val agreement = detector.assess(fix(55.0, 37.0, 5, speed = 20.0, speedAccuracy = 0.5),
            null, false, 5_200_000_000L, true)
        detector.observeObdVehicleSpeed(obdSpeed(30, 6))
        val nextMismatch = detector.assess(fix(55.0, 37.0, 7, speed = 20.0, speedAccuracy = 0.5),
            null, false, 7_200_000_000L, true)
        assertEquals(15, mismatch.breakdown.obdSpeedScore)
        assertEquals(0, agreement.breakdown.obdSpeedScore)
        assertTrue(agreement.breakdown.obdSpeedEvaluated)
        assertEquals(15, nextMismatch.breakdown.obdSpeedScore)
    }

    @Test fun staleFutureOrLowQualityObdGnssPairsAreNeutral() {
        val stale = GnssSpoofingDetector()
        stale.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        stale.observeObdVehicleSpeed(obdSpeed(30, 2))
        val staleResult = stale.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = 0.5),
            null, false, 9_200_000_000L, true)
        assertEquals(0, staleResult.breakdown.obdSpeedScore)
        assertFalse(staleResult.breakdown.obdSpeedEvaluated)

        val future = GnssSpoofingDetector()
        future.observeObdVehicleSpeed(obdSpeed(30, 4))
        val futureResult = future.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = 0.5),
            null, false, 4_200_000_000L, true)
        assertEquals(0, futureResult.breakdown.obdSpeedScore)
        assertFalse(futureResult.breakdown.obdSpeedEvaluated)

        val lowQuality = GnssSpoofingDetector()
        lowQuality.observeObdVehicleSpeed(obdSpeed(30, 2))
        val lowQualityResult = lowQuality.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = null),
            null, false, 3_200_000_000L, true)
        assertEquals(0, lowQualityResult.breakdown.obdSpeedScore)
        assertFalse(lowQualityResult.breakdown.obdSpeedEvaluated)
    }

    @Test fun obdProbeWithExcessiveDurationIsRejected() {
        val detector = GnssSpoofingDetector()
        detector.observeObdVehicleSpeed(obdSpeed(30, 2, 1_501L))
        val result = detector.assess(fix(55.0, 37.0, 3, speed = 20.0, speedAccuracy = 0.5),
            null, false, 3_200_000_000L, true)
        assertEquals(0, result.breakdown.obdSpeedScore)
        assertFalse(result.breakdown.obdSpeedEvaluated)
    }

    @Test fun singleRadioDisagreementIsWeakSupportingEvidenceAndCannotConfirmSpoofing() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        detector.observeRadioPosition(
            RadioPositionSnapshot(55.0, 37.01, 30.0, 2_000_000_000L)
        )

        val result = detector.assess(
            fix(55.0, 37.00001, 2), null, false, 2_200_000_000L, true
        )

        assertFalse(result.confirmed)
        assertTrue(result.score in 1..59)
        assertEquals(25, result.breakdown.radioPositionScore)
        assertTrue(result.breakdown.radioPositionEvaluated)
    }

    @Test fun theSameRadioTimestampCannotArtificiallyIncreaseTheDisagreementStreak() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        val oneScan = RadioPositionSnapshot(55.0, 37.01, 30.0, 2_000_000_000L)
        detector.observeRadioPosition(oneScan)
        val first = detector.assess(
            fix(55.0, 37.00001, 2), null, false, 2_200_000_000L, true
        )
        detector.observeRadioPosition(oneScan)
        val laterGps = detector.assess(
            fix(55.0, 37.00002, 3), null, false, 3_200_000_000L, true
        )

        assertEquals(25, first.breakdown.radioPositionScore)
        assertEquals(25, laterGps.breakdown.radioPositionScore)
        assertFalse(laterGps.confirmed)
    }

    @Test fun repeatedDistinctRadioDisagreementsIncreaseOnlyCappedSupportingScore() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)

        val expectedScores = listOf(25, 35, 45)
        val sampleTimes = listOf(2L, 32L, 62L)
        val results = sampleTimes.map { seconds ->
            detector.observeRadioPosition(
                RadioPositionSnapshot(55.0, 37.01, 30.0, seconds * 1_000_000_000L)
            )
            detector.assess(
                fix(55.0, 37.00001, seconds), null, false,
                seconds * 1_000_000_000L + 200_000_000L, true
            )
        }

        assertEquals(expectedScores, results.map { it.breakdown.radioPositionScore })
        assertFalse(results.last().confirmed)
        assertTrue(results.last().score <= 50)
    }

    @Test fun staleOrUnreliableRadioPositionIsIgnored() {
        val detector = GnssSpoofingDetector()
        detector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        detector.observeRadioPosition(
            RadioPositionSnapshot(55.0, 37.01, 500.0, 2_000_000_000L)
        )
        val coarse = detector.assess(
            fix(55.0, 37.00001, 2), null, false, 2_200_000_000L, true
        )
        assertEquals(0, coarse.breakdown.radioPositionScore)

        detector.observeRadioPosition(
            RadioPositionSnapshot(55.0, 37.01, 30.0, 3_000_000_000L)
        )
        val stale = detector.assess(
            fix(55.0, 37.00001, 3), null, false, 50_000_000_001L, true
        )
        assertEquals(0, stale.breakdown.radioPositionScore)
        assertFalse(stale.confirmed)
    }

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

    @Test fun riskBreakdownShowsSourceScoresAndNonAdditiveCombinationBonuses() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(
                elapsedRealtimeNanos = 1_500_000_000L,
                satellitesVisible = 8,
                satellitesUsedInFix = 8,
                meanCn0DbHz = 35.0,
                cn0SpreadDb = 0.7,
                constellationCount = 3,
                strongSignalCount = 8
            )
        )
        detector.assess(fix(55.0, 37.0, 1, accuracy = 5.0), null, false, 1_200_000_000L, true)
        val assessment = detector.assess(
            fix(56.0, 37.0, 2, accuracy = 5.0),
            fix(55.0, 37.0, 2, accuracy = 5.0, provider = "network"),
            false, 2_200_000_000L, true
        )
        val breakdown = assessment.breakdown
        assertTrue(breakdown.available)
        assertTrue(breakdown.motionEvaluated)
        assertTrue(breakdown.crossSourceEvaluated)
        assertTrue(breakdown.gnssSignalEvaluated)
        assertEquals(90, breakdown.motionScore)
        assertEquals(76, breakdown.crossSourceScore)
        assertEquals(10, breakdown.gnssSignalScore)
        assertEquals(20, breakdown.motionCrossSourceBonus)
        assertEquals(10, breakdown.gnssCorroborationBonus)
        assertEquals(120, breakdown.combinedBeforeZone)
        assertTrue(breakdown.clampedAtHundred)
        assertEquals(100, breakdown.finalScore)
        assertEquals(assessment.score, breakdown.finalScore)
    }

    @Test fun zoneFloorAndTemporalHoldAreReportedSeparatelyFromSourceScores() {
        val zoneDetector = GnssSpoofingDetector()
        val zoneAssessment = zoneDetector.assess(fix(55.0, 37.0, 1), null, true, 1_200_000_000L, true)
        assertEquals(0, zoneAssessment.breakdown.combinedBeforeZone)
        assertTrue(zoneAssessment.breakdown.zoneFloorActive)
        assertTrue(zoneAssessment.breakdown.zoneRaisedScore)
        assertEquals(75, zoneAssessment.breakdown.rawCombinedScore)
        assertEquals(75, zoneAssessment.breakdown.finalScore)

        val holdDetector = GnssSpoofingDetector()
        holdDetector.assess(fix(55.0, 37.0, 1), null, false, 1_200_000_000L, true)
        holdDetector.assess(fix(56.0, 37.0, 2), null, false, 2_200_000_000L, true)
        val held = holdDetector.assess(fix(56.0, 37.0, 3), null, false, 3_200_000_000L, true)
        assertTrue(held.breakdown.temporalHoldFloorApplied)
        assertEquals(0, held.breakdown.rawCombinedScore)
        assertEquals(60, held.breakdown.finalScore)
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


    @Test fun satelliteLossAndRecoveryAreNotMistakenForAnAttack() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(1_100_000_000L, 10, 8, 34.0, 4.0, 3, strongSignalCount = 7)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(2_100_000_000L, 0, 0, null, null, 0, strongSignalCount = 0)
        )
        assertEquals(0, detector.assessSignalOnly(2_200_000_000L).score)

        detector.observeGnssSignal(
            GnssSignalSnapshot(3_100_000_000L, 10, 8, 33.0, 4.5, 3, strongSignalCount = 7)
        )
        val recovered = detector.receptionDiagnostics(3_200_000_000L)
        assertEquals(GnssEnvironmentContext.LIKELY_OPEN_SKY, recovered.environment.context)
        assertEquals(0, detector.assessSignalOnly(3_200_000_000L).score)
    }

    @Test fun malformedSatelliteSnapshotsCannotBuildASignalWarning() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(1_100_000_000L, 10, 8, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        // Each snapshot reports more strong satellites than satellites visible, so neither may
        // count toward the underused-strong-signal evidence streak.
        detector.observeGnssSignal(
            GnssSignalSnapshot(2_100_000_000L, 10, 1, 38.0, 5.0, 3, strongSignalCount = 11)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(3_100_000_000L, 10, 1, 38.0, 5.0, 3, strongSignalCount = 12)
        )

        val result = detector.assessSignalOnly(3_200_000_000L)
        assertEquals(0, result.score)
        assertFalse(result.confirmed)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun signalOnlyWarningExpiresWithoutNewEvidence() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(
            GnssSignalSnapshot(1_100_000_000L, 10, 8, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(2_100_000_000L, 10, 1, 38.1, 5.0, 3, strongSignalCount = 8)
        )
        detector.observeGnssSignal(
            GnssSignalSnapshot(3_100_000_000L, 10, 1, 38.0, 5.0, 3, strongSignalCount = 8)
        )
        assertTrue(detector.assessSignalOnly(3_200_000_000L).score >= 30)

        // The warning is a short-lived hint, not a latched attack state.
        val expired = detector.assessSignalOnly(14_000_000_000L)
        assertEquals(0, expired.score)
        assertFalse(expired.confirmed)
        assertTrue(expired.reasons.isEmpty())
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
