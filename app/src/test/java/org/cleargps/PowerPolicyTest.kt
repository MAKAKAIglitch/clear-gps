package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {
    private val defaults = PowerPolicySettings()

    @Test
    fun activeFixIsNeverBlockedByBatteryOrDisplayState() {
        val result = PowerPolicy.evaluate(
            PowerTask.ACTIVE_FIX, defaults, PowerEnvironment(1, false, false, true)
        )
        assertTrue(result.allowed)
        assertEquals(null, result.blockReason)
    }

    @Test
    fun optionalWorkPausesAtOrBelowConfiguredBatteryThreshold() {
        val atThreshold = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults,
            PowerEnvironment(20, false, true, false)
        )
        val aboveThreshold = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults,
            PowerEnvironment(21, false, true, false)
        )
        assertFalse(atThreshold.allowed)
        assertEquals(PowerBlockReason.LOW_BATTERY, atThreshold.blockReason)
        assertTrue(aboveThreshold.allowed)
    }

    @Test
    fun optionalWorkPausesWhenScreenTurnsOffOnBattery() {
        val result = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults,
            PowerEnvironment(75, false, false, false)
        )
        assertFalse(result.allowed)
        assertEquals(PowerBlockReason.SCREEN_OFF, result.blockReason)
    }

    @Test
    fun disablingAppSaverAllowsOptionalWorkOnBatteryWithScreenOff() {
        val result = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults.copy(appSaverEnabled = false),
            PowerEnvironment(5, false, false, false)
        )
        assertTrue(result.allowed)
    }

    @Test
    fun systemPowerSaveModeBlocksOptionalWorkEvenWhenAppSaverIsDisabled() {
        val result = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults.copy(appSaverEnabled = false),
            PowerEnvironment(80, false, true, true)
        )
        assertFalse(result.allowed)
        assertEquals(PowerBlockReason.SYSTEM_POWER_SAVE, result.blockReason)
    }

    @Test
    fun chargingResumesOptionalWork() {
        val result = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults,
            PowerEnvironment(3, true, false, false)
        )
        assertTrue(result.allowed)
    }

    @Test
    fun unknownBatteryLevelDoesNotInventALowBatteryCondition() {
        val result = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults,
            PowerEnvironment(null, false, true, false)
        )
        assertTrue(result.allowed)
    }

    @Test
    fun thresholdIsClampedToSupportedRange() {
        val tooLow = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults.copy(lowBatteryThresholdPercent = -10),
            PowerEnvironment(6, false, true, false)
        )
        val tooHigh = PowerPolicy.evaluate(
            PowerTask.OPTIONAL_BACKGROUND, defaults.copy(lowBatteryThresholdPercent = 90),
            PowerEnvironment(51, false, true, false)
        )
        assertTrue(tooLow.allowed)
        assertFalse(tooHigh.allowed)
        assertEquals(5, PowerPolicy.MIN_LOW_BATTERY_THRESHOLD_PERCENT)
        assertEquals(50, PowerPolicy.MAX_LOW_BATTERY_THRESHOLD_PERCENT)
    }
}
