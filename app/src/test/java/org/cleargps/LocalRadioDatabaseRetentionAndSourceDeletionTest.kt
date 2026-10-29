package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class LocalRadioDatabaseRetentionAndSourceDeletionTest {
    private lateinit var context: Context
    private lateinit var database: LocalRadioDatabase
    private lateinit var sqlite: SQLiteDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit().remove(ClearGpsPreferences.KEY_RADIO_OBSERVATION_LIMIT).commit()
        database = LocalRadioDatabase(context)
        sqlite = database.writableDatabase
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) {
            context.deleteDatabase(DB_NAME)
            context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
                .edit().remove(ClearGpsPreferences.KEY_RADIO_OBSERVATION_LIMIT).commit()
        }
    }

    @Test
    fun changingRetentionLimitImmediatelyDeletesOldestRowsAndKeepsNewest() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val firstObservedAt = 1_700_000_000_000L
        sqlite.beginTransaction()
        try {
            repeat(5_002) { index ->
                val observedAt = firstObservedAt + index
                sqlite.insertOrThrow(
                    "radio_observations",
                    null,
                    ContentValues().apply {
                        put("radio_type", "wifi")
                        put("identifier", "retention-radio-$index")
                        putNull("latitude")
                        putNull("longitude")
                        putNull("accuracy")
                        put("rssi", -70)
                        put("frequency", 2412)
                        put("observed_at", observedAt)
                        put("position_source", "unlocated")
                        put("source_id", localSourceId)
                    }
                )
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }

        assertEquals(5_002, database.observationCount())
        assertEquals(2, database.setObservationRetentionLimit(5_000))
        assertEquals(5_000, database.observationCount())
        assertEquals(5_000, database.observationRetentionLimit())
        assertEquals(firstObservedAt + 2L, scalarLong("SELECT MIN(observed_at) FROM radio_observations"))
        assertEquals(firstObservedAt + 5_001L, scalarLong("SELECT MAX(observed_at) FROM radio_observations"))
        assertEquals(0, countRows(
            "radio_observations", "identifier = ?", "retention-radio-0"
        ))
        assertEquals(1, countRows(
            "radio_observations", "identifier = ?", "retention-radio-5001"
        ))
    }

    @Test
    fun deletingImportedSourceCascadesItsRowsAndRebuildsPointFromRemainingSource() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        val importedSourceId = sqlite.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "custom")
                put("name", "Cascade fixture")
                put("created_at", now - 10_000L)
                put("last_seen", now)
                put("enabled", 1)
                put("trust_weight", 1.0)
                put("fingerprint", "cascade-test-$now")
            }
        )
        val dayKey = sqlite.rawQuery(
            "SELECT strftime('%Y-%m-%d', ? / 1000, 'unixepoch', 'localtime')",
            arrayOf(now.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", SHARED_RADIO_ID)
                put("label", "imported coordinate")
                put("ssid", "shared-network")
                put("notes", "")
                put("latitude", 52.280)
                put("longitude", 40.460)
                put("accuracy", 60.0)
                put("last_rssi", -76)
                put("peak_rssi", -72)
                put("frequency", 2412)
                put("first_seen", now - 5_000L)
                put("last_seen", now - 1_000L)
                put("sightings", 1)
                put("position_source", "wigle-import")
                put("source_id", importedSourceId)
                put("position_observed_at", now - 1_000L)
            }
        )
        insertObservation(importedSourceId, now - 1_000L, 52.280, 40.460, 60.0, -76, "wigle-import")
        insertObservation(localSourceId, now, 52.274, 40.452, 8.0, -51, "manual-reference")
        sqlite.insertOrThrow(
            "radio_source_segment_settings",
            null,
            ContentValues().apply {
                put("source_id", importedSourceId)
                put("day_key", dayKey)
                put("radio_type", "wifi")
                put("enabled", 0)
                put("trust_weight", 0.25)
            }
        )

        assertEquals(2, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?",
            "wifi", SHARED_RADIO_ID
        ))
        assertTrue(database.deleteRadioSource(importedSourceId))

        assertEquals(0, countRows("radio_sources", "id = ?", importedSourceId.toString()))
        assertEquals(0, countRows("radio_observations", "source_id = ?", importedSourceId.toString()))
        assertEquals(0, countRows(
            "radio_source_segment_settings", "source_id = ?", importedSourceId.toString()
        ))
        assertEquals(1, countRows(
            "radio_observations", "source_id = ?", localSourceId.toString()
        ))
        val rebuilt = database.listPoints().single { it.identifier == SHARED_RADIO_ID }
        assertEquals(52.274, rebuilt.latitude!!, 0.000001)
        assertEquals(40.452, rebuilt.longitude!!, 0.000001)
        assertEquals(localSourceId, rebuilt.sourceId)
        assertEquals("manual-reference", rebuilt.positionSource)
    }

    @Test
    fun listingMapPointObservationsWithSourceJoinUsesUnambiguousOrder() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val firstObservedAt = 1_700_000_000_000L
        val pointId = sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", SHARED_RADIO_ID)
                put("label", "Map point query fixture")
                put("ssid", "test-network")
                put("notes", "")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 25.0)
                put("last_rssi", -60)
                put("peak_rssi", -55)
                put("frequency", 2412)
                put("first_seen", firstObservedAt)
                put("last_seen", firstObservedAt + 1_000L)
                put("sightings", 2)
                put("position_source", "local-test")
                put("source_id", localSourceId)
                put("position_observed_at", firstObservedAt + 1_000L)
            }
        )

        insertObservation(localSourceId, firstObservedAt, 52.274, 40.452, 25.0, -70, "local-test")
        insertObservation(localSourceId, firstObservedAt + 1_000L, 52.275, 40.453, 20.0, -55, "local-test")

        val point = database.findPoint(pointId) ?: throw AssertionError("Seeded map point was not found")
        val observations = database.listObservations(point)

        assertEquals(2, observations.size)
        assertEquals(firstObservedAt + 1_000L, observations[0].observedAt)
        assertEquals(firstObservedAt, observations[1].observedAt)
    }

    @Test
    fun localSourceCannotBeDeleted() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id

        assertFalse(database.deleteRadioSource(localSourceId))
        assertEquals(1, countRows("radio_sources", "kind = ?", "local"))
        assertEquals(localSourceId, database.listRadioSources().single { it.kind == "local" }.id)
    }

    private fun insertObservation(
        sourceId: Long,
        observedAt: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        rssi: Int,
        positionSource: String
    ) {
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", SHARED_RADIO_ID)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("rssi", rssi)
                put("frequency", 2412)
                put("observed_at", observedAt)
                put("position_source", positionSource)
                put("source_id", sourceId)
            }
        )
    }

    private fun countRows(table: String, where: String, vararg args: String): Int =
        sqlite.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun scalarLong(sql: String): Long =
        sqlite.rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
        private const val SHARED_RADIO_ID = "aa:bb:cc:dd:ee:c1"
    }
}
