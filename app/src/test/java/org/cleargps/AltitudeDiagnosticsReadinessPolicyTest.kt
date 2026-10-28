package org.cleargps

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AltitudeDiagnosticsReadinessPolicyTest {
    @Test
    fun ellipsoidGnssAndEgm96DemAreNotComparableEvenWhenNumbersMatch() {
        val result = assess(
            makeObservation(100.0, AltitudeVerticalDatum.WGS84_ELLIPSOID, 5.0, 10),
            makeObservation(100.0, AltitudeVerticalDatum.EGM96_GEOID, null, 10, AltitudeMeasurementSource.DEM),
            null,
            now(10)
        )

        assertEquals(AltitudeComparisonReadiness.VERTICAL_DATUM_MISMATCH, result.comparisonReadiness)
    }

    @Test
    fun AndroidMslAltitudeIsNotAssumedToUseEgm96() {
        val location = Location("gps").apply {
            altitude = 123.0
            verticalAccuracyMeters = 5.0f
            elapsedRealtimeNanos = now(10)
            mslAltitudeMeters = 120.0
            mslAltitudeAccuracyMeters = 4.0f
        }

        val gnssObservation = requireNotNull(
            AltitudeDiagnosticsReadinessPolicy.gnssObservation(location)
        )

        assertEquals(AltitudeVerticalDatum.MEAN_SEA_LEVEL_UNSPECIFIED, gnssObservation.verticalDatum)
        assertEquals(120.0, gnssObservation.valueMeters, 0.0)
        val result = assess(
            gnssObservation,
            makeObservation(120.0, AltitudeVerticalDatum.EGM96_GEOID, null, 10, AltitudeMeasurementSource.DEM),
            null,
            now(10)
        )
        assertEquals(AltitudeComparisonReadiness.UNKNOWN_VERTICAL_DATUM, result.comparisonReadiness)
    }

    @Test
    fun lackOfDemUncertaintyPreventsComparisonWhenDatumMatches() {
        val result = assess(
            makeObservation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 5.0, 10),
            makeObservation(100.0, AltitudeVerticalDatum.EGM96_GEOID, null, 10, AltitudeMeasurementSource.DEM),
            null,
            now(10)
        )

        assertEquals(AltitudeComparisonReadiness.DEM_UNCERTAINTY_UNAVAILABLE, result.comparisonReadiness)
    }

    @Test
    fun matchingDatumAndKnownUncertaintiesOnlyMakeEvidenceReadyForPolicy() {
        val result = assess(
            makeObservation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 5.0, 10),
            makeObservation(101.0, AltitudeVerticalDatum.EGM96_GEOID, 8.0, 10, AltitudeMeasurementSource.DEM),
            null,
            now(10)
        )

        assertEquals(AltitudeComparisonReadiness.READY_FOR_POLICY, result.comparisonReadiness)
        assertTrue(AltitudeDiagnosticsReadinessPolicy.description(result).contains("не влияет на риск спуфинга"))
    }

    @Test
    fun rejectsFutureAndStaleMeasurements() {
        val future = assess(
            makeObservation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 5.0, 11),
            makeObservation(101.0, AltitudeVerticalDatum.EGM96_GEOID, 8.0, 10, AltitudeMeasurementSource.DEM),
            null,
            now(10)
        )
        assertEquals(AltitudeComparisonReadiness.INVALID_MONOTONIC_TIMESTAMP, future.comparisonReadiness)

        val stale = assess(
            makeObservation(100.0, AltitudeVerticalDatum.EGM96_GEOID, 5.0, 1),
            makeObservation(101.0, AltitudeVerticalDatum.EGM96_GEOID, 8.0, 1, AltitudeMeasurementSource.DEM),
            null,
            now(40)
        )
        assertEquals(AltitudeComparisonReadiness.STALE_OBSERVATION, stale.comparisonReadiness)
    }

    @Test
    fun pressureIsReportedAsRawOnlyAndStalePressureIsUnavailable() {
        val freshPressure = BarometerPressureSample(
            pressurePascals = 101_325.0,
            sensorResolutionPascals = 1.0,
            elapsedRealtimeNanos = now(10)
        )
        val fresh = assess(null, null, freshPressure, now(10))
        assertEquals(BarometerAltitudeReadiness.RAW_PRESSURE_ONLY, fresh.barometerReadiness)

        val stale = assess(null, null, freshPressure, now(16))
        assertEquals(BarometerAltitudeReadiness.NO_FRESH_PRESSURE, stale.barometerReadiness)
    }

    @Test
    fun locationWithoutAnAltitudeDoesNotCreateAnObservation() {
        val observation = AltitudeDiagnosticsReadinessPolicy.gnssObservation(
            Location("gps").apply { elapsedRealtimeNanos = now(10) }
        )
        assertNull(observation)
    }

    private fun assess(
        gnss: AltitudeObservation?,
        dem: AltitudeObservation?,
        pressure: BarometerPressureSample?,
        now: Long
    ) = AltitudeDiagnosticsReadinessPolicy.assess(
        gnssObservation = gnss,
        demObservation = dem,
        barometerPressureSample = pressure,
        nowElapsedRealtimeNanos = now
    )

    private fun makeObservation(
        value: Double,
        datum: AltitudeVerticalDatum,
        uncertainty: Double?,
        seconds: Long,
        source: AltitudeMeasurementSource = AltitudeMeasurementSource.GNSS
    ) = AltitudeObservation(
        valueMeters = value,
        verticalDatum = datum,
        uncertaintyMeters = uncertainty,
        elapsedRealtimeNanos = now(seconds),
        source = source
    )

    private fun now(seconds: Long) = seconds * 1_000_000_000L
}
