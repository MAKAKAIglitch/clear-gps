package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssReplayRadioCapturePolicyTest {
    @Test fun captureRequiresMonitoringRecordingAndAutoMode() {
        assertTrue(GnssReplayRadioCapturePolicy.shouldCapture(true, true, "auto"))
        assertFalse(GnssReplayRadioCapturePolicy.shouldCapture(false, true, "auto"))
        assertFalse(GnssReplayRadioCapturePolicy.shouldCapture(true, false, "auto"))
        assertFalse(GnssReplayRadioCapturePolicy.shouldCapture(true, true, "gps"))
        assertFalse(GnssReplayRadioCapturePolicy.shouldCapture(true, true, "radio"))
        assertFalse(GnssReplayRadioCapturePolicy.shouldCapture(true, true, "radio_obd2"))
        assertFalse(GnssReplayRadioCapturePolicy.shouldCapture(true, true, null))
    }

    @Test fun recorderRateLimitAppliesEvenWhenCallerIsNotPeriodicService() {
        val t0 = 1_000_000_000L
        assertTrue(GnssReplayRadioCapturePolicy.shouldRecordFrame(0L, t0, false))
        assertFalse(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0 + 14_999_999_999L, false))
        assertTrue(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0 + 15_000_000_000L, false))
        assertFalse(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0 - 1L, false))
        assertFalse(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0, false))
        assertFalse(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0 + 15_000_000_000L, true))
        assertFalse(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0 + 29_999_999_999L, true))
        assertTrue(GnssReplayRadioCapturePolicy.shouldRecordFrame(t0, t0 + 30_000_000_000L, true))
    }

    @Test fun radioIntervalNeverDropsBelowFifteenSeconds() {
        assertEquals(15_000L, GnssReplayRadioCapturePolicy.intervalMillis(1))
        assertEquals(15_000L, GnssReplayRadioCapturePolicy.intervalMillis(15))
        assertEquals(30_000L, GnssReplayRadioCapturePolicy.intervalMillis(30))
        assertEquals(15_000L, GnssReplayRadioCapturePolicy.intervalMillis(0))
        assertEquals(30_000L, GnssReplayRadioCapturePolicy.intervalMillis(99))
    }
}
