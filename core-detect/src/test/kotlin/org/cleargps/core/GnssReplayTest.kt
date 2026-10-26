package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssReplayTest {
    private fun fixture() = listOf(
        GnssReplayEvent.Signal(1_000_000_000L, GnssSignalSnapshot(1L,2,2,34.0,2.5,2,1),
            listOf(GnssReplaySatellite(1,5,35.2,44.0,160.0,true,1_575_420_000.0,31.0))),
        GnssReplayEvent.Rf(1_100_000_000L, GnssRfSnapshot(1L,1,1,35.2,-12.0,1),
            listOf(GnssReplayMeasurement(1,5,35.2,1_575_420_000.0,-12.0,-500.0,0.5,123_000_000L,12L,7))),
        GnssReplayEvent.Position(1_200_000_000L,GnssReplayPositionSource.GPS,0.0,0.0,5.0,
            0.0,0.5,null,null,false,0,"NORMAL","GPS"),
        GnssReplayEvent.Position(2_200_000_000L,GnssReplayPositionSource.GPS,1_000.0,0.0,5.0,
            0.0,0.5,null,null,false,90,"SUSPECT","GPS")
    )

    @Test fun versionedFormatRoundTripsAggregateAndRawGnssFields() {
        val decoded=GnssReplayCodec.decode(GnssReplayCodec.encodeDocument(fixture()))
        assertNull(decoded.error); assertEquals(0,decoded.skippedLines); assertTrue(decoded.hasFooter)
        assertEquals(4,decoded.events.size)
        val signal=decoded.events[0] as GnssReplayEvent.Signal
        assertEquals(1,signal.satellites.size); assertEquals(5,signal.satellites.single().svid)
        val rf=decoded.events[1] as GnssReplayEvent.Rf
        assertEquals(1,rf.measurements.size)
        assertEquals(-500.0,rf.measurements.single().pseudorangeRateMetersPerSecond!!,0.0)
        assertEquals(1_000.0,(decoded.events[3] as GnssReplayEvent.Position).eastMeters,0.0)
    }

    @Test fun exportedReplayHasNoAbsoluteCoordinatesWallClockOrRadioIds() {
        val text=GnssReplayCodec.encodeDocument(fixture())
        assertTrue(text.startsWith("CLEAR_GPS_REPLAY|1|ENU_RELATIVE|OFFSET_ONLY"))
        assertFalse(text.contains("55.7558")); assertFalse(text.contains("37.6173"))
        assertFalse(text.contains("SSID")); assertFalse(text.contains("BSSID"))
        assertFalse(text.contains("timestampEpochMillis")); assertTrue(text.contains("OFFSET_ONLY"))
    }

    @Test fun malformedRowsAreSkippedAndUnsupportedHeaderIsRejected() {
        val valid=GnssReplayCodec.encodeDocument(fixture())
        val damaged=valid.replace("P|2200000000|GPS|1000.0","P|oops|GPS|1000.0")
        val decoded=GnssReplayCodec.decode(damaged)
        assertNull(decoded.error); assertTrue(decoded.skippedLines >= 1)
        val unsupported=GnssReplayCodec.decode(valid.replaceFirst(GnssReplayCodec.FORMAT_HEADER,"GNSSLOGGER|0"))
        assertNotNull(unsupported.error); assertTrue(unsupported.events.isEmpty())
    }

    @Test fun replayRunsDetectorAgainstRelativeTrack() {
        val summary=GnssReplayRunner().replay(GnssReplayCodec.decode(GnssReplayCodec.encodeDocument(fixture())))
        assertNull(summary.error); assertEquals(2,summary.gpsFixes)
        assertEquals(1,summary.signalFrames); assertEquals(1,summary.rfFrames)
        assertEquals(1,summary.rawSatelliteRecords); assertEquals(1,summary.rawMeasurementRecords)
        assertEquals(2,summary.evaluatedFixes); assertTrue(summary.peakRiskScore >= 75)
    }

    @Test fun outOfOrderEventsAndOversizeInputsAreRejected() {
        var failed=false
        try { GnssReplayCodec.encodeDocument(listOf(fixture()[1],fixture()[0])) }
        catch (_: IllegalArgumentException) { failed=true }
        assertTrue(failed)
        assertNotNull(GnssReplayCodec.decode("x".repeat(GnssReplayCodec.MAX_BYTES+1)).error)
    }
}
