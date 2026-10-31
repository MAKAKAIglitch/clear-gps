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
class SpoofZoneRadioDatabasePolicyTest {
    private lateinit var context: Context
    private lateinit var database: LocalRadioDatabase
    private lateinit var sqlite: SQLiteDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        database = LocalRadioDatabase(context)
        sqlite = database.writableDatabase
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) context.deleteDatabase(DB_NAME)
    }

    @Test
    fun blockedZoneScanDoesNotPersistRadioPointsOrObservations() {
        val recorded = database.recordScan(
            signals = listOf(
                RadioSignal("wifi", "aa:bb:cc:dd:ee:01", -51, 2412, "Home"),
                RadioSignal("cell", "lte:250:1:123:567890", -82)
            ),
            reference = RadioReference(52.0, 5.0, 10.0, "gps"),
            estimate = null,
            mode = "auto",
            wifiCount = 1,
            cellCount = 1,
            bleCount = 0,
            shouldRecord = true,
            allowRadioWrite = false
        )

        assertEquals(0, recorded)
        assertEquals(0, countRows("radio_points"))
        assertEquals(0, countRows("radio_observations"))
        assertEquals(1, countRows("scan_history"))
        assertEquals(
            0,
            sqlite.rawQuery("SELECT recorded FROM scan_history LIMIT 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        )
    }

    @Test
    fun radioPointsInsideExcludedAreaDoNotMatchOrProducePositionEstimate() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        insertLocatedWifiPoint(sourceId, "aa:bb:cc:dd:ee:11", 52.0001, 5.0001, now)
        insertLocatedWifiPoint(sourceId, "aa:bb:cc:dd:ee:12", 52.0002, 5.0002, now)
        val signals = listOf(
            RadioSignal("wifi", "aa:bb:cc:dd:ee:11", -53, 2412, "Airport-WiFi"),
            RadioSignal("wifi", "aa:bb:cc:dd:ee:12", -59, 2437, "Airport-WiFi")
        )
        val excluded: (Double, Double, Double?) -> Boolean = { latitude, longitude, _ ->
            latitude in 51.99..52.01 && longitude in 4.99..5.01
        }

        val matches = database.countMatchingPoints(
            signals = signals,
            useWifi = true,
            useCells = false,
            useBle = false,
            now = now,
            excludeCoordinate = excluded
        )
        val estimate = database.estimatePosition(
            signals = signals,
            useWifi = true,
            useCells = false,
            useBle = false,
            excludeCoordinate = excluded
        )

        assertEquals(0, matches.knownRadios)
        assertEquals(0, matches.usableCoordinateRadios)
        assertNull(estimate)
    }

    private fun insertLocatedWifiPoint(
        sourceId: Long,
        identifier: String,
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
                put("label", "Airport Wi-Fi")
                put("ssid", "Airport-WiFi")
                put("notes", "")
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", 10.0)
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

    private fun countRows(table: String): Int =
        sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
    }
}
