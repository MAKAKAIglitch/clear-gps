package org.cleargps

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenCellIdUsageBudgetTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }

    @Before fun setUp() = OpenCellIdUsageBudget.clearForTests(context)
    @After fun tearDown() = OpenCellIdUsageBudget.clearForTests(context)

    @Test fun reservesOnlyOneHundredRequestsPerUtcDay() {
        val now = 1_800_000_000_000L
        assertEquals(OpenCellIdBudgetBlock.NONE, OpenCellIdUsageBudget.tryStartBatch(context, now))
        repeat(OpenCellIdUsageBudget.DAILY_APP_BUDGET) { index ->
            assertTrue(OpenCellIdUsageBudget.reserveRequest(context, now + index))
        }
        assertFalse(OpenCellIdUsageBudget.reserveRequest(context, now + 1_000L))
        assertEquals(0, OpenCellIdUsageBudget.snapshot(context, now + 1_001L).requestsRemaining)
        assertEquals(OpenCellIdBudgetBlock.DAILY_BUDGET,
            OpenCellIdUsageBudget.tryStartBatch(context, now + 1_001L))
    }

    @Test fun enforcesCooldownBetweenBatches() {
        val now = 1_800_000_000_000L
        assertEquals(OpenCellIdBudgetBlock.NONE, OpenCellIdUsageBudget.tryStartBatch(context, now))
        assertEquals(OpenCellIdBudgetBlock.COOLDOWN,
            OpenCellIdUsageBudget.tryStartBatch(context, now + 1_000L))
        assertEquals(OpenCellIdBudgetBlock.NONE,
            OpenCellIdUsageBudget.tryStartBatch(context, now + 15L * 60L * 1_000L))
    }

    @Test fun serverQuotaBlockPersistsUntilNextUtcDay() {
        val now = 1_800_000_000_000L
        assertEquals(OpenCellIdBudgetBlock.NONE, OpenCellIdUsageBudget.tryStartBatch(context, now))
        OpenCellIdUsageBudget.markServerLimitReached(context, now)
        assertEquals(OpenCellIdBudgetBlock.SERVER_LIMIT,
            OpenCellIdUsageBudget.tryStartBatch(context, now + 20L * 60L * 1_000L))
        val nextDay = now + 24L * 60L * 60L * 1_000L
        assertEquals(OpenCellIdBudgetBlock.NONE, OpenCellIdUsageBudget.tryStartBatch(context, nextDay))
        assertFalse(OpenCellIdUsageBudget.snapshot(context, nextDay).serverLimitReached)
    }
}
