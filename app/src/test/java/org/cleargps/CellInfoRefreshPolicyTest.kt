package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellInfoRefreshPolicyTest {
    @Test
    fun firstRequestIsAllowed() {
        assertTrue(CellInfoRefreshPolicy.shouldRequestUpdate(10_000L, 0L))
    }

    @Test
    fun ordinaryScansRespectTheThirtySecondRefreshInterval() {
        assertFalse(CellInfoRefreshPolicy.shouldRequestUpdate(39_999L, 10_000L))
        assertTrue(CellInfoRefreshPolicy.shouldRequestUpdate(40_000L, 10_000L))
    }

    @Test
    fun explicitBeaconDbLookupCanAskForRefreshImmediately() {
        assertTrue(
            CellInfoRefreshPolicy.shouldRequestUpdate(
                nowElapsedMillis = 11_000L,
                lastRequestElapsedMillis = 10_000L,
                force = true
            )
        )
    }

    @Test
    fun elapsedRealtimeResetDoesNotLockRefreshesOut() {
        assertTrue(CellInfoRefreshPolicy.shouldRequestUpdate(500L, 10_000L))
    }
}
