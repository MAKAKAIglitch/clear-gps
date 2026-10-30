package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRadioDatabaseRefreshPolicyTest {
    @Test
    fun snapshotIsNotCollectedAgainBeforeThirtySeconds() {
        assertTrue(ExternalRadioDatabaseRefreshPolicy.shouldCaptureSnapshot(10_000L, 0L))
        assertFalse(ExternalRadioDatabaseRefreshPolicy.shouldCaptureSnapshot(39_999L, 10_000L))
        assertTrue(ExternalRadioDatabaseRefreshPolicy.shouldCaptureSnapshot(40_000L, 10_000L))
    }

    @Test
    fun changedBeaconIdentityWaitsForGlobalCooldown() {
        val state = BeaconDbAutoRefreshState("old", 100_000L, 100_000L)
        assertFalse(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "new", 219_999L))
        assertTrue(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "new", 220_000L))
    }

    @Test
    fun unchangedBeaconIdentityRefreshesOnlyAfterFifteenMinutes() {
        val state = BeaconDbAutoRefreshState("same", 100_000L, 100_000L)
        assertFalse(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "same", 999_999L))
        assertTrue(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "same", 1_000_000L))
    }

    @Test
    fun failedBeaconLookupUsesLongerRetryCooldown() {
        val state = BeaconDbAutoRefreshState("same", 100_000L, 0L)
        assertFalse(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "same", 399_999L))
        assertTrue(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "same", 400_000L))
    }

    @Test
    fun invalidClockOrEmptyFingerprintNeverTriggersLookup() {
        val state = BeaconDbAutoRefreshState(null, 0L, 0L)
        assertFalse(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "", 1L))
        assertFalse(ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, "abc", 0L))
    }
}
