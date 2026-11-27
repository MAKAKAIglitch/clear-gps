package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PremiumHapticsTest {
    @Test
    fun everyEventHasAValidShortWaveform() {
        PremiumHapticEvent.values().forEach { event ->
            val pattern = PremiumHaptics.patternFor(event, PremiumHaptics.DEFAULT_INTENSITY)
            assertEquals(pattern.timings.size, pattern.amplitudes.size)
            assertEquals(0L, pattern.timings.first())
            assertTrue(pattern.timings.all { it >= 0L })
            assertTrue(pattern.amplitudes.all { it in 0..255 })
            assertTrue(pattern.timings.sum() <= 250L)
            assertTrue(pattern.amplitudes.any { it > 0 })
        }
    }

    @Test
    fun strongerIntensityNeverReducesAnyPulseAmplitude() {
        PremiumHapticEvent.values().forEach { event ->
            val soft = PremiumHaptics.patternFor(event, 0.25f)
            val strong = PremiumHaptics.patternFor(event, 1f)
            assertEquals(soft.timings.toList(), strong.timings.toList())
            soft.amplitudes.indices.forEach { index ->
                assertTrue(strong.amplitudes[index] >= soft.amplitudes[index])
            }
        }
    }

    @Test
    fun eventSignaturesAreDistinct() {
        val tap = PremiumHaptics.patternFor(PremiumHapticEvent.TAP, 0.8f)
        val toggle = PremiumHaptics.patternFor(PremiumHapticEvent.TOGGLE, 0.8f)
        val fix = PremiumHaptics.patternFor(PremiumHapticEvent.FIX_ACQUIRED, 0.8f)
        assertNotEquals(tap.timings.toList(), toggle.timings.toList())
        assertNotEquals(toggle.timings.toList(), fix.timings.toList())
    }
}
