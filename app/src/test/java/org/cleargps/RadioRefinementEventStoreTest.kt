package org.cleargps

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioRefinementEventStoreTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }

    @After
    fun tearDown() = RadioRefinementEventStore.clear(context)

    @Test
    fun storesNewestResultsAndReadsThemBack() {
        RadioRefinementEventStore.clear(context)
        RadioRefinementEventStore.append(context, "BeaconDB", "success", "Уточнено 4 Wi-Fi-точки.", 1_800_000_000_000L)
        RadioRefinementEventStore.append(context, "OpenCellID", "info", "Ячейка не найдена.", 1_800_000_001_000L)

        val events = RadioRefinementEventStore.read(context)

        assertEquals(2, events.size)
        assertEquals("OpenCellID", events.first().provider)
        assertEquals("BeaconDB", events.last().provider)
        assertTrue(events.last().message.contains("4 Wi-Fi-точки"))
    }

    @Test
    fun retainsOnlyTheMostRecentTwoHundredEvents() {
        RadioRefinementEventStore.clear(context)
        repeat(205) { index ->
            RadioRefinementEventStore.append(
                context, "BeaconDB", "info", "Событие $index", 1_800_000_000_000L + index
            )
        }

        val events = RadioRefinementEventStore.read(context)

        assertEquals(200, events.size)
        assertTrue(events.first().message.contains("204"))
        assertTrue(events.last().message.contains("5"))
    }
}
