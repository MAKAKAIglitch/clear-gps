package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2LocationFusionTest {
    @Test
    fun shortGpsGapUsesFreshVehicleSpeedAndRecentBearing() {
        val predicted = Obd2LocationFusion.predictFromGps(
            lastFix = MapCoordinate(52.0, 39.0),
            baseAccuracyMeters = 5.0,
            bearingDegrees = 0.0,
            fixTimeMillis = 1_000L,
            speedKmh = 36,
            speedUpdatedAtMillis = 10_000L,
            nowMillis = 11_000L
        )

        val actual = predicted ?: throw AssertionError("Fresh speed and course should produce a bounded prediction")
        assertEquals(100.0, actual.travelledMeters, 0.01)
        assertTrue("Northbound bearing should increase latitude", actual.coordinate.latitude > 52.0)
        assertEquals(39.0, actual.coordinate.longitude, 0.001)
        assertTrue(actual.accuracyMeters > 5.0)
    }

    @Test
    fun predictionIsDisabledWithoutHeadingOrFreshSpeed() {
        val args = arrayOf(
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), 10.0, null, 1_000L, 36, 10_000L, 11_000L
            ),
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), 10.0, 90.0, 1_000L, 36, 1_000L, 11_000L
            ),
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), 10.0, 90.0, 1_000L, 0, 10_000L, 11_000L
            )
        )
        assertTrue(args.all { it == null })
    }

    @Test
    fun predictionHasStrictAgeAndDistanceLimits() {
        assertNull(
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), 10.0, 90.0, 1_000L, 1, 29_000L, 31_001L
            )
        )
        assertNull(
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), 150.0, 90.0, 1_000L, 36, 10_000L, 11_000L
            )
        )
        assertNull(
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), null, 90.0, 1_000L, 36, 10_000L, 11_000L
            )
        )
        assertNull(
            Obd2LocationFusion.predictFromGps(
                MapCoordinate(52.0, 39.0), 10.0, 90.0, 1_000L, 100, 29_000L, 31_000L
            )
        )
    }
}
