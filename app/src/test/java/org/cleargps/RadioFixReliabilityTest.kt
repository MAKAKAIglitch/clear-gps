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

    @Test fun mixedFreshAndStaleRadioSourcesAreNotAllClassifiedAsCached() {
        // Fresh BLE is still new evidence even while Wi-Fi's Android cache has aged.
        assertFalse(
            RadioFixReliability.isAllRadioDataCached(
                wifiCount = 4, wifiAgeMillis = 25_000L,
                cellCount = 1, cellAgeMillis = 60_000L,
                bleCount = 2, bleAgeMillis = 2_000L
            )
        )
        // One fresh Wi-Fi scan is enough to keep the combined fix from being labelled
        // entirely cached; stale sources still add their own uncertainty penalty.
        assertFalse(
            RadioFixReliability.isAllRadioDataCached(
                wifiCount = 4, wifiAgeMillis = 5_000L,
                cellCount = 1, cellAgeMillis = 60_000L,
                bleCount = 0, bleAgeMillis = null
            )
        )
    }

    @Test fun radioFixIsCachedWhenEveryContributingSourceIsStaleOrUnknown() {
        assertTrue(
            RadioFixReliability.isAllRadioDataCached(
                wifiCount = 4, wifiAgeMillis = 25_000L,
                cellCount = 1, cellAgeMillis = 60_000L,
                bleCount = 2, bleAgeMillis = 10_000L
            )
        )
        assertTrue(
            RadioFixReliability.isAllRadioDataCached(
                wifiCount = 0, wifiAgeMillis = null,
                cellCount = 0, cellAgeMillis = null,
                bleCount = 0, bleAgeMillis = null
            )
        )
        assertTrue(
            RadioFixReliability.isAllRadioDataCached(
                wifiCount = 2, wifiAgeMillis = null,
                cellCount = 0, cellAgeMillis = null,
                bleCount = 0, bleAgeMillis = null
            )
        )
    }

    @Test fun staleCellInformationIncreasesUncertaintyAndMarksCache() {
        assertEquals(0.0, RadioFixReliability.cellAgePenaltyMeters(30_000L, 2, 4), 0.0)
        assertTrue(RadioFixReliability.cellAgePenaltyMeters(60_000L, 2, 4) > 0.0)
        assertTrue(
            RadioFixReliability.cellAgePenaltyMeters(60_000L, 2, 4) <
                RadioFixReliability.cellAgePenaltyMeters(60_000L, 4, 4)
        )
        assertEquals(1_000.0, RadioFixReliability.cellAgePenaltyMeters(600_000L, 4, 4), 0.0)
        assertEquals(0.0, RadioFixReliability.cellAgePenaltyMeters(null, 4, 4), 0.0)
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

    @Test fun mapKeepsApproximateRadioFixLongerThanMockRecovery() {
        assertTrue(RadioFixReliability.canDisplayLastRadioEstimateOnMap(100_000L, 400_000L, 2, 4_500.0))
        assertTrue(RadioFixReliability.canDisplayLastRadioEstimateOnMap(100_000L, 699_999L, 1, 50_000.0))
        assertFalse(RadioFixReliability.canDisplayLastRadioEstimateOnMap(100_000L, 700_001L, 3, 100.0))
        assertFalse(RadioFixReliability.canDisplayLastRadioEstimateOnMap(100_000L, 90_000L, 3, 100.0))
        assertFalse(RadioFixReliability.canDisplayLastRadioEstimateOnMap(100_000L, 110_000L, 0, 100.0))
        assertFalse(RadioFixReliability.canDisplayLastRadioEstimateOnMap(100_000L, 110_000L, 2, 50_001.0))
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

    @Test fun cachedEstimateCannotRefreshMockFixForever() {
        assertTrue(
            RadioFixReliability.canPublishRadioEstimate(
                estimateUpdatedAtMillis = 100_000L,
                nowWallMillis = 110_000L,
                fromCache = true,
                lastConfirmedAtElapsedMillis = 100_000L,
                nowElapsedMillis = 110_000L,
                updateIntervalMillis = 1_000L
            )
        )
        assertFalse(
            RadioFixReliability.canPublishRadioEstimate(
                estimateUpdatedAtMillis = 100_000L,
                nowWallMillis = 116_000L,
                fromCache = true,
                lastConfirmedAtElapsedMillis = 100_000L,
                nowElapsedMillis = 116_000L,
                updateIntervalMillis = 1_000L
            )
        )
        assertFalse(
            RadioFixReliability.canPublishRadioEstimate(
                estimateUpdatedAtMillis = 100_000L,
                nowWallMillis = 110_000L,
                fromCache = true,
                lastConfirmedAtElapsedMillis = 0L,
                nowElapsedMillis = 110_000L,
                updateIntervalMillis = 1_000L
            )
        )
    }

    @Test fun evenNonCachedEstimateMustHaveRecentEvidenceTimestamp() {
        assertTrue(
            RadioFixReliability.canPublishRadioEstimate(
                estimateUpdatedAtMillis = 100_000L,
                nowWallMillis = 145_000L,
                fromCache = false,
                lastConfirmedAtElapsedMillis = 0L,
                nowElapsedMillis = 145_000L,
                updateIntervalMillis = 1_000L
            )
        )
        assertFalse(
            RadioFixReliability.canPublishRadioEstimate(
                estimateUpdatedAtMillis = 100_000L,
                nowWallMillis = 145_001L,
                fromCache = false,
                lastConfirmedAtElapsedMillis = 0L,
                nowElapsedMillis = 145_001L,
                updateIntervalMillis = 1_000L
            )
        )
    }

    @Test fun mockPublicationTimeoutLeavesRoomForAndroidProviderJitter() {
        assertEquals(30_000L, RadioFixReliability.mockPublicationTimeoutMillis(1_000L))
        assertEquals(30_000L, RadioFixReliability.mockPublicationTimeoutMillis(10_000L))
        assertEquals(90_000L, RadioFixReliability.mockPublicationTimeoutMillis(30_000L))
        assertEquals(30_000L, RadioFixReliability.mockPublicationTimeoutMillis(0L))
        assertEquals(90_000L, RadioFixReliability.mockPublicationTimeoutMillis(60_000L))
    }
}
