package org.cleargps.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class PositionFusionTimelineTest {
    @Test
    fun lateRadioMeasurementIsInsertedChronologicallyAndReplayed() {
        val timeline = PositionFusionTimeline()
        assertEquals(
            PositionFusionUpdateStatus.INITIALIZED,
            timeline.update(measurement(0.0, 1, PositionMeasurementSource.GNSS)).status
        )
        assertEquals(
            PositionFusionUpdateStatus.UPDATED,
            timeline.update(measurement(eastwardLongitude(16.0), 3, PositionMeasurementSource.GNSS)).status
        )

        val lateRadio = timeline.update(
            measurement(eastwardLongitude(8.0), 2, PositionMeasurementSource.RADIO)
        )

        assertEquals(PositionFusionUpdateStatus.REPLAYED_OUT_OF_ORDER, lateRadio.status)
        assertNotNull(lateRadio.estimate)
        assertEquals(3, lateRadio.estimate!!.acceptedMeasurementCount)
        assertEquals(
            setOf(PositionMeasurementSource.GNSS, PositionMeasurementSource.RADIO),
            lateRadio.estimate!!.sourcesUsed
        )
        assertEquals(3, timeline.retainedMeasurementCount())
        assertNotNull(timeline.estimate(seconds(3)))
    }

    @Test
    fun duplicateOrTooOldMeasurementsAreRejectedWithoutChangingTimeline() {
        val timeline = PositionFusionTimeline(historyWindowNanos = seconds(2))
        timeline.update(measurement(0.0, 1, PositionMeasurementSource.GNSS))
        timeline.update(measurement(eastwardLongitude(16.0), 3, PositionMeasurementSource.GNSS))

        val before = timeline.estimate(seconds(3))
        val duplicate = timeline.update(measurement(0.0, 3, PositionMeasurementSource.RADIO))
        val tooOld = timeline.update(measurement(eastwardLongitude(4.0), 1, PositionMeasurementSource.RADIO))
        val after = timeline.estimate(seconds(3))

        assertEquals(PositionFusionUpdateStatus.REJECTED, duplicate.status)
        assertEquals(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP, duplicate.rejectionReason)
        assertEquals(PositionFusionUpdateStatus.REJECTED, tooOld.status)
        assertEquals(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP, tooOld.rejectionReason)
        assertEquals(2, timeline.retainedMeasurementCount())
        assertNotNull(before)
        assertNotNull(after)
        assertEquals(before!!.latitude, after!!.latitude, 0.000000001)
        assertEquals(before.longitude, after.longitude, 0.000000001)
    }

    @Test
    fun invalidLateMeasurementDoesNotMutateRetainedState() {
        val timeline = PositionFusionTimeline()
        timeline.update(measurement(0.0, 1, PositionMeasurementSource.GNSS))
        timeline.update(measurement(eastwardLongitude(16.0), 3, PositionMeasurementSource.GNSS))
        val before = timeline.estimate(seconds(3))

        val result = timeline.update(
            PositionMeasurement(
                source = PositionMeasurementSource.RADIO,
                latitude = Double.NaN,
                longitude = 0.0,
                horizontalUncertaintyMeters = 10.0,
                elapsedRealtimeNanos = seconds(2)
            )
        )

        assertEquals(PositionFusionUpdateStatus.REJECTED, result.status)
        assertEquals(PositionFusionRejectionReason.INVALID_COORDINATES, result.rejectionReason)
        assertEquals(2, timeline.retainedMeasurementCount())
        assertEquals(before!!.latitude, timeline.estimate(seconds(3))!!.latitude, 0.000000001)
    }

    @Test
    fun resetClearsReplayHistoryAndFilterState() {
        val timeline = PositionFusionTimeline()
        timeline.update(measurement(0.0, 1, PositionMeasurementSource.GNSS))
        timeline.update(measurement(eastwardLongitude(8.0), 2, PositionMeasurementSource.RADIO))

        timeline.reset()

        assertEquals(0, timeline.retainedMeasurementCount())
        assertNull(timeline.estimate(seconds(2)))
        assertEquals(
            PositionFusionUpdateStatus.INITIALIZED,
            timeline.update(measurement(30.0, 10, PositionMeasurementSource.RADIO)).status
        )
        assertEquals(setOf(PositionMeasurementSource.RADIO), timeline.estimate(seconds(10))!!.sourcesUsed)
    }

    @Test
    fun replayHistoryHasAMaximumMeasurementCount() {
        val timeline = PositionFusionTimeline(maxMeasurements = 3)
        for (second in 1L..5L) {
            val update = timeline.update(
                measurement(eastwardLongitude(second.toDouble() * 4.0), second, PositionMeasurementSource.GNSS)
            )
            assertTrue(update.status != PositionFusionUpdateStatus.REJECTED)
        }

        assertEquals(3, timeline.retainedMeasurementCount())
        assertEquals(3, timeline.estimate(seconds(5))!!.acceptedMeasurementCount)
    }

    private fun measurement(
        longitude: Double,
        timestampSeconds: Long,
        source: PositionMeasurementSource
    ) = PositionMeasurement(
        source = source,
        latitude = 52.0,
        longitude = longitude,
        horizontalUncertaintyMeters = if (source == PositionMeasurementSource.RADIO) 10.0 else 5.0,
        elapsedRealtimeNanos = seconds(timestampSeconds)
    )

    private fun eastwardLongitude(distanceMeters: Double): Double {
        val scale = 6_371_000.0 * cos(Math.toRadians(52.0))
        return Math.toDegrees(distanceMeters / scale)
    }

    private fun seconds(value: Long) = value * 1_000_000_000L
}
