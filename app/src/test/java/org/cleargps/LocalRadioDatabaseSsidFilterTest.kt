package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
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
class LocalRadioDatabaseSsidFilterTest {
    private lateinit var context: Context
    private lateinit var database: LocalRadioDatabase
    private lateinit var sqlite: SQLiteDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ClearGpsPreferences.KEY_WIFI_SSID_BLACKLIST, "70mai*")
            .commit()
        database = LocalRadioDatabase(context)
        sqlite = database.writableDatabase
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            context.deleteDatabase(DB_NAME)
            context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(ClearGpsPreferences.KEY_WIFI_SSID_BLACKLIST)
                .commit()
        }
    }

    @Test
    fun recordingExcludesBlacklistedWifiButKeepsOtherWifiAndCellSignals() {
        val signals = listOf(
            RadioSignal("wifi", "aa:bb:cc:dd:ee:01", -61, 2412, "70mai_CAM"),
            RadioSignal("wifi", "aa:bb:cc:dd:ee:02", -72, 2437, "Home-WiFi"),
            // The SSID policy is deliberately Wi-Fi-only, never a generic radio-name filter.
            RadioSignal("cell", "lte:250:1:123:567890", -87, 0, "70mai-cell")
        )

        val recorded = database.recordScan(
            signals = signals,
            reference = RadioReference(52.274, 40.452, 12.0, "manual-reference"),
            estimate = null,
            mode = "auto",
            wifiCount = 2,
            cellCount = 1,
            bleCount = 0,
            shouldRecord = true
        )

        assertEquals(2, recorded)
        assertEquals(0, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?", "wifi", "aa:bb:cc:dd:ee:01"
        ))
        assertEquals(0, countRows(
            "radio_points", "radio_type = ? AND identifier = ?", "wifi", "aa:bb:cc:dd:ee:01"
        ))
        assertEquals(1, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?", "wifi", "aa:bb:cc:dd:ee:02"
        ))
        assertEquals(1, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?", "cell", "lte:250:1:123:567890"
        ))
    }

    @Test
    fun matchingAndPositionEstimateIgnoreBlacklistedSsidEvenIfPointAlreadyExists() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        insertLocatedWifiPoint(sourceId, "aa:bb:cc:dd:ee:11", "70mai_CAM_1", 52.270, 40.450, now)
        insertLocatedWifiPoint(sourceId, "aa:bb:cc:dd:ee:12", "70MAI-CAM_2", 52.275, 40.455, now)

        val currentSignals = listOf(
            RadioSignal("wifi", "aa:bb:cc:dd:ee:11", -53, 2412, "70mai_CAM_1"),
            RadioSignal("wifi", "aa:bb:cc:dd:ee:12", -59, 2437, "70MAI-CAM_2"),
            RadioSignal("wifi", "aa:bb:cc:dd:ee:13", -66, 2462, "Home-WiFi")
        )

        val matches = database.countMatchingPoints(
            signals = currentSignals,
            useWifi = true,
            useCells = false,
            useBle = false,
            now = now
        )

        assertEquals(0, matches.knownRadios)
        assertEquals(0, matches.usableCoordinateRadios)
        assertNull(database.estimatePosition(currentSignals, useWifi = true, useCells = false, useBle = false))
    }

    private fun insertLocatedWifiPoint(
        sourceId: Long,
        identifier: String,
        ssid: String,
        latitude: Double,
        longitude: Double,
        observedAt: Long
    ) {
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("label", ssid)
                put("ssid", ssid)
                put("notes", "")
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", 15.0)
                put("last_rssi", -55)
                put("peak_rssi", -50)
                put("frequency", 2412)
                put("first_seen", observedAt - 1_000L)
                put("last_seen", observedAt)
                put("sightings", 2)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
                put("position_observed_at", observedAt)
            }
        )
    }

    private fun countRows(table: String, where: String, vararg args: String): Int =
        sqlite.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
    }
}
