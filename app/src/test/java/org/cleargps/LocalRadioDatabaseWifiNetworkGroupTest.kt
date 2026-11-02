package org.cleargps

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalRadioDatabaseWifiNetworkGroupTest {
    private lateinit var context: Context
    private lateinit var database: LocalRadioDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // These tests cover grouping behavior, not file-backed WAL behavior.
        database = LocalRadioDatabase(context, null)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun manuallyGroupingWifiPointsKeepsEveryBssidAndCanBeUndone() {
        database.recordScan(
            listOf(
                RadioSignal("wifi", "aa:bb:cc:00:00:01", -55, 2412, "Home"),
                RadioSignal("wifi", "aa:bb:cc:00:00:02", -62, 5180, "Home")
            ),
            reference = null,
            estimate = null,
            mode = "test",
            wifiCount = 2,
            cellCount = 0,
            bleCount = 0,
            shouldRecord = true
        )
        val first = database.findWifiPointByIdentifier("aa:bb:cc:00:00:01")
        val second = database.findWifiPointByIdentifier("aa:bb:cc:00:00:02")
        assertNotNull(first)
        assertNotNull(second)

        val groupId = database.groupWifiPoints(listOf(first!!.id, second!!.id))
        assertNotNull(groupId)
        val group = database.findWifiNetworkGroup(first.id)
        assertNotNull(group)
        assertEquals(setOf(first.id, second.id), group!!.members.map { it.id }.toSet())
        assertEquals(groupId, database.findWifiNetworkGroup(second.id)?.id)

        assertTrue(database.removeWifiPointFromNetworkGroup(first.id))
        assertNull(database.findWifiNetworkGroup(first.id))
        assertNull(database.findWifiNetworkGroup(second.id))
        assertFalse(database.removeWifiPointFromNetworkGroup(first.id))

        // Removing the relationship does not delete either underlying radio record.
        assertNotNull(database.findPoint(first.id))
        assertNotNull(database.findPoint(second.id))
    }

    @Test
    fun sameBssidObservedOnTwoFrequenciesStaysOnePointWithBothFrequencyObservations() {
        database.recordScan(
            listOf(
                RadioSignal("wifi", "a8:bb:cc:00:00:01", -55, 2412, "DualBand"),
                RadioSignal("wifi", "a8:bb:cc:00:00:01", -62, 5180, "DualBand")
            ),
            reference = null,
            estimate = null,
            mode = "test",
            wifiCount = 1,
            cellCount = 0,
            bleCount = 0,
            shouldRecord = true
        )

        val point = database.findWifiPointByIdentifier("a8:bb:cc:00:00:01")!!
        val observations = database.listObservations(point)

        assertEquals(1, database.pointCount())
        assertEquals(1, point.sightings)
        assertEquals(setOf(2412, 5180), observations.map { it.frequency }.toSet())
        assertEquals(2, observations.size)
    }

    @Test
    fun groupingRejectsCellAndBleRecords() {
        database.recordScan(
            listOf(
                RadioSignal("wifi", "aa:bb:cc:00:00:01", -55, 2412, "Home"),
                RadioSignal("ble", "aa:bb:cc:00:00:02", -62)
            ),
            reference = null,
            estimate = null,
            mode = "test",
            wifiCount = 1,
            cellCount = 0,
            bleCount = 1,
            shouldRecord = true
        )
        val first = database.findWifiPointByIdentifier("aa:bb:cc:00:00:01")!!
        assertNull(database.groupWifiPoints(listOf(first.id, 999_999L)))
    }

}
