package org.cleargps.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class CellTimingAdvanceSiteEstimatorTest {
    private val identity = CellIdentity(
        technology = CellTechnology.LTE,
        mcc = "234",
        mnc = "15",
        areaCode = 31_000L,
        cellId = 765_432L
    )
    private val siteLatitude = 51.5007
    private val siteLongitude = -0.1246

    @Test
    fun estimatesSiteFromSpatiallyDistributedSynchronizedLteMeasurements() {
        val samples = (0 until 16).map { index ->
            val bearing = index * 22.5
            val distance = 600.0 + (index % 4) * 180.0
            sample(index, bearing, distance)
        }

        val result = CellTimingAdvanceSiteEstimator.estimate(identity, samples)

        assertNotNull(result)
        result!!
        assertEquals(identity.stableKey, result.identity.stableKey)
        assertTrue(distanceMeters(siteLatitude, siteLongitude, result.latitude, result.longitude) < 100.0)
        assertTrue(result.uncertaintyRadiusMeters in 1.0..500.0)
        assertTrue(result.sampleCount >= 8)
        assertTrue(result.occupiedBearingBins >= 4)
        assertTrue(result.confidenceScore >= 0.45)
    }

    @Test
    fun robustFitRejectsOneLargeTimingAdvanceOutlier() {
        val samples = (0 until 16).map { index ->
            val distance = 650.0 + (index % 3) * 220.0
            val ordinary = sample(index, index * 22.5, distance)
            if (index == 7) ordinary.copy(timingAdvanceValue = ordinary.timingAdvanceValue!! + 20)
            else ordinary
        }

        val result = CellTimingAdvanceSiteEstimator.estimate(identity, samples)

        assertNotNull(result)
        assertTrue(distanceMeters(siteLatitude, siteLongitude, result!!.latitude, result.longitude) < 140.0)
    }

    @Test
    fun rejectsUnsynchronizedUnknownOrPoorGeometrySamples() {
        val unsynchronized = (0 until 12).map { index ->
            sample(index, index * 30.0, 800.0).copy(timingAdvancePositionDeltaMillis = 2_000L)
        }
        val gsm = (0 until 12).map { index ->
            sample(index, index * 30.0, 800.0).copy(
                timingAdvanceUnit = "gsm-symbols"
            )
        }
        val line = (0 until 12).map { index ->
            val east = -2_000.0 + index * 360.0
            sampleAtLocalEast(index, east, 1_000.0)
        }

        assertNull(CellTimingAdvanceSiteEstimator.estimate(identity, unsynchronized))
        assertNull(CellTimingAdvanceSiteEstimator.estimate(identity, gsm))
        assertNull(CellTimingAdvanceSiteEstimator.estimate(identity, line))
    }

    private fun sample(index: Int, bearingDegrees: Double, distanceMeters: Double): CellObservationSample {
        val bearing = Math.toRadians(bearingDegrees)
        val angular = distanceMeters / 6_371_008.8
        val lat0 = Math.toRadians(siteLatitude)
        val lon0 = Math.toRadians(siteLongitude)
        val latitude = Math.toDegrees(
            kotlin.math.asin(
                sin(lat0) * cos(angular) +
                    cos(lat0) * sin(angular) * cos(bearing)
            )
        )
        val longitude = Math.toDegrees(
            lon0 + kotlin.math.atan2(
                sin(bearing) * sin(angular) * cos(lat0),
                cos(angular) - sin(lat0) * sin(Math.toRadians(latitude))
            )
        )
        return sampleAt(index, latitude, longitude, distanceMeters)
    }

    private fun sampleAtLocalEast(index: Int, eastMeters: Double, northMeters: Double): CellObservationSample {
        val latitude = siteLatitude + Math.toDegrees(northMeters / 6_371_008.8)
        val longitude = siteLongitude + Math.toDegrees(
            eastMeters / (6_371_008.8 * cos(Math.toRadians(siteLatitude)))
        )
        val distance = kotlin.math.hypot(eastMeters, northMeters)
        return sampleAt(index, latitude, longitude, distance)
    }

    private fun sampleAt(
        index: Int,
        latitude: Double,
        longitude: Double,
        distanceMeters: Double
    ) = CellObservationSample(
        identity = identity,
        latitude = latitude,
        longitude = longitude,
        coordinateAccuracyMeters = 8.0,
        rssiDbm = -70.0,
        frequencyKey = 1_800,
        observedAtMillis = 1_700_000_000_000L + index * 1_000L,
        locationOrigin = ObservationLocationOrigin.OBSERVED,
        receiverProfileId = "test-receiver",
        sourceGroupId = "local:1",
        timingAdvanceValue = (distanceMeters / CellTimingAdvanceRangePolicy.LTE_ONE_WAY_STEP_METERS)
            .roundToInt().coerceIn(0, CellTimingAdvanceRangePolicy.LTE_COMMAND_INDEX_MAX),
        timingAdvanceUnit = "lte-command-index",
        timingAdvancePositionDeltaMillis = 100L
    )

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        CellSiteCoordinateEstimator.distanceMeters(lat1, lon1, lat2, lon2)
}
