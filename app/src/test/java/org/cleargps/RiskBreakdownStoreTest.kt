package org.cleargps

import android.content.Context
import org.cleargps.core.RiskBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RiskBreakdownStoreTest {

    @Test
    fun persistsAirportProximityAsAWeakIndependentSignal() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("risk-breakdown-airport-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val expected = RiskBreakdown(
            available = true,
            airportProximityEvaluated = true,
            airportProximityScore = 10,
            airportProximityReason = "Near airport; weak context only",
            combinedBeforeZone = 10,
            rawCombinedScore = 10,
            finalScore = 10
        )
        RiskBreakdownStore.write(prefs, expected, 20_000_000_000L)

        val restored = RiskBreakdownStore.read(prefs, 20_000_000_000L)
        assertNotNull(restored)
        assertEquals(true, restored!!.airportProximityEvaluated)
        assertEquals(10, restored.airportProximityScore)
        assertEquals(expected.airportProximityReason, restored.airportProximityReason)
        prefs.edit().clear().commit()
    }

    @Test
    fun persistsObdSpeedEvidenceAndCorroborationBonus() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("risk-breakdown-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val expected = RiskBreakdown(
            available = true,
            obdSpeedEvaluated = true,
            obdSpeedScore = 35,
            obdSpeedReason = "Repeated physical GNSS / OBD-II speed disagreement",
            obdSpeedCorroborationBonus = 8,
            combinedBeforeZone = 43,
            rawCombinedScore = 43,
            finalScore = 43
        )
        RiskBreakdownStore.write(prefs, expected, 10_000_000_000L)

        val restored = RiskBreakdownStore.read(prefs, 10_000_000_000L)
        assertNotNull(restored)
        assertEquals(true, restored!!.obdSpeedEvaluated)
        assertEquals(35, restored.obdSpeedScore)
        assertEquals(expected.obdSpeedReason, restored.obdSpeedReason)
        assertEquals(8, restored.obdSpeedCorroborationBonus)
        assertEquals(43, restored.combinedBeforeZone)
        prefs.edit().clear().commit()
    }
}
