package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRecoveryPolicyTest {
    @Test
    fun healthyRecentHeartbeatDoesNotTriggerRecovery() {
        assertFalse(ServiceRecoveryPolicy.shouldAttemptRecovery(true, true, true, 180_000L, 150_000L))
    }

    @Test
    fun missingOrStaleHeartbeatTriggersRecovery() {
        assertTrue(ServiceRecoveryPolicy.shouldAttemptRecovery(true, true, true, 180_000L, 0L))
        assertTrue(ServiceRecoveryPolicy.shouldAttemptRecovery(true, true, true, 180_001L, 60_000L))
    }

    @Test
    fun recoveryRequiresPreviousUserIntentAndBothLocationPermissions() {
        assertFalse(ServiceRecoveryPolicy.shouldAttemptRecovery(false, true, true, 180_000L, 0L))
        assertFalse(ServiceRecoveryPolicy.shouldAttemptRecovery(true, false, true, 180_000L, 0L))
        assertFalse(ServiceRecoveryPolicy.shouldAttemptRecovery(true, true, false, 180_000L, 0L))
    }

    @Test
    fun taskRemovalWaitsForGraceAndRequiresNoPostRemovalHeartbeat() {
        assertFalse(ServiceRecoveryPolicy.heartbeatIsStale(54_999L, 9_000L, 10_000L))
        assertTrue(ServiceRecoveryPolicy.heartbeatIsStale(55_000L, 9_000L, 10_000L))
        assertFalse(ServiceRecoveryPolicy.heartbeatIsStale(55_000L, 40_000L, 10_000L))
    }

    @Test
    fun futureHeartbeatFromPreviousBootIsTreatedAsStale() {
        assertTrue(ServiceRecoveryPolicy.heartbeatIsStale(15_000L, 700_000L))
    }
}
