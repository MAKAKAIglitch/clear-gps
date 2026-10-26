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

    @Test fun radioSnapshotsRoundTripWithoutRawRadioIdentifiers() {
        val first = GnssReplayEvent.RadioSnapshot(1_300_000_000L, listOf(
            GnssReplayRadioObservation(1, GnssReplayRadioType.WIFI, -52, 2412, 12L),
            GnssReplayRadioObservation(2, GnssReplayRadioType.CELL, -91, null, 4_500L),
            GnssReplayRadioObservation(3, GnssReplayRadioType.BLE, -68, null, null)
        ))
        val second = GnssReplayEvent.RadioSnapshot(9_300_000_000L, listOf(
            GnssReplayRadioObservation(1, GnssReplayRadioType.WIFI, -57, 2412, 20L)
        ))
        val encoded = GnssReplayCodec.encodeDocument(listOf(first, second))
        assertTrue(encoded.startsWith(GnssReplayCodec.FORMAT_HEADER))
        assertFalse(encoded.contains("00:11:22:33:44:55"))
        assertFalse(encoded.contains("private-network-name"))
        val decoded = GnssReplayCodec.decode(encoded)
        assertNull(decoded.error)
        assertEquals(listOf(first, second), decoded.events)
        val summary = GnssReplayRunner().replay(decoded)
        assertEquals(2, summary.radioSnapshots)
        assertEquals(4, summary.radioObservations)
        val injected = encoded.replaceFirst("O|1300000000|1|WIFI", "O|1300000000|00:11:22:33:44:55|WIFI")
        val rejected = GnssReplayCodec.decode(injected)
        assertFalse(rejected.events.filterIsInstance<GnssReplayEvent.RadioSnapshot>().any { it.offsetNanos == 1_300_000_000L })
        assertTrue(rejected.skippedLines > 0)
    }

    @Test fun legacyVersionOneReplayRemainsReadable() {
        val v2 = GnssReplayCodec.encodeDocument(fixture())
        val legacy = v2.replaceFirst(GnssReplayCodec.FORMAT_HEADER, GnssReplayCodec.LEGACY_FORMAT_HEADER)
        val decoded = GnssReplayCodec.decode(legacy)
        assertNull(decoded.error)
        assertEquals(fixture().size, decoded.events.size)
    }

    @Test fun sensorSamplesRoundTripWithRelativeMonotonicOffsets() {
        val sensor = GnssReplayEvent.Sensor(
            2_500_000_000L, GnssReplaySensorType.ACCELEROMETER, true,
            0.12, -0.34, 9.71, null, null, null
        )
        val decoded = GnssReplayCodec.decode(GnssReplayCodec.encodeDocument(listOf(sensor)))
        assertEquals(null, decoded.error)
        assertEquals(listOf(sensor), decoded.events)
        val summary = GnssReplayRunner().replay(decoded)
        assertEquals(1, summary.sensorSamples)
    }

    @Test fun exportedReplayHasNoAbsoluteCoordinatesWallClockOrRadioIds() {
        val text=GnssReplayCodec.encodeDocument(fixture())
        assertTrue(text.startsWith(GnssReplayCodec.FORMAT_HEADER))
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

    @Test fun importedGnssLoggerCsvIsRelativeAndKeepsRawAndSensorRows() {
        val fixHeader = "# Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees,UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees,elapsedRealtimeNanos,VerticalAccuracyMeters,MockLocation"
        val statusHeader = "# Status,UnixTimeMillis,SignalCount,SignalIndex,ConstellationType,Svid,CarrierFrequencyHz,Cn0DbHz,AzimuthDegrees,ElevationDegrees,UsedInFix,HasAlmanacData,HasEphemerisData,BasebandCn0DbHz"
        val rawHeader = "# Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos"
        val raw = MutableList(37) { "" }
        raw[0]="Raw";raw[1]="1700000000000";raw[11]="5";raw[13]="35";raw[14]="79120";raw[15]="53"
        raw[16]="29.6";raw[17]="102.5";raw[18]="2.24";raw[22]="1575420030";raw[28]="1"
        raw[29]="-12.0";raw[30]="24.6";raw[36]="1000000000"
        val csv=listOf(
            fixHeader,
            "Fix,GPS,55.7558,37.6173,150,0,5,0,1700000000000,0,0,1000000000,3,0",
            "Fix,NLP,55.7559,37.6174,151,0,20,90,1700000001000,1,2,2000000000,5,0",
            statusHeader,
            "Status,1700000000000,2,0,1,5,1575420000,35,180,45,1,1,1,30",
            "Status,1700000000000,2,1,1,6,1575420000,30,120,60,1,1,1,28",
            rawHeader,raw.joinToString(","),
            "# UncalAccel,utcTimeMillis,elapsedRealtimeNanos,UncalAccelXMps2,UncalAccelYMps2,UncalAccelZMps2,BiasXMps2,BiasYMps2,BiasZMps2",
            "UncalAccel,1700000000000,1000000000,0.1,0.2,9.8,0.01,0.02,0.03",
            "# Gyro,utcTimeMillis,elapsedRealtimeNanos,GyroXRadPerSec,GyroYRadPerSec,GyroZRadPerSec",
            "Gyro,1700000000000,1000000000,0.001,0.002,0.003",
            "# UncalMag,utcTimeMillis,elapsedRealtimeNanos,UncalMagXMicroT,UncalMagYMicroT,UncalMagZMicroT,BiasXMicroT,BiasYMicroT,BiasZMicroT",
            "UncalMag,1700000000000,1000000000,20,30,40,1,2,3",
            "# OrientationDeg,utcTimeMillis,elapsedRealtimeNanos,yawDeg,rollDeg,pitchDeg",
            "OrientationDeg,1700000000000,1000000000,201,-5,-52",
            "Nav,3,769,1,-1,14,116"
        ).joinToString("\n")
        val result=GnssLoggerCsvImporter.decode(csv)
        assertNull(result.decoded.error)
        assertEquals(2,result.decoded.events.filterIsInstance<GnssReplayEvent.Position>().size)
        assertEquals(1,result.decoded.events.filterIsInstance<GnssReplayEvent.Rf>().size)
        assertEquals(1,result.decoded.events.filterIsInstance<GnssReplayEvent.Signal>().size)
        assertEquals(4,result.stats.sensorSamples)
        val gps=result.decoded.events.filterIsInstance<GnssReplayEvent.Position>().first()
        assertEquals(0.0,gps.eastMeters,0.001);assertEquals(0.0,gps.northMeters,0.001)
        val summary=GnssReplayRunner().replay(result.decoded)
        assertEquals(1,summary.gpsFixes);assertEquals(1,summary.networkFixes)
        assertEquals(1,summary.rawMeasurementRecords);assertEquals(4,summary.sensorSamples)
        val safe=GnssReplayCodec.encodeDocument(result.decoded.events)
        assertFalse(safe.contains("55.7558"));assertFalse(safe.contains("37.6173"))
        assertFalse(safe.contains("1700000000000"))
        assertTrue(safe.startsWith(GnssReplayCodec.FORMAT_HEADER))
    }

    @Test fun gnssLoggerImportRejectsUnboundedAndUnsupportedFiles() {
        assertNotNull(GnssLoggerCsvImporter.decode("x".repeat(GnssLoggerCsvImporter.MAX_IMPORT_BYTES+1)).decoded.error)
        assertNotNull(GnssLoggerCsvImporter.decode("# only comments\nNav,1,2,3").decoded.error)
    }

    @Test fun outOfOrderEventsAndOversizeInputsAreRejected() {
        var failed=false
        try { GnssReplayCodec.encodeDocument(listOf(fixture()[1],fixture()[0])) }
        catch (_: IllegalArgumentException) { failed=true }
        assertTrue(failed)
        assertNotNull(GnssReplayCodec.decode("x".repeat(GnssReplayCodec.MAX_BYTES+1)).error)
    }
}
