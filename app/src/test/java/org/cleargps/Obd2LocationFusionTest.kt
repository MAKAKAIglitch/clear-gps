package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2LocationFusionTest {
    @Test
    fun shortGpsGapIntegratesFreshMonotonicVehicleSpeedSamples() {
        val predicted = Obd2LocationFusion.predictFromGps(
            lastFix = MapCoordinate(52.0, 39.0),
            baseAccuracyMeters = 5.0,
            bearingDegrees = 0.0,
            fixElapsedRealtimeNanos = seconds(1),
            speedSamples = (2L..10L step 2L).map { sample(36, it) },
            nowElapsedRealtimeNanos = seconds(11)
        )
        val actual = predicted ?: throw AssertionError("Fresh speed history should produce a bounded prediction")
        assertEquals(100.0, actual.travelledMeters, 0.01)
        assertTrue(actual.coordinate.latitude > 52.0)
        assertEquals(39.0, actual.coordinate.longitude, 0.001)
        assertTrue(actual.accuracyMeters > 5.0)
        assertEquals(10_000L, actual.ageMillis)
    }

    @Test
    fun predictionIsDisabledWithoutHeadingOrFreshSpeedHistory() {
        assertNull(predict(bearing = null, samples = listOf(sample(36, 2), sample(36, 10))))
        assertNull(predict(samples = listOf(sample(36, 1))))
        assertNull(predict(samples = listOf(sample(0, 2), sample(0, 10))))
        assertNull(predict(samples = emptyList()))
    }

    @Test
    fun predictionHasStrictAgeAccuracyAndDistanceLimits() {
        assertNull(predict(now = seconds(31) + 1_000_000L, samples = listOf(sample(1, 2), sample(1, 10))))
        assertNull(predict(baseAccuracy = 150.0, samples = listOf(sample(36, 2), sample(36, 10))))
        assertNull(predict(baseAccuracy = null, samples = listOf(sample(36, 2), sample(36, 10))))
        assertNull(
            predict(now = seconds(31), samples = (2L..30L step 2L).map { sample(100, it) })
        )
    }

    @Test
    fun rejectsVehicleSpeedSamplesThatPredateGpsAnchorOrHaveSlowQueries() {
        assertNull(
            Obd2LocationFusion.predictFromGps(
                lastFix = MapCoordinate(52.0, 39.0),
                baseAccuracyMeters = 5.0,
                bearingDegrees = 90.0,
                fixElapsedRealtimeNanos = seconds(10),
                speedSamples = listOf(sample(36, 9)),
                nowElapsedRealtimeNanos = seconds(15)
            )
        )
        assertNull(
            predict(samples = listOf(sample(36, 2), sample(36, 10, queryDurationMillis = 1_501L)))
        )
        assertNull(predict(lastFix = MapCoordinate(91.0, 39.0), samples = listOf(sample(36, 2), sample(36, 10))))
    }

    @Test
    fun refusesToBridgeMissingSpeedSamples() {
        assertNull(predict(samples = listOf(sample(36, 2), sample(36, 9), sample(36, 10))))
    }

    private fun predict(
        lastFix: MapCoordinate = MapCoordinate(52.0, 39.0),
        baseAccuracy: Double? = 5.0,
        bearing: Double? = 90.0,
        samples: List<Obd2SpeedSample>,
        fixTime: Long = seconds(1),
        now: Long = seconds(11)
    ) = Obd2LocationFusion.predictFromGps(
        lastFix=lastFix,
        baseAccuracyMeters=baseAccuracy,
        bearingDegrees=bearing,
        fixElapsedRealtimeNanos=fixTime,
        speedSamples=samples,
        nowElapsedRealtimeNanos=now
    )

    private fun sample(speed: Int, at: Long, queryDurationMillis: Long = 250L) = Obd2SpeedSample(
        speedKmh=speed,
        elapsedRealtimeNanos=seconds(at),
        queryDurationMillis=queryDurationMillis
    )

    private fun seconds(value: Long) = value * 1_000_000_000L
}
