package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class LocalRadioDatabaseUndoTest {
    private lateinit var context: Context
    private lateinit var database: LocalRadioDatabase
    private lateinit var sqlite: SQLiteDatabase

    private data class Seed(
        val sourceId: Long,
        val dayKey: String,
        val pointId: Long,
        val observationId: Long,
        val historyId: Long,
        val observedAt: Long
    )

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
    fun deletingDayThenUndoRestoresPointObservationHistoryAndEverySegmentSetting() {
        val seed = seedData()

        val deleted = database.deleteRadioSourceDay(seed.sourceId, seed.dayKey)
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)
        assertEquals(1, deleted.observationsDeleted)
        assertEquals(1, deleted.scanHistoryDeleted)
        assertEquals(0, countRows("radio_observations", "source_id = ?", seed.sourceId.toString()))
        assertEquals(0, countRows("scan_history", "id = ?", seed.historyId.toString()))
        assertEquals(0, countRows("radio_source_segment_settings", "source_id = ? AND day_key = ?",
            seed.sourceId.toString(), seed.dayKey))
        assertNull(readPointLatitude(seed.pointId))

        assertTrue(database.undoRadioSourceDeletion(undoToken!!))

        assertEquals(1, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(1, countRows("scan_history", "id = ?", seed.historyId.toString()))
        assertEquals(2, countRows("radio_source_segment_settings", "source_id = ? AND day_key = ?",
            seed.sourceId.toString(), seed.dayKey))
        assertEquals(52.274, readPointLatitude(seed.pointId)!!, 0.000001)
        assertEquals("manual-reference", readPointPositionSource(seed.pointId))
        assertEquals(0, readSettingEnabled(seed.sourceId, seed.dayKey, "wifi"))
        assertEquals(0.35, readSettingWeight(seed.sourceId, seed.dayKey, "wifi")!!, 0.000001)
        assertEquals(1, readSettingEnabled(seed.sourceId, seed.dayKey, "cell"))
    }

    @Test
    fun deletingSingleRadioTypeThenUndoRestoresOnlyThatSegment() {
        val seed = seedData()

        val deleted = database.deleteRadioSourceSegment(seed.sourceId, seed.dayKey, "wifi")
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)
        assertEquals(1, deleted.observationsDeleted)
        assertEquals(0, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(1, countRows("radio_source_segment_settings", "source_id = ? AND day_key = ?",
            seed.sourceId.toString(), seed.dayKey))

        assertTrue(database.undoRadioSourceDeletion(undoToken!!))

        assertEquals(1, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(2, countRows("radio_source_segment_settings", "source_id = ? AND day_key = ?",
            seed.sourceId.toString(), seed.dayKey))
        assertEquals(52.274, readPointLatitude(seed.pointId)!!, 0.000001)
        assertEquals(0, readSettingEnabled(seed.sourceId, seed.dayKey, "wifi"))
        assertEquals(1, readSettingEnabled(seed.sourceId, seed.dayKey, "cell"))
    }

    @Test
    fun undoExpiresAndDoesNotRestoreDeletedData() {
        val seed = seedData()
        val deleted = database.deleteRadioSourceSegment(seed.sourceId, seed.dayKey, "wifi")
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)

        sqlite.execSQL(
            "UPDATE radio_deletion_undo SET created_at = ? WHERE undo_token = ?",
            arrayOf((System.currentTimeMillis() - UNDO_TTL_MILLIS - 1L).toString(), undoToken!!)
        )

        assertFalse(database.undoRadioSourceDeletion(undoToken))
        assertEquals(0, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(0, countRows("radio_source_segment_settings",
            "source_id = ? AND day_key = ? AND radio_type = ?",
            seed.sourceId.toString(), seed.dayKey, "wifi"))
    }

    @Test
    fun laterDatabaseMutationInvalidatesPreviousUndoToken() {
        val seed = seedData()
        // Keep another observation on a different day so the point still exists after deletion
        // and can be edited to prove that this mutation invalidates the pending Undo token.
        insertOlderObservation(seed)
        val deleted = database.deleteRadioSourceDay(seed.sourceId, seed.dayKey)
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)

        assertTrue(database.updatePoint(
            seed.pointId, "edited", "test-network", "edited after delete",
            40.0, -70.0, 10.0
        ))
        assertFalse(database.undoRadioSourceDeletion(undoToken!!))
        assertEquals("edited", readPointLabel(seed.pointId))
        assertEquals(0, countRows("radio_observations", "id = ?", seed.observationId.toString()))
    }

    @Test
    fun v7DatabaseMigrationAddsUndoTablesAndIndexesWithoutLosingExistingRows() {
        val seed = seedData()
        UNDO_TABLES.forEach { table -> sqlite.execSQL("DROP TABLE $table") }
        sqlite.execSQL("DROP INDEX IF EXISTS radio_obs_radio_time")
        sqlite.version = 7
        database.close()

        database = LocalRadioDatabase(context)
        sqlite = database.writableDatabase

        assertEquals(11, sqlite.version)
        UNDO_TABLES.forEach { table -> assertTrue("missing migrated table $table", tableExists(table)) }
        assertTrue("missing radio-key/time index", indexExists("radio_obs_radio_time"))
        assertTrue("write-ahead logging should be enabled", sqlite.isWriteAheadLoggingEnabled)
        assertEquals(1, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(1, countRows("radio_points", "id = ?", seed.pointId.toString()))
        assertEquals(1, countRows("scan_history", "id = ?", seed.historyId.toString()))
    }

    private fun seedData(): Seed {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val observedAt = System.currentTimeMillis()
        val dayKey = sqlite.rawQuery(
            "SELECT strftime('%Y-%m-%d', ? / 1000, 'unixepoch', 'localtime')",
            arrayOf(observedAt.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        val radioType = "wifi"
        val identifier = "aa:bb:cc:dd:ee:01"
        val pointValues = ContentValues().apply {
            put("radio_type", radioType)
            put("identifier", identifier)
            put("label", "original label")
            put("ssid", "test-network")
            put("notes", "original notes")
            put("latitude", 52.274)
            put("longitude", 40.452)
            put("accuracy", 23.0)
            put("last_rssi", -55)
            put("peak_rssi", -49)
            put("frequency", 2412)
            put("first_seen", observedAt - 10_000L)
            put("last_seen", observedAt)
            put("sightings", 3)
            put("position_source", "manual-reference")
            put("source_id", sourceId)
            put("position_observed_at", observedAt)
        }
        val pointId = sqlite.insertOrThrow("radio_points", null, pointValues)

        val observationValues = ContentValues().apply {
            put("radio_type", radioType)
            put("identifier", identifier)
            put("latitude", 52.274)
            put("longitude", 40.452)
            put("accuracy", 23.0)
            put("rssi", -55)
            put("frequency", 2412)
            put("observed_at", observedAt)
            put("position_source", "manual-reference")
            put("source_id", sourceId)
        }
        val observationId = sqlite.insertOrThrow("radio_observations", null, observationValues)

        val historyValues = ContentValues().apply {
            put("scanned_at", observedAt)
            put("wifi_count", 1)
            put("cell_count", 0)
            put("ble_count", 0)
            put("matched_count", 1)
            put("latitude", 52.274)
            put("longitude", 40.452)
            put("accuracy", 23.0)
            put("mode", "auto")
            put("recorded", 1)
        }
        val historyId = sqlite.insertOrThrow("scan_history", null, historyValues)

        insertSegmentSetting(sourceId, dayKey, "wifi", enabled = false, trustWeight = 0.35)
        insertSegmentSetting(sourceId, dayKey, "cell", enabled = true, trustWeight = 0.8)

        return Seed(sourceId, dayKey, pointId, observationId, historyId, observedAt)
    }

    private fun insertOlderObservation(seed: Seed) {
        val olderAt = seed.observedAt - 3L * 24L * 60L * 60L * 1_000L
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", "aa:bb:cc:dd:ee:01")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 23.0)
                put("rssi", -58)
                put("frequency", 2412)
                put("observed_at", olderAt)
                put("position_source", "manual-reference")
                put("source_id", seed.sourceId)
            }
        )
    }

    private fun insertSegmentSetting(
        sourceId: Long, dayKey: String, radioType: String, enabled: Boolean, trustWeight: Double
    ) {
        sqlite.insertOrThrow(
            "radio_source_segment_settings",
            null,
            ContentValues().apply {
                put("source_id", sourceId)
                put("day_key", dayKey)
                put("radio_type", radioType)
                put("enabled", if (enabled) 1 else 0)
                put("trust_weight", trustWeight)
            }
        )
    }

    private fun countRows(table: String, where: String, vararg args: String): Int =
        sqlite.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun readPointLatitude(pointId: Long): Double? =
        sqlite.rawQuery("SELECT latitude FROM radio_points WHERE id = ?", arrayOf(pointId.toString())).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getDouble(0)
        }

    private fun readPointPositionSource(pointId: Long): String? =
        sqlite.rawQuery("SELECT position_source FROM radio_points WHERE id = ?", arrayOf(pointId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }

    private fun readPointLabel(pointId: Long): String? =
        sqlite.rawQuery("SELECT label FROM radio_points WHERE id = ?", arrayOf(pointId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }

    private fun readSettingEnabled(sourceId: Long, dayKey: String, radioType: String): Int? =
        sqlite.rawQuery(
            "SELECT enabled FROM radio_source_segment_settings WHERE source_id = ? AND day_key = ? AND radio_type = ?",
            arrayOf(sourceId.toString(), dayKey, radioType)
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getInt(0) }

    private fun readSettingWeight(sourceId: Long, dayKey: String, radioType: String): Double? =
        sqlite.rawQuery(
            "SELECT trust_weight FROM radio_source_segment_settings WHERE source_id = ? AND day_key = ? AND radio_type = ?",
            arrayOf(sourceId.toString(), dayKey, radioType)
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getDouble(0) }

    private fun tableExists(table: String): Boolean =
        sqlite.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun indexExists(index: String): Boolean =
        sqlite.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(index)
        ).use { it.moveToFirst() }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
        private const val UNDO_TTL_MILLIS = 120_000L
        private val UNDO_TABLES = listOf(
            "radio_deletion_undo",
            "radio_deletion_undo_keys",
            "radio_deletion_undo_points",
            "radio_deletion_undo_observations",
            "radio_deletion_undo_history",
            "radio_deletion_undo_segments"
        )
    }
}
