package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMapPointLimitPolicyTest {
    @Test fun choicesAreIndependentAndReachFiveHundredThousand() {
        assertEquals(
            listOf(5_000, 10_000, 20_000, 50_000, 100_000, 250_000, 500_000),
            RadioMapPointLimitPolicy.LIMIT_OPTIONS
        )
        assertEquals(500_000, RadioMapPointLimitPolicy.MAX_LIMIT)
        assertEquals(50_000, RadioMapPointLimitPolicy.DEFAULT_LIMIT)
        assertEquals(500_000, RadioMapPointLimitPolicy.normalizeLimit(500_000))
    }

    @Test fun unsupportedValuesUseTheDefault() {
        assertEquals(50_000, RadioMapPointLimitPolicy.normalizeLimit(-1))
        assertEquals(50_000, RadioMapPointLimitPolicy.normalizeLimit(500_001))
        assertEquals(50_000, RadioMapPointLimitPolicy.normalizeLimit(75_000))
    }

    @Test fun allLimitsHaveReadableLabels() {
        assertEquals("5 тыс.", RadioMapPointLimitPolicy.label(5_000))
        assertEquals("50 тыс.", RadioMapPointLimitPolicy.label(50_000))
        assertEquals("100 тыс.", RadioMapPointLimitPolicy.label(100_000))
        assertEquals("250 тыс.", RadioMapPointLimitPolicy.label(250_000))
        assertEquals("500 тыс.", RadioMapPointLimitPolicy.label(500_000))
    }

    @Test fun mapLimitIsSharedSoRecentBleCannotHideWifiAndCells() {
        val limits = RadioMapPointLimitPolicy.allocatePerTypeLimits(
            mapOf("ble" to 100_000, "wifi" to 120, "cell" to 80),
            300
        )
        assertEquals(100, limits["wifi"])
        assertEquals(80, limits["cell"])
        assertEquals(120, limits["ble"])
        assertEquals(300, limits.values.sum())
    }

    @Test fun mapLimitLoadsEveryPointWhenDatabaseIsSmallerThanCap() {
        val limits = RadioMapPointLimitPolicy.allocatePerTypeLimits(
            mapOf("ble" to 4_000, "wifi" to 12, "cell" to 8),
            5_000
        )
        assertEquals(4_000, limits["ble"])
        assertEquals(12, limits["wifi"])
        assertEquals(8, limits["cell"])
        assertEquals(4_020, limits.values.sum())
    }

    @Test fun mapLimitNeverAllocatesMoreRowsThanExistOrConfiguredCap() {
        val limits = RadioMapPointLimitPolicy.allocatePerTypeLimits(
            mapOf("ble" to 900_000, "wifi" to 2, "cell" to 1),
            500_000
        )
        assertEquals(2, limits["wifi"])
        assertEquals(1, limits["cell"])
        assertEquals(500_000, limits.values.sum())
        assertTrue(limits.values.all { it >= 0 })
    }

}
