package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalRadioDatabaseBatchLookupTest {
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
    fun batchLookupPreservesLegacyMacAndSourceSegmentTrust() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        val dayKey = sqlite.rawQuery(
            "SELECT strftime('%Y-%m-%d', ? / 1000, 'unixepoch', 'localtime')",
            arrayOf(now.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

        sqlite.update(
            "radio_sources",
            ContentValues().apply { put("trust_weight", 0.8) },
            "id = ?",
            arrayOf(sourceId.toString())
        )
        insertSegment(sourceId, dayKey, "wifi", enabled = true, weight = 0.5)
        insertSegment(sourceId, dayKey, "cell", enabled = false, weight = 1.0)

        insertPoint(
            type = "wifi",
            identifier = "aabbccddee01",
            sourceId = sourceId,
            latitude = 52.0000,
            longitude = 4.0000,
            observedAt = now
        )
        insertPoint(
            type = "cell",
            identifier = "lte:250:1:123:456789",
            sourceId = sourceId,
            latitude = 52.0001,
            longitude = 4.0001,
            observedAt = now
        )
        insertPoint(
            type = "ble",
            identifier = "aa:bb:cc:dd:ee:03",
            sourceId = null,
            latitude = 52.00005,
            longitude = 4.00005,
            observedAt = now
        )

        val signals = listOf(
            RadioSignal("wifi", "aa:bb:cc:dd:ee:01", -51, 2412, "Home"),
            RadioSignal("cell", "lte:250:1:123:456789", -82, 0),
            RadioSignal("ble", "aa:bb:cc:dd:ee:03", -64, 0)
        )
        val counts = database.countMatchingPoints(
            signals = signals,
            useWifi = true,
            useCells = true,
            useBle = true,
            now = now
        )

        assertEquals(3, counts.knownRadios)
        assertEquals(2, counts.usableCoordinateRadios)

        val estimate = database.estimatePosition(
            signals = signals,
            useWifi = true,
            useCells = true,
            useBle = true
        )
        assertNotNull(estimate)
        assertEquals(2, estimate!!.matchedCount)
        assertEquals(setOf("wifi", "ble"), estimate.matchedRadioTypes)
        assertTrue(estimate.accuracyMeters > 0.0)
    }

    @Test
    fun exactIdentityQueryUsesExistingCompositeIndex() {
        val details = sqlite.rawQuery(
            "EXPLAIN QUERY PLAN SELECT * FROM radio_points " +
                "WHERE radio_type = ? AND identifier = ? LIMIT 1",
            arrayOf("wifi", "aa:bb:cc:dd:ee:01")
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3).orEmpty())
            }.joinToString(" ")
        }

        assertTrue("Expected composite identity index, got: $details", details.contains("INDEX", ignoreCase = true))
        assertTrue(details.contains("radio_type", ignoreCase = true))
        assertTrue(details.contains("identifier", ignoreCase = true))
    }

    private fun insertSegment(
        sourceId: Long,
        dayKey: String,
        radioType: String,
        enabled: Boolean,
        weight: Double
    ) {
        sqlite.insertOrThrow(
            "radio_source_segment_settings",
            null,
            ContentValues().apply {
                put("source_id", sourceId)
                put("day_key", dayKey)
                put("radio_type", radioType)
                put("enabled", if (enabled) 1 else 0)
                put("trust_weight", weight)
            }
        )
    }

    private fun insertPoint(
        type: String,
        identifier: String,
        sourceId: Long?,
        latitude: Double,
        longitude: Double,
        observedAt: Long
    ) {
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", type)
                put("identifier", identifier)
                put("label", "")
                put("ssid", "")
                put("notes", "")
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", 20.0)
                put("last_rssi", -60)
                put("peak_rssi", -50)
                put("frequency", 0)
                put("first_seen", observedAt - 1_000L)
                put("last_seen", observedAt)
                put("sightings", 1)
                put("position_source", "test-import")
                if (sourceId != null) put("source_id", sourceId)
                put("position_observed_at", observedAt)
            }
        )
    }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
    }
}
