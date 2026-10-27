package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeConsistencyPolicyTest {
    private val config = AltitudeConsistencyConfig(
        maximumObservationAgeNanos = seconds(5),
        uncertaintyMultiplier = 3.0,
        minimumToleranceMeters = 8.0,
        minimumSustainedDiscrepancyNanos = seconds(10),
        maximumEvidenceGapNanos = seconds(2)
    )
    private val policy = AltitudeConsistencyPolicy(config)

    @Test
    fun agreesWithinMaximumOfFloorAndCombinedUncertainty() {
        val result = policy.compare(
            observation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 3.0, 10),
            observation(112.0, AltitudeVerticalDatum.EGM96_GEOID, 4.0, 10, AltitudeMeasurementSource.DEM),
            now(10)
        )

        assertEquals(AltitudeComparisonStatus.CONSISTENT, result.status)
        assertEquals(12.0, result.absoluteDifferenceMeters!!, 0.0)
        assertEquals(15.0, result.allowedDifferenceMeters!!, 0.000001)
    }

    @Test
    fun reportsDiscrepancyOnlyWhenDifferenceExceedsTolerance() {
        val result = policy.compare(
            observation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 3.0, 10),
            observation(116.0, AltitudeVerticalDatum.EGM96_GEOID, 4.0, 10, AltitudeMeasurementSource.DEM),
            now(10)
        )
        assertEquals(AltitudeComparisonStatus.DISCREPANT, result.status)
        assertEquals(AltitudeComparisonReason.OUTSIDE_TOLERANCE, result.reason)
        assertEquals(15.0, result.allowedDifferenceMeters!!, 0.000001)
    }

    @Test
    fun refusesToCompareHeightsFromDifferentDatums() {
        val result = policy.compare(
            observation(100.0, AltitudeVerticalDatum.WGS84_ELLIPSOID, 1.0, 10),
            observation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 1.0, 10, AltitudeMeasurementSource.DEM),
            now(10)
        )
        assertEquals(AltitudeComparisonStatus.INSUFFICIENT_DATA, result.status)
        assertEquals(AltitudeComparisonReason.VERTICAL_DATUM_MISMATCH, result.reason)
        assertNull(result.absoluteDifferenceMeters)
    }

    @Test
    fun unspecifiedMeanSeaLevelIsNotSilentlyTreatedAsEgm96() {
        val result = policy.compare(
            observation(100.0, AltitudeVerticalDatum.MEAN_SEA_LEVEL_UNSPECIFIED, 1.0, 10),
            observation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 1.0, 10, AltitudeMeasurementSource.DEM),
            now(10)
        )
        assertEquals(AltitudeComparisonStatus.INSUFFICIENT_DATA, result.status)
        assertEquals(AltitudeComparisonReason.UNKNOWN_VERTICAL_DATUM, result.reason)
    }

    @Test
    fun missingUncertaintyAndStaleOrFutureMeasurementsAreNeutral() {
        val reference = observation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 1.0, 10)
        assertEquals(
            AltitudeComparisonReason.INVALID_UNCERTAINTY,
            policy.compare(
                reference,
                observation(120.0, AltitudeVerticalDatum.EGM96_GEOID, null, 10, AltitudeMeasurementSource.DEM),
                now(10)
            ).reason
        )
        assertEquals(
            AltitudeComparisonReason.STALE_OBSERVATION,
            policy.compare(reference, reference.copy(elapsedRealtimeNanos = 4), now(10)).reason
        )
        assertEquals(
            AltitudeComparisonReason.INVALID_TIMESTAMP,
            policy.compare(reference, reference.copy(elapsedRealtimeNanos = 11), now(10)).reason
        )
        assertEquals(
            AltitudeComparisonReason.MISSING_OBSERVATION,
            policy.compare(null, reference, now(10)).reason
        )
    }

    @Test
    fun disagreementMustPersistWithoutLongGapsToBecomeSustained() {
        val tracker = SustainedAltitudeDiscrepancyTracker(config)
        val discrepancy = AltitudeComparisonResult(
            AltitudeComparisonStatus.DISCREPANT,
            AltitudeComparisonReason.OUTSIDE_TOLERANCE,
            absoluteDifferenceMeters = 40.0,
            allowedDifferenceMeters = 15.0
        )

        assertFalse(tracker.update(discrepancy, seconds(10)).sustained)
        assertFalse(tracker.update(discrepancy, seconds(12)).sustained)
        assertFalse(tracker.update(discrepancy, seconds(14)).sustained)
        assertFalse(tracker.update(discrepancy, seconds(16)).sustained)
        assertFalse(tracker.update(discrepancy, seconds(18)).sustained)
        assertTrue(tracker.update(discrepancy, seconds(20)).sustained)
    }

    @Test
    fun agreeingOrInsufficientSampleResetsDiscrepancyWindow() {
        val tracker = SustainedAltitudeDiscrepancyTracker(config)
        val discrepancy = AltitudeComparisonResult(
            AltitudeComparisonStatus.DISCREPANT,
            AltitudeComparisonReason.OUTSIDE_TOLERANCE,
            40.0,
            15.0
        )
        val consistent = AltitudeComparisonResult(
            AltitudeComparisonStatus.CONSISTENT,
            AltitudeComparisonReason.WITHIN_TOLERANCE,
            2.0,
            8.0
        )

        tracker.update(discrepancy, seconds(10))
        tracker.update(discrepancy, seconds(12))
        assertFalse(tracker.update(consistent, seconds(13)).active)
        assertFalse(tracker.update(discrepancy, seconds(14)).sustained)
        assertFalse(tracker.update(discrepancy, seconds(15)).sustained)
    }

    @Test
    fun evidenceGapAndBackwardsClockRestartTheWindow() {
        val tracker = SustainedAltitudeDiscrepancyTracker(config)
        val discrepancy = AltitudeComparisonResult(
            AltitudeComparisonStatus.DISCREPANT,
            AltitudeComparisonReason.OUTSIDE_TOLERANCE,
            40.0,
            15.0
        )
        tracker.update(discrepancy, seconds(10))
        tracker.update(discrepancy, seconds(12))
        val gap = tracker.update(discrepancy, seconds(16))
        assertTrue(gap.active)
        assertEquals(0L, gap.durationNanos)
        val backwards = tracker.update(discrepancy, seconds(15))
        assertFalse(backwards.active)
    }

    private fun observation(
        height: Double,
        datum: AltitudeVerticalDatum,
        uncertainty: Double?,
        timestampSeconds: Long,
        source: AltitudeMeasurementSource = AltitudeMeasurementSource.GNSS
    ) = AltitudeObservation(
        valueMeters = height,
        verticalDatum = datum,
        uncertaintyMeters = uncertainty,
        elapsedRealtimeNanos = seconds(timestampSeconds),
        source = source
    )

    private fun now(seconds: Long) = seconds(seconds)
    private fun seconds(value: Long) = value * 1_000_000_000L
}
