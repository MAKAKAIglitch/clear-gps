package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellSiteRecalculationRefreshPolicyTest {
    @Test
    fun recalculationUsesShortPowerGatedDelayAndRetryInterval() {
        assertEquals(60_000L, CellSiteRecalculationSchedulePolicy.INITIAL_DELAY_MILLIS)
        assertEquals(
            PowerPolicy.PAUSED_OPTIONAL_WORK_RECHECK_INTERVAL_MILLIS,
            CellSiteRecalculationSchedulePolicy.RETRY_DELAY_MILLIS
        )
    }

    @Test
    fun refreshesOnlyForANewerSuccessfulCalculation() {
        assertTrue(CellSiteRecalculationRefreshPolicy.shouldRefresh(0L, 1L))
        assertTrue(CellSiteRecalculationRefreshPolicy.shouldRefresh(100L, 101L))
        assertFalse(CellSiteRecalculationRefreshPolicy.shouldRefresh(100L, 100L))
        assertFalse(CellSiteRecalculationRefreshPolicy.shouldRefresh(101L, 100L))
    }
}
