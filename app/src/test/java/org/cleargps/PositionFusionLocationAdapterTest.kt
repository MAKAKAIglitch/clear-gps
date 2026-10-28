package org.cleargps

import android.location.Location
import android.location.LocationManager
import org.cleargps.core.FilterState
import org.cleargps.fusion.FusedPosition
import org.cleargps.fusion.PositionMeasurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PositionFusionLocationAdapterTest {
    @Test
    fun onlyTrustedPhysicalGpsFixesBecomeFusionMeasurements() {
        val input = gpsLocation(accuracyMeters = 5f)

        val measurement = PositionFusionLocationAdapter.trustedGnssMeasurement(
            location = input,
            trustState = FilterState.NORMAL,
            decisionSource = "GPS",
            inRiskZone = false
        )

        assertNotNull(measurement)
        assertEquals(PositionMeasurementSource.GNSS, measurement!!.source)
        assertEquals(input.elapsedRealtimeNanos, measurement.elapsedRealtimeNanos)
        assertEquals(10.0, measurement.horizontalUncertaintyMeters, 0.000001)
    }

    @Test
    fun nonNormalGpsCandidatesAreSuppressedButIndependentFallbacksRemainEligible() {
        assertTrue(PositionFusionLocationAdapter.shouldSuppressGpsOutput(
            FilterState.SUSPECT, "GPS", false
        ))
        assertTrue(PositionFusionLocationAdapter.shouldSuppressGpsOutput(
            FilterState.NORMAL, "GPS", true
        ))
        assertTrue(PositionFusionLocationAdapter.shouldSuppressGpsOutput(
            FilterState.CORRECTING, "GPS", false
        ))
        assertFalse(PositionFusionLocationAdapter.shouldSuppressGpsOutput(
            FilterState.CORRECTING, "LOCAL_RADIO", false
        ))
        assertFalse(PositionFusionLocationAdapter.shouldSuppressGpsOutput(
            FilterState.NORMAL, "GPS", false
        ))
    }

    @Test
    fun suspectOrNonGpsOrRiskZoneFixesAreNotAdmitted() {
        val input = gpsLocation(accuracyMeters = 5f)

        assertNull(PositionFusionLocationAdapter.trustedGnssMeasurement(
            input, FilterState.SUSPECT, "GPS", false
        ))
        assertNull(PositionFusionLocationAdapter.trustedGnssMeasurement(
            input, FilterState.NORMAL, "NETWORK", false
        ))
        assertNull(PositionFusionLocationAdapter.trustedGnssMeasurement(
            input, FilterState.NORMAL, "GPS", true
        ))

        val network = Location(input).apply { provider = LocationManager.NETWORK_PROVIDER }
        assertNull(PositionFusionLocationAdapter.trustedGnssMeasurement(
            network, FilterState.NORMAL, "GPS", false
        ))
    }

    @Test
    fun invalidCoordinatesAndOverlyUncertainFixesAreRejected() {
        val invalidCoordinates = gpsLocation(accuracyMeters = 5f).apply { latitude = 91.0 }
        assertNull(PositionFusionLocationAdapter.trustedGnssMeasurement(
            invalidCoordinates, FilterState.NORMAL, "GPS", false
        ))

        val coarseFix = gpsLocation(accuracyMeters = 2_501f)
        assertNull(PositionFusionLocationAdapter.trustedGnssMeasurement(
            coarseFix, FilterState.NORMAL, "GPS", false
        ))
    }

    @Test
    fun fusedOutputPreservesMeasuredSpeedAndBearing() {
        val source = gpsLocation(accuracyMeters = 10f).apply {
            speed = 4f
            bearing = 90f
        }
        val estimate = estimate(
            latitude = 52.0001,
            longitude = 5.0002,
            accuracyMeters = 8.0,
            elapsedNanos = 10_500_000_000L,
            acceptedCount = 3,
            speedMetersPerSecond = 12.0,
            bearingDegrees = 45.0
        )

        val output = PositionFusionLocationAdapter.toLocation(
            estimate, source, wallTimeMillis = 123_000L, nowElapsedRealtimeNanos = 10_500_000_000L
        )

        assertNotNull(output)
        assertEquals(52.0001, output!!.latitude, 0.0000001)
        assertEquals(5.0002, output.longitude, 0.0000001)
        assertEquals(8f, output.accuracy, 0.0001f)
        assertEquals(4f, output.speed, 0.0001f)
        assertEquals(90f, output.bearing, 0.0001f)
        assertEquals(123_000L, output.time)
        assertEquals(10_500_000_000L, output.elapsedRealtimeNanos)
    }

    @Test
    fun estimatedSpeedIsAddedOnlyWhenItCanBeDerivedFromMultipleFixes() {
        val source = gpsLocation(accuracyMeters = 10f)
        val estimate = estimate(
            latitude = 52.0001,
            longitude = 5.0002,
            accuracyMeters = 8.0,
            elapsedNanos = 10_500_000_000L,
            acceptedCount = 2,
            speedMetersPerSecond = 6.0,
            bearingDegrees = 30.0
        )
        val output = PositionFusionLocationAdapter.toLocation(
            estimate, source, 123_000L, 10_500_000_000L
        )

        assertNotNull(output)
        assertTrue(output!!.hasSpeed())
        assertEquals(6f, output.speed, 0.0001f)
        assertTrue(output.hasBearing())
        assertEquals(30f, output.bearing, 0.0001f)
    }

    @Test
    fun staleOrFutureFusionEstimatesAreNotPublished() {
        val source = gpsLocation(accuracyMeters = 10f)
        val stale = estimate(52.0, 5.0, 8.0, 10_000_000_000L, 2)
        val future = estimate(52.0, 5.0, 8.0, 13_000_000_000L, 2)

        assertNull(PositionFusionLocationAdapter.toLocation(
            stale, source, 123_000L, nowElapsedRealtimeNanos = 13_000_000_001L
        ))
        assertNull(PositionFusionLocationAdapter.toLocation(
            future, source, 123_000L, nowElapsedRealtimeNanos = 12_000_000_000L
        ))
    }

    @Test
    fun freshRadioEstimateUsesItsOriginalMonotonicEvidenceTimestamp() {
        val measuredAt = 10_000_000_000L
        val estimate = RadioEstimate(
            latitude = 52.1,
            longitude = 5.1,
            accuracyMeters = 25.0,
            updatedAt = 1_000L,
            fingerprint = "radio-fixture",
            matchedCount = 3,
            matchedRadioTypes = setOf("wifi", "cell"),
            matchedRadioIdentifiers = setOf("ap-one", "ap-two", "cell-one"),
            measurementElapsedRealtimeNanos = measuredAt
        )

        val measurement = PositionFusionLocationAdapter.radioMeasurement(
            estimate = estimate,
            nowElapsedRealtimeNanos = measuredAt + 1_000_000_000L
        )

        assertNotNull(measurement)
        assertEquals(PositionMeasurementSource.RADIO, measurement!!.source)
        assertEquals(measuredAt, measurement.elapsedRealtimeNanos)
        assertEquals(25.0, measurement.horizontalUncertaintyMeters, 0.000001)
        assertEquals(52.1, measurement.latitude, 0.000001)
        assertEquals(5.1, measurement.longitude, 0.000001)

        val unrealisticallyPrecise = PositionFusionLocationAdapter.radioMeasurement(
            estimate.copy(accuracyMeters = 1.0),
            nowElapsedRealtimeNanos = measuredAt + 1_000_000_000L
        )
        assertNotNull(unrealisticallyPrecise)
        assertEquals(10.0, unrealisticallyPrecise!!.horizontalUncertaintyMeters, 0.000001)
    }

    @Test
    fun radioFusionRejectsMissingStaleFutureOrInsufficientEvidence() {
        val measuredAt = 10_000_000_000L
        val now = measuredAt + 1_000_000_000L
        val estimate = RadioEstimate(
            latitude = 52.1,
            longitude = 5.1,
            accuracyMeters = 25.0,
            updatedAt = 1_000L,
            fingerprint = "radio-fixture",
            matchedCount = 3,
            matchedRadioTypes = setOf("wifi"),
            matchedRadioIdentifiers = setOf("ap-one", "ap-two", "ap-three"),
            measurementElapsedRealtimeNanos = measuredAt
        )

        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(
            measurementElapsedRealtimeNanos = null
        ), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate, measuredAt +
            PositionFusionLocationAdapter.MAX_RADIO_EVIDENCE_AGE_NANOS + 1L))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(
            measurementElapsedRealtimeNanos = now + 1L
        ), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(matchedCount = 1), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(matchedRadioIdentifiers = emptySet()), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(
            matchedRadioIdentifiers = setOf("only-one-identifier")
        ), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(accuracyMeters = Double.NaN), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(accuracyMeters = 5_001.0), now))
        assertNull(PositionFusionLocationAdapter.radioMeasurement(estimate.copy(latitude = 91.0), now))
    }

    private fun gpsLocation(accuracyMeters: Float) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 52.0
        longitude = 5.0
        accuracy = accuracyMeters
        time = 100_000L
        elapsedRealtimeNanos = 10_000_000_000L
    }

    private fun estimate(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        elapsedNanos: Long,
        acceptedCount: Int,
        speedMetersPerSecond: Double = 0.0,
        bearingDegrees: Double? = null
    ) = FusedPosition(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        elapsedRealtimeNanos = elapsedNanos,
        lastMeasurementElapsedRealtimeNanos = elapsedNanos - 500_000_000L,
        acceptedMeasurementCount = acceptedCount,
        sourcesUsed = setOf(PositionMeasurementSource.GNSS)
    )
}
