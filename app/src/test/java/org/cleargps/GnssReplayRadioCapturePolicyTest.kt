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

    @Test fun radioIntervalNeverDropsBelowFifteenSeconds() {
        assertEquals(15_000L, GnssReplayRadioCapturePolicy.intervalMillis(1))
        assertEquals(15_000L, GnssReplayRadioCapturePolicy.intervalMillis(15))
        assertEquals(30_000L, GnssReplayRadioCapturePolicy.intervalMillis(30))
        assertEquals(15_000L, GnssReplayRadioCapturePolicy.intervalMillis(0))
        assertEquals(30_000L, GnssReplayRadioCapturePolicy.intervalMillis(99))
    }
}
