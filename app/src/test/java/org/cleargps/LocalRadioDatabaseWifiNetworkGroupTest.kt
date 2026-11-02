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
        context.deleteDatabase(DB_NAME)
        database = LocalRadioDatabase(context, DB_NAME)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) context.deleteDatabase(DB_NAME)
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

    companion object {
        private const val DB_NAME = "clear_gps_wifi_group_test.sqlite"
    }
}
