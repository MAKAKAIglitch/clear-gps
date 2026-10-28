package org.cleargps.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.PI

class PositionFusionEngineTest {
    @Test
    fun initializesFromFirstFixAndRecordsItsSource() {
        val engine = PositionFusionEngine()
        val result = engine.update(measurement(
            latitude = 52.1,
            longitude = 5.1,
            uncertainty = 5.0,
            timestampSeconds = 10
        ))

        assertEquals(PositionFusionUpdateStatus.INITIALIZED, result.status)
        assertNull(result.rejectionReason)
        assertNotNull(result.estimate)
        assertEquals(52.1, result.estimate!!.latitude, 0.000001)
        assertEquals(5.1, result.estimate!!.longitude, 0.000001)
        assertEquals(setOf(PositionMeasurementSource.GNSS), result.estimate!!.sourcesUsed)
    }

    @Test
    fun consistentRadioAndGnssFixesProduceSmoothedMotionEstimate() {
        val engine = PositionFusionEngine()
        engine.update(measurement(52.0, 0.0, 5.0, 1, PositionMeasurementSource.GNSS))
        val nextLongitude = longitudeAfterEastwardTravel(0.0, 52.0, 8.0)
        val update = engine.update(
            measurement(52.0, nextLongitude, 5.0, 2, PositionMeasurementSource.RADIO)
        )

        assertEquals(PositionFusionUpdateStatus.UPDATED, update.status)
        assertNotNull(update.estimate)
        assertTrue(update.estimate!!.speedMetersPerSecond in 1.0..10.0)
        assertTrue(update.estimate!!.longitude > 0.0)
        assertEquals(
            setOf(PositionMeasurementSource.GNSS, PositionMeasurementSource.RADIO),
            update.estimate!!.sourcesUsed
        )
        assertEquals(2, update.estimate!!.acceptedMeasurementCount)
    }

    @Test
    fun largeStatisticalOutlierIsRejectedWithoutAdvancingFilterClock() {
        val engine = PositionFusionEngine()
        engine.update(measurement(52.0, 0.0, 5.0, 1))
        val outlierLongitude = longitudeAfterEastwardTravel(0.0, 52.0, 1_000.0)

        val rejected = engine.update(measurement(52.0, outlierLongitude, 5.0, 2))
        assertEquals(PositionFusionUpdateStatus.REJECTED, rejected.status)
        assertEquals(PositionFusionRejectionReason.STATISTICAL_OUTLIER, rejected.rejectionReason)
        assertEquals(seconds(1), engine.estimate(seconds(1))!!.lastMeasurementElapsedRealtimeNanos)

        // Because the rejected sample did not advance the clock, a plausible fix at the same
        // timestamp remains eligible and can recover the filter.
        val plausibleLongitude = longitudeAfterEastwardTravel(0.0, 52.0, 8.0)
        val accepted = engine.update(measurement(52.0, plausibleLongitude, 5.0, 2))
        assertEquals(PositionFusionUpdateStatus.UPDATED, accepted.status)
    }

    @Test
    fun invalidCoordinatesAccuracyAndTimeAreRejectedWithoutInitialization() {
        val engine = PositionFusionEngine()

        assertEquals(
            PositionFusionRejectionReason.INVALID_COORDINATES,
            engine.update(measurement(latitude = Double.NaN, longitude = 0.0, uncertainty = 5.0, timestampSeconds = 1))
                .rejectionReason
        )
        assertEquals(
            PositionFusionRejectionReason.INVALID_UNCERTAINTY,
            engine.update(measurement(latitude = 52.0, longitude = 0.0, uncertainty = 0.0, timestampSeconds = 1))
                .rejectionReason
        )
        assertEquals(
            PositionFusionRejectionReason.INVALID_TIMESTAMP,
            engine.update(measurement(latitude = 52.0, longitude = 0.0, uncertainty = 5.0, timestampSeconds = 0))
                .rejectionReason
        )
        assertNull(engine.estimate(seconds(1)))
    }

