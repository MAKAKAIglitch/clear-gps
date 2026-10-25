package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterEngineDiagnosticsTest {
    @Test
    fun missingGnssDataProducesNeutralReadOnlyDiagnostics() {
        val engine = LocationFilterEngine()

        val diagnostics = engine.receptionDiagnostics(1_000_000_000L)

        assertEquals(GnssEnvironmentContext.UNKNOWN, diagnostics.environment.context)
        assertFalse(diagnostics.baselineDeviation.ready)
        assertTrue(diagnostics.environment.reasons.isEmpty())
        assertTrue(diagnostics.baselineDeviation.deviations.isEmpty())
    }
}
