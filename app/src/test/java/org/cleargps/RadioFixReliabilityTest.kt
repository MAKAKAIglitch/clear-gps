package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFixReliabilityTest {
    @Test fun wifiScanRequestsAreThrottledInsteadOfHammeredEveryFixTick() {
        assertTrue(RadioFixReliability.shouldRequestWifiScan(10_000L, 0L))
        assertFalse(RadioFixReliability.shouldRequestWifiScan(20_000L, 10_000L))
        assertTrue(RadioFixReliability.shouldRequestWifiScan(40_000L, 10_000L))
        // A smaller elapsed-realtime value means the device clock was reset by reboot.
        assertTrue(RadioFixReliability.shouldRequestWifiScan(9_000L, 10_000L))
    }

    @Test fun cellInformationIsRejectedWhenTooOldOrFromFuture() {
        assertTrue(RadioFixReliability.isCellInfoFresh(100_000L, 100_000L))
        assertTrue(RadioFixReliability.isCellInfoFresh(100_000L, 100_000L + 300_000L))
        assertFalse(RadioFixReliability.isCellInfoFresh(100_000L, 400_001L))
        assertFalse(RadioFixReliability.isCellInfoFresh(0L, 100_000L))
        assertFalse(RadioFixReliability.isCellInfoFresh(100_001L, 100_000L))
    }

    @Test fun staleWifiIncreasesUncertaintyAndFreshWifiDoesNot() {
        assertEquals(0.0, RadioFixReliability.wifiAgePenaltyMeters(15_000L, 4, 4), 0.0)
        assertTrue(RadioFixReliability.wifiAgePenaltyMeters(25_000L, 4, 4) > 0.0)
        assertTrue(
            RadioFixReliability.wifiAgePenaltyMeters(25_000L, 2, 4) <
                RadioFixReliability.wifiAgePenaltyMeters(25_000L, 4, 4)
        )
        assertEquals(0.0, RadioFixReliability.wifiAgePenaltyMeters(null, 4, 4), 0.0)
    }

    @Test fun bleScanCooldownAppliesEvenWhenNoRecentCacheExists() {
        assertTrue(RadioFixReliability.shouldRequestBleScan(10_000L, 0L))
        assertFalse(RadioFixReliability.shouldRequestBleScan(14_000L, 10_000L))
        assertTrue(RadioFixReliability.shouldRequestBleScan(16_000L, 10_000L))
        assertTrue(RadioFixReliability.shouldRequestBleScan(9_000L, 10_000L))
    }

    @Test fun staleBleAlsoIncreasesUncertainty() {
        assertEquals(0.0, RadioFixReliability.bleAgePenaltyMeters(6_000L, 2, 2), 0.0)
        assertTrue(RadioFixReliability.bleAgePenaltyMeters(10_000L, 2, 2) > 0.0)
        assertTrue(
            RadioFixReliability.bleAgePenaltyMeters(10_000L, 1, 2) <
                RadioFixReliability.bleAgePenaltyMeters(10_000L, 2, 2)
        )
        assertEquals(0.0, RadioFixReliability.bleAgePenaltyMeters(null, 2, 2), 0.0)
    }

    @Test fun lastEstimateIsRetainedOnlyWhenRecentAndCredible() {
        assertTrue(RadioFixReliability.canRetainLastEstimate(100_000L, 145_000L, 2, 1_000.0))
        assertFalse(RadioFixReliability.canRetainLastEstimate(100_000L, 145_001L, 2, 100.0))
        assertFalse(RadioFixReliability.canRetainLastEstimate(100_000L, 110_000L, 1, 100.0))
        assertFalse(RadioFixReliability.canRetainLastEstimate(100_000L, 110_000L, 3, 1_001.0))
        assertFalse(RadioFixReliability.canRetainLastEstimate(100_000L, 90_000L, 3, 100.0))
        assertFalse(RadioFixReliability.canRetainLastEstimate(0L, 10_000L, 3, Double.NaN))
    }

    @Test fun lastRadioFixHasABoundedHoldWindow() {
        assertTrue(RadioFixReliability.shouldHoldLastRadioMockFix(100_000L, 115_000L, 1_000L))
        assertFalse(RadioFixReliability.shouldHoldLastRadioMockFix(100_000L, 115_001L, 1_000L))
        assertTrue(RadioFixReliability.shouldHoldLastRadioMockFix(100_000L, 160_000L, 30_000L))
        assertFalse(RadioFixReliability.shouldHoldLastRadioMockFix(100_000L, 160_001L, 30_000L))
        assertFalse(RadioFixReliability.shouldHoldLastRadioMockFix(0L, 100_000L, 1_000L))
    }

    @Test fun heldRadioFixAccuracyGrowsWithAgeAndIsBounded() {
        assertEquals(80f, RadioFixReliability.mockAccuracyWithAge(80f, 0L), 0f)
        assertTrue(RadioFixReliability.mockAccuracyWithAge(80f, 10_000L) > 80f)
        assertEquals(5_000f, RadioFixReliability.mockAccuracyWithAge(80f, 1_000_000L), 0f)
        assertEquals(100f, RadioFixReliability.mockAccuracyWithAge(Float.NaN, 0L), 0f)
    }
}
