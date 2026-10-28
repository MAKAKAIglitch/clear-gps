package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SrtmElevationDiagnosticsTest {
    @Before
    fun resetStore() {
        SrtmElevationDiagnosticsLiveStore.clear()
    }

    @Test
    fun samplingPolicyRateLimitsLookupsAndRejectsNonMonotonicTime() {
        val first = 1_000_000_000L
        assertTrue(SrtmElevationDiagnosticSamplingPolicy.shouldSample(0L, first))
        assertFalse(SrtmElevationDiagnosticSamplingPolicy.shouldSample(first, first))
        assertFalse(
            SrtmElevationDiagnosticSamplingPolicy.shouldSample(
                first,
                first + SrtmElevationDiagnosticSamplingPolicy.MINIMUM_INTERVAL_NANOS - 1L
            )
        )
        assertTrue(
            SrtmElevationDiagnosticSamplingPolicy.shouldSample(
                first,
                first + SrtmElevationDiagnosticSamplingPolicy.MINIMUM_INTERVAL_NANOS
            )
        )
        assertFalse(SrtmElevationDiagnosticSamplingPolicy.shouldSample(first, first - 1L))
        assertFalse(SrtmElevationDiagnosticSamplingPolicy.shouldSample(0L, 0L))
    }

    @Test
    fun validSampleRetainsExplicitDatumAndExpiresAfterTwoMinutes() {
        val sampledAt = 10_000_000_000L
        SrtmElevationDiagnosticsLiveStore.update(
            SrtmHgtElevationSample(elevationMeters = 123.4),
            sampledAt
        )

        val snapshot = SrtmElevationDiagnosticsLiveStore.snapshot(sampledAt + 1_000_000_000L)!!
        assertEquals(123.4, snapshot.elevationMeters!!, 0.0)
        assertEquals(SrtmVerticalDatum.EGM96_GEOID, snapshot.verticalDatum)
        assertEquals(sampledAt, snapshot.sampledAtElapsedRealtimeNanos)

        assertNull(SrtmElevationDiagnosticsLiveStore.snapshot(sampledAt + 120_000_000_001L))
    }

    @Test
    fun completedLookupWithoutUsableTileIsNotReportedAsZeroElevation() {
        SrtmElevationDiagnosticsLiveStore.update(
            sample = null,
            sampledAtElapsedRealtimeNanos = 5_000_000_000L
        )

        val snapshot = SrtmElevationDiagnosticsLiveStore.snapshot(5_000_000_100L)!!
        assertNull(snapshot.elevationMeters)
        assertNull(snapshot.verticalDatum)
        assertEquals(5_000_000_000L, snapshot.sampledAtElapsedRealtimeNanos)
    }

    @Test
    fun invalidTimeIsIgnoredAndClearRemovesSnapshot() {
        SrtmElevationDiagnosticsLiveStore.update(
            SrtmHgtElevationSample(30.0),
            sampledAtElapsedRealtimeNanos = 0L
        )
        assertNull(SrtmElevationDiagnosticsLiveStore.snapshot(1_000_000L))

        SrtmElevationDiagnosticsLiveStore.update(
            SrtmHgtElevationSample(30.0),
            sampledAtElapsedRealtimeNanos = 1_000_000L
        )
        SrtmElevationDiagnosticsLiveStore.clear()
        assertNull(SrtmElevationDiagnosticsLiveStore.snapshot(2_000_000L))
    }
}