    @Test
    fun duplicateAndOlderTimestampsAreRejected() {
        val engine = PositionFusionEngine()
        engine.update(measurement(52.0, 0.0, 5.0, 10))

        val older = engine.update(measurement(52.0, 0.0, 5.0, 9))
        assertEquals(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP, older.rejectionReason)
        val duplicate = engine.update(measurement(52.0, 0.0, 5.0, 10))
        assertEquals(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP, duplicate.rejectionReason)
    }

    @Test
    fun predictionExpiresAndFutureRequestsAreNeverReturned() {
        val engine = PositionFusionEngine()
        engine.update(measurement(52.0, 0.0, 5.0, 10))

        assertNotNull(engine.estimate(seconds(11)))
        assertNull(engine.estimate(seconds(13)))
        assertNull(engine.estimate(seconds(9)))
        assertNull(engine.estimate(0))
    }

    @Test
    fun longMeasurementGapStartsANewSegmentInsteadOfExtrapolatingOldVelocity() {
        val engine = PositionFusionEngine()
        engine.update(measurement(52.0, 0.0, 5.0, 1))
        engine.update(
            measurement(52.0, longitudeAfterEastwardTravel(0.0, 52.0, 8.0), 5.0, 2)
        )

        val next = engine.update(measurement(52.1, 5.1, 7.0, 20, PositionMeasurementSource.RADIO))
        assertEquals(PositionFusionUpdateStatus.REINITIALIZED_AFTER_GAP, next.status)
        assertNotNull(next.estimate)
        assertEquals(0.0, next.estimate!!.speedMetersPerSecond, 0.000001)
        assertEquals(setOf(PositionMeasurementSource.RADIO), next.estimate!!.sourcesUsed)
        assertEquals(1, next.estimate!!.acceptedMeasurementCount)
    }

    @Test
    fun longitudeWrapAcrossAntimeridianIsHandledAsShortDistance() {
        val engine = PositionFusionEngine()
        engine.update(measurement(0.0, 179.9999, 5.0, 1))
        val acrossAntimeridian = engine.update(measurement(0.0, -179.9999, 5.0, 2))

        assertEquals(PositionFusionUpdateStatus.UPDATED, acrossAntimeridian.status)
        assertNotNull(acrossAntimeridian.estimate)
        assertTrue(acrossAntimeridian.estimate!!.longitude in -180.0..180.0)
        assertTrue(acrossAntimeridian.estimate!!.speedMetersPerSecond > 0.0)
    }

    @Test
    fun resetClearsAllFusionState() {
        val engine = PositionFusionEngine()
        engine.update(measurement(52.0, 0.0, 5.0, 1))
        engine.reset()
        assertNull(engine.estimate(seconds(1)))
        val next = engine.update(measurement(40.0, 30.0, 10.0, 2, PositionMeasurementSource.RADIO))
        assertEquals(PositionFusionUpdateStatus.INITIALIZED, next.status)
        assertEquals(setOf(PositionMeasurementSource.RADIO), next.estimate!!.sourcesUsed)
    }

    private fun measurement(
        latitude: Double,
        longitude: Double,
        uncertainty: Double,
        timestampSeconds: Long,
        source: PositionMeasurementSource = PositionMeasurementSource.GNSS
    ) = PositionMeasurement(
        source = source,
        latitude = latitude,
        longitude = longitude,
        horizontalUncertaintyMeters = uncertainty,
        elapsedRealtimeNanos = seconds(timestampSeconds)
    )

    private fun longitudeAfterEastwardTravel(longitude: Double, latitude: Double, distanceMeters: Double): Double {
        val scale = 6_371_000.0 * cos(latitude * PI / 180.0)
        return longitude + Math.toDegrees(distanceMeters / scale)
    }

    private fun seconds(value: Long) = value * 1_000_000_000L
}
