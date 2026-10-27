package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryProfilePolicyTest {
    @Test
    fun normalProfilePreservesTheExistingConfiguredIntervalAndMovementRule() {
        assertEquals(15, BatteryProfilePolicy.intervalSeconds(15, null, true, BatteryProfile.NORMAL))
        assertEquals(7, BatteryProfilePolicy.intervalSeconds(15, 30f, true, BatteryProfile.NORMAL))
        assertEquals(15, BatteryProfilePolicy.intervalSeconds(15, 30f, false, BatteryProfile.NORMAL))
    }

    @Test
    fun ecoDoublesTheIntervalAndStillAppliesMovementAdaptationFirst() {
        assertEquals(30, BatteryProfilePolicy.intervalSeconds(15, null, true, BatteryProfile.ECO))
        assertEquals(14, BatteryProfilePolicy.intervalSeconds(15, 30f, true, BatteryProfile.ECO))
        assertEquals(60, BatteryProfilePolicy.profiledIntervalSeconds(30, BatteryProfile.ECO))
    }

    @Test
    fun maxHalvesTheIntervalWithAnOneSecondFloor() {
        assertEquals(7, BatteryProfilePolicy.profiledIntervalSeconds(15, BatteryProfile.MAX))
        assertEquals(3, BatteryProfilePolicy.intervalSeconds(15, 30f, true, BatteryProfile.MAX))
        assertEquals(1, BatteryProfilePolicy.profiledIntervalSeconds(1, BatteryProfile.MAX))
    }

    @Test
    fun invalidSpeedDoesNotTriggerMovementAdaptationAndInputsAreBounded() {
        assertEquals(15, BatteryProfilePolicy.intervalSeconds(15, Float.NaN, true, BatteryProfile.NORMAL))
        assertEquals(30, BatteryProfilePolicy.profiledIntervalSeconds(99, BatteryProfile.NORMAL))
        assertEquals(2, BatteryProfilePolicy.profiledIntervalSeconds(0, BatteryProfile.ECO))
    }

    @Test
    fun unknownStoredProfileDefaultsToNormal() {
        assertEquals(BatteryProfile.NORMAL, BatteryProfile.fromKey(null))
        assertEquals(BatteryProfile.NORMAL, BatteryProfile.fromKey("unsupported"))
        assertEquals(BatteryProfile.ECO, BatteryProfile.fromKey("eco"))
        assertEquals(false, BatteryProfile.isKnownKey("turbo"))
    }
}
