package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssReceptionDiagnosticsTest {
    private fun signal(at: Long = 10, visible: Int = 10, used: Int = 8, cn0: Double? = 34.0) =
        GnssSignalSnapshot(
            elapsedRealtimeNanos = at * 1_000_000_000L,
            satellitesVisible = visible,
            satellitesUsedInFix = used,
            meanCn0DbHz = cn0,
            cn0SpreadDb = 4.0,
            constellationCount = 3,
            strongSignalCount = used
        )

    private fun matureBaseline() = GnssDeviceBaselineSnapshot(
        acceptedSignalSnapshots = 40,
        acceptedAgcSnapshots = 0,
        firstSampleElapsedRealtimeNanos = null,
        lastSampleElapsedRealtimeNanos = null,
        meanCn0DbHz = GnssMetricBaseline(40, 38.0, 2.0),
        cn0SpreadDb = GnssMetricBaseline(40, 4.0, 1.0),
        visibleSatellites = GnssMetricBaseline(40, 10.0, 1.0),
        usedSatellites = GnssMetricBaseline(40, 8.0, 1.0),
        meanAgcDb = GnssMetricBaseline(0, null, null)
    )

    @Test
    fun diagnosticsExposeReceptionContextAndBaselineHintsTogether() {
        val detector = GnssSpoofingDetector()
        assertTrue(detector.restoreDeviceBaseline(matureBaseline()))
        detector.observeGnssSignal(signal(visible = 5, used = 3, cn0 = 25.0))

        val diagnostics = detector.receptionDiagnostics(10_500_000_000L)

        assertEquals(GnssEnvironmentContext.POSSIBLE_SEVERE_OBSTRUCTION, diagnostics.environment.context)
        assertTrue(diagnostics.baselineDeviation.ready)
        assertTrue(diagnostics.baselineDeviation.deviations.isNotEmpty())
    }

    @Test
    fun diagnosticsDoNotChangeSpoofingAssessmentOrDetectorState() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(signal())
        val gps = PositionFix(
            provider = "gps",
            latitude = 52.0,
            longitude = 40.0,
            accuracyMeters = 8.0,
            elapsedRealtimeNanos = 10_000_000_000L
        )

        val before = detector.assess(gps, null, false, 10_500_000_000L, advanceState = false)
        repeat(3) { detector.receptionDiagnostics(10_500_000_000L) }
        val after = detector.assess(gps, null, false, 10_500_000_000L, advanceState = false)

        assertEquals(before.score, after.score)
        assertEquals(before.confirmed, after.confirmed)
        assertEquals(before.reasons, after.reasons)
    }

    @Test
    fun missingSignalIsUnknownAndDoesNotInventBaselineReadiness() {
        val diagnostics = GnssSpoofingDetector().receptionDiagnostics(10_500_000_000L)
        assertEquals(GnssEnvironmentContext.UNKNOWN, diagnostics.environment.context)
        assertFalse(diagnostics.baselineDeviation.ready)
        assertTrue(diagnostics.baselineDeviation.deviations.isEmpty())
    }

    @Test
    fun staleSignalProducesUnknownEnvironmentAndNoBaselineHints() {
        val detector = GnssSpoofingDetector()
        detector.observeGnssSignal(signal(at = 1))
        val diagnostics = detector.receptionDiagnostics(20_000_000_000L)
        assertEquals(GnssEnvironmentContext.UNKNOWN, diagnostics.environment.context)
        assertTrue(diagnostics.baselineDeviation.deviations.isEmpty())
    }
}
