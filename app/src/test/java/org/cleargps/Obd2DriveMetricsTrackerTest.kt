package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2DriveMetricsTrackerTest {
    private val base = 100_000_000_000L
    private fun sample(
        tracker: Obd2DriveMetricsTracker,
        speed: Int = 36,
        at: Long = base,
        now: Long = at + 100_000_000L
    ) = tracker.observe(speed, at, now)

    @Test fun integratesDistanceAndResetsOnDisconnect() {
        val tracker = Obd2DriveMetricsTracker()
        assertEquals(0.0, sample(tracker).sessionDistanceMeters, 0.001)
        val second = sample(tracker, at = base + 1_000_000_000L)
        assertEquals(10.0, second.sessionDistanceMeters, 0.001)
        assertEquals(2, second.acceptedSpeedSamples)
        tracker.reset()
        val reset = sample(tracker, at = base + 2_000_000_000L)
        assertEquals(0.0, reset.sessionDistanceMeters, 0.001)
        assertEquals(1, reset.acceptedSpeedSamples)
        assertNull(reset.longitudinalAccelerationMps2)
    }

    @Test fun derivesAccelerationAndRejectsAccelerationSpike() {
        val tracker = Obd2DriveMetricsTracker()
        sample(tracker, at = base)
        val next = sample(tracker, speed = 72, at = base + 2_000_000_000L)
        assertEquals(5.0, next.longitudinalAccelerationMps2!!, 0.001)
        assertEquals(30.0, next.sessionDistanceMeters, 0.001)
        val spike = sample(tracker, speed = 200, at = base + 3_000_000_000L)
        assertNull(spike.longitudinalAccelerationMps2)
        assertTrue(spike.sessionDistanceMeters > next.sessionDistanceMeters)
    }

    @Test fun ignoresDuplicateTimestamp() {
        val tracker = Obd2DriveMetricsTracker()
        sample(tracker, at = base)
        val first = sample(tracker, at = base + 1_000_000_000L)
        val duplicate = sample(tracker, speed = 200, at = base + 1_000_000_000L)
        assertEquals(10.0, first.sessionDistanceMeters, 0.001)
        assertEquals(first, duplicate)
    }

    @Test fun doesNotIntegrateAcrossLongGaps() {
        val tracker = Obd2DriveMetricsTracker()
        sample(tracker, at = base)
        val gap = sample(tracker, at = base + 9_000_000_000L)
        assertEquals(0.0, gap.sessionDistanceMeters, 0.001)
        val resumed = sample(tracker, at = base + 10_000_000_000L)
        assertEquals(10.0, resumed.sessionDistanceMeters, 0.001)
    }

    @Test fun staleFutureAndOutOfRangeValuesBreakTheChain() {
        val tracker = Obd2DriveMetricsTracker()
        sample(tracker, at = base)
        val stale = sample(tracker, at = base + 1_000_000_000L, now = base + 7_000_000_000L)
        assertEquals(0.0, stale.sessionDistanceMeters, 0.001)
        assertEquals(1, stale.acceptedSpeedSamples)
        sample(tracker, at = base + 8_000_000_000L, now = base + 7_000_000_000L)
        sample(tracker, speed = 251, at = base + 9_000_000_000L)
        val resumed = sample(tracker, at = base + 10_000_000_000L)
        assertEquals(0.0, resumed.sessionDistanceMeters, 0.001)
        assertEquals(2, resumed.acceptedSpeedSamples)
    }

    @Test fun stationarySamplesDoNotCreateDistance() {
        val tracker = Obd2DriveMetricsTracker()
        sample(tracker, speed = 0, at = base)
        val stopped = sample(tracker, speed = 0, at = base + 1_000_000_000L)
        assertEquals(0.0, stopped.sessionDistanceMeters, 0.001)
    }
}
