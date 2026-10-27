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
class LocalRadioDatabaseLegacyMigrationTest {
    private lateinit var context: Context
    private var database: LocalRadioDatabase? = null
    private var sqlite: SQLiteDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun v1DatabaseMigrationAddsSsidPeakRssiAndMergesCaseVariantBssids() {
        val legacyFile = context.getDatabasePath(DB_NAME)
        legacyFile.parentFile?.mkdirs()
        val legacyDb = SQLiteDatabase.openOrCreateDatabase(legacyFile.absolutePath, null)
        val firstObservation = 1_700_000_000_000L
        val lastObservation = firstObservation + 2_000L
        val historyTime = lastObservation + 1_000L
        val originalPointId: Long

        try {
            createVersion1Schema(legacyDb)
            originalPointId = insertVersion1Point(
                legacyDb,
                identifier = WIGLE_ID.uppercase(),
                label = "Legacy WiGLE upper",
                notes = "first import note",
                latitude = 52.280,
                longitude = 40.460,
                accuracy = 45.0,
                firstSeen = firstObservation - 10_000L,
                lastSeen = firstObservation,
                lastRssi = -60,
                sightings = 2
            )
            insertVersion1Point(
                legacyDb,
                identifier = WIGLE_ID,
                label = "Legacy WiGLE lower",
                notes = "second import note",
                latitude = 52.281,
                longitude = 40.461,
                accuracy = 30.0,
                firstSeen = lastObservation - 10_000L,
                lastSeen = lastObservation,
                lastRssi = -50,
                sightings = 3
            )
            insertVersion1Observation(
                legacyDb, WIGLE_ID.uppercase(), firstObservation,
                52.280, 40.460, 45.0, -60
            )
            insertVersion1Observation(
                legacyDb, WIGLE_ID, lastObservation,
                52.281, 40.461, 30.0, -50
            )
            legacyDb.insertOrThrow(
                "scan_history",
                null,
                ContentValues().apply {
                    put("scanned_at", historyTime)
                    put("wifi_count", 2)
                    put("cell_count", 0)
                    put("ble_count", 0)
                    put("matched_count", 1)
                    put("latitude", 52.281)
                    put("longitude", 40.461)
                    put("accuracy", 30.0)
                    put("mode", "legacy-v1")
                    put("recorded", 1)
                }
            )
            legacyDb.version = 1
            assertEquals(1, legacyDb.version)
        } finally {
            legacyDb.close()
        }

        database = LocalRadioDatabase(context)
        sqlite = database!!.writableDatabase

        assertEquals(9, sqlite!!.version)
        assertTrue(sqlite!!.isWriteAheadLoggingEnabled)
        assertEquals(1, countRows("radio_points", "LOWER(identifier) = ?", WIGLE_ID))
        assertEquals(2, database!!.observationCount())
        assertEquals(1, countRows("scan_history", "scanned_at = ?", historyTime.toString()))
        assertEquals(1, countRows("radio_points", "id = ?", originalPointId.toString()))

        val point = database!!.listPoints().single { it.identifier == WIGLE_ID }
        assertEquals("wifi", point.type)
        assertEquals(WIGLE_ID, point.identifier)
        assertEquals(52.281, point.latitude!!, 0.000001)
        assertEquals(40.461, point.longitude!!, 0.000001)
        assertEquals(-50, point.rssi)
        assertEquals(-50, point.peakRssi)
        assertEquals(5, point.sightings)
        assertTrue(point.ssid.isNotBlank())
        assertTrue(point.notes.contains("first import note"))
        assertTrue(point.notes.contains("second import note"))

        val sources = database!!.listRadioSources()
        assertEquals(2, sources.size)
        val localSource = sources.single { it.kind == "local" }
        val legacyWigleSource = sources.single { it.kind == "wigle" }
        assertEquals(firstObservation, legacyWigleSource.createdAt)
        assertEquals(lastObservation, legacyWigleSource.lastSeen)
        assertEquals(2, legacyWigleSource.observationCount)
        assertEquals(1, legacyWigleSource.radioCount)
        assertEquals(legacyWigleSource.id, queryNullableLong(
            "SELECT source_id FROM radio_points WHERE id = ?", originalPointId.toString()
        ))
        assertEquals(legacyWigleSource.id, queryLong(
            "SELECT source_id FROM radio_observations WHERE identifier = ? ORDER BY observed_at DESC LIMIT 1",
            WIGLE_ID
        ))
        assertEquals(lastObservation, queryLong(
            "SELECT position_observed_at FROM radio_points WHERE id = ?", originalPointId.toString()
        ))
        assertTrue(indexExists("radio_obs_source_time"))
        assertTrue(indexExists("radio_points_source"))
        assertTrue(indexExists("radio_obs_radio_time"))
        assertFalse(queryHasRow("PRAGMA foreign_key_check"))
        assertEquals(0, countRows("radio_source_segment_settings", "1 = 1"))
        assertEquals(6, countRows("sqlite_master", "type = ? AND name LIKE 'radio_deletion_undo%'", "table"))
        assertEquals(1, countRows("radio_sources", "id = ?", localSource.id.toString()))
    }

    private fun createVersion1Schema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE radio_points (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "label TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', " +
                "latitude REAL, longitude REAL, accuracy REAL, " +
                "last_rssi INTEGER NOT NULL DEFAULT -100, frequency INTEGER NOT NULL DEFAULT 0, " +
                "first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, " +
                "sightings INTEGER NOT NULL DEFAULT 1, position_source TEXT NOT NULL DEFAULT 'unknown', " +
                "UNIQUE(radio_type, identifier))"
        )
        db.execSQL("CREATE INDEX radio_points_last_seen ON radio_points(last_seen DESC)")
        db.execSQL(
            "CREATE TABLE radio_observations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "latitude REAL, longitude REAL, accuracy REAL, " +
                "rssi INTEGER NOT NULL, frequency INTEGER NOT NULL, observed_at INTEGER NOT NULL, " +
                "position_source TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX radio_obs_time ON radio_observations(observed_at DESC)")
        db.execSQL(
            "CREATE TABLE scan_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "scanned_at INTEGER NOT NULL, wifi_count INTEGER NOT NULL, cell_count INTEGER NOT NULL, " +
                "ble_count INTEGER NOT NULL, matched_count INTEGER NOT NULL, " +
                "latitude REAL, longitude REAL, accuracy REAL, mode TEXT NOT NULL, recorded INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX scan_history_time ON scan_history(scanned_at DESC)")
    }

    private fun insertVersion1Point(
        db: SQLiteDatabase,
        identifier: String,
        label: String,
        notes: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        firstSeen: Long,
        lastSeen: Long,
        lastRssi: Int,
        sightings: Int
    ): Long = db.insertOrThrow(
        "radio_points",
        null,
        ContentValues().apply {
            put("radio_type", "wifi")
            put("identifier", identifier)
            put("label", label)
            put("notes", notes)
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy)
            put("last_rssi", lastRssi)
            put("frequency", 2412)
            put("first_seen", firstSeen)
            put("last_seen", lastSeen)
            put("sightings", sightings)
            put("position_source", "wigle-import")
        }
    )

    private fun insertVersion1Observation(
        db: SQLiteDatabase,
        identifier: String,
        observedAt: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        rssi: Int
    ) {
        db.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("rssi", rssi)
                put("frequency", 2412)
                put("observed_at", observedAt)
                put("position_source", "wigle-import")
            }
        )
    }

    @Test
    fun v4DatabaseMigrationPreservesRowsAndAttributesLegacyWigleProvenance() {
        val legacyFile = context.getDatabasePath(DB_NAME)
        legacyFile.parentFile?.mkdirs()
        val legacyDb = SQLiteDatabase.openOrCreateDatabase(legacyFile.absolutePath, null)
        val wigleFirstSeen = 1_700_000_000_000L
        val wigleLastSeen = wigleFirstSeen + 2_000L
        val localObservedAt = wigleFirstSeen + 4_000L
        val manualObservedAt = wigleFirstSeen + 5_000L
        val historyId: Long
        val wiglePointId: Long
        val localPointId: Long
        val manualPointId: Long

        try {
            createVersion4Schema(legacyDb)
            wiglePointId = insertLegacyPoint(
                legacyDb,
                identifier = WIGLE_ID,
                label = "legacy WiGLE",
                ssid = "legacy-network",
                latitude = 52.280,
                longitude = 40.460,
                accuracy = 45.0,
                observedAt = wigleLastSeen,
                positionSource = "wigle-import"
            )
            localPointId = insertLegacyPoint(
                legacyDb,
                identifier = LOCAL_ID,
                label = "local anchor",
                ssid = "local-network",
                latitude = 52.274,
                longitude = 40.452,
                accuracy = 8.0,
                observedAt = localObservedAt,
                positionSource = "manual-reference"
            )
            manualPointId = insertLegacyPoint(
                legacyDb,
                identifier = MANUAL_ID,
                label = "manually edited",
                ssid = "manual-network",
                latitude = 40.0,
                longitude = -70.0,
                accuracy = 10.0,
                observedAt = manualObservedAt,
                positionSource = "manual-edit"
            )

            insertLegacyObservation(
                legacyDb, WIGLE_ID, wigleFirstSeen, 52.270, 40.450, 65.0, -66, "wigle-import"
            )
            insertLegacyObservation(
                legacyDb, WIGLE_ID, wigleLastSeen, 52.280, 40.460, 45.0, -52, "wigle-import"
            )
            insertLegacyObservation(
                legacyDb, LOCAL_ID, localObservedAt, 52.274, 40.452, 8.0, -49, "manual-reference"
            )
            historyId = legacyDb.insertOrThrow(
                "scan_history",
                null,
                ContentValues().apply {
                    put("scanned_at", localObservedAt)
                    put("wifi_count", 2)
                    put("cell_count", 0)
                    put("ble_count", 0)
                    put("matched_count", 1)
                    put("latitude", 52.274)
                    put("longitude", 40.452)
                    put("accuracy", 8.0)
                    put("mode", "legacy-v4")
                    put("recorded", 1)
                }
            )
            legacyDb.version = 4
            assertEquals(4, legacyDb.version)
        } finally {
            legacyDb.close()
        }

        database = LocalRadioDatabase(context)
        sqlite = database!!.writableDatabase

        assertEquals(9, sqlite!!.version)
        assertTrue(sqlite!!.isWriteAheadLoggingEnabled)
        assertEquals(3, database!!.pointCount())
        assertEquals(3, database!!.observationCount())
        assertEquals(1, countRows("scan_history", "id = ?", historyId.toString()))
        assertEquals(1, countRows("radio_points", "id = ?", wiglePointId.toString()))
        assertEquals(1, countRows("radio_points", "id = ?", localPointId.toString()))
        assertEquals(1, countRows("radio_points", "id = ?", manualPointId.toString()))

        val sources = database!!.listRadioSources()
        assertEquals(2, sources.size)
        val localSource = sources.single { it.kind == "local" }
        val wigleSource = sources.single { it.kind == "wigle" }
        assertEquals(wigleFirstSeen, wigleSource.createdAt)
        assertEquals(wigleLastSeen, wigleSource.lastSeen)
        assertEquals(2, wigleSource.observationCount)
        assertEquals(1, wigleSource.radioCount)
        assertEquals(1, localSource.observationCount)
        assertEquals(1, localSource.radioCount)
        assertTrue(wigleSource.name.contains("до версии"))

        assertEquals(wigleSource.id, queryNullableLong(
            "SELECT source_id FROM radio_points WHERE id = ?", wiglePointId.toString()
        ))
        assertEquals(localSource.id, queryNullableLong(
            "SELECT source_id FROM radio_points WHERE id = ?", localPointId.toString()
        ))
        assertNull(queryNullableLong(
            "SELECT source_id FROM radio_points WHERE id = ?", manualPointId.toString()
        ))
        assertEquals(wigleLastSeen, queryLong(
            "SELECT position_observed_at FROM radio_points WHERE id = ?", wiglePointId.toString()
        ))
        assertEquals(localObservedAt, queryLong(
            "SELECT position_observed_at FROM radio_points WHERE id = ?", localPointId.toString()
        ))
        assertNull(queryNullableLong(
            "SELECT position_observed_at FROM radio_points WHERE id = ?", manualPointId.toString()
        ))

        assertEquals(wigleSource.id, queryLong(
            "SELECT source_id FROM radio_observations WHERE identifier = ? ORDER BY observed_at LIMIT 1",
            WIGLE_ID
        ))
        assertEquals(wigleSource.id, queryLong(
            "SELECT source_id FROM radio_observations WHERE identifier = ? ORDER BY observed_at DESC LIMIT 1",
            WIGLE_ID
        ))
        assertEquals(localSource.id, queryLong(
            "SELECT source_id FROM radio_observations WHERE identifier = ?", LOCAL_ID
        ))
        assertEquals(0, countRows("radio_source_segment_settings", "1 = 1"))
        assertEquals(6, countRows("sqlite_master", "type = ? AND name LIKE 'radio_deletion_undo%'", "table"))
        assertTrue(indexExists("radio_obs_source_time"))
        assertTrue(indexExists("radio_points_source"))
        assertTrue(indexExists("radio_obs_radio_time"))
        assertFalse(queryHasRow("PRAGMA foreign_key_check"))
    }

    private fun createVersion4Schema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE radio_points (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "label TEXT NOT NULL DEFAULT '', ssid TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', " +
                "latitude REAL, longitude REAL, accuracy REAL, " +
                "last_rssi INTEGER NOT NULL DEFAULT -100, peak_rssi INTEGER NOT NULL DEFAULT -120, " +
                "frequency INTEGER NOT NULL DEFAULT 0, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, " +
                "sightings INTEGER NOT NULL DEFAULT 1, position_source TEXT NOT NULL DEFAULT 'unknown', " +
                "UNIQUE(radio_type, identifier))"
        )
        db.execSQL("CREATE INDEX radio_points_last_seen ON radio_points(last_seen DESC)")
        db.execSQL(
            "CREATE TABLE radio_observations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "latitude REAL, longitude REAL, accuracy REAL, " +
                "rssi INTEGER NOT NULL, frequency INTEGER NOT NULL, observed_at INTEGER NOT NULL, " +
                "position_source TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX radio_obs_time ON radio_observations(observed_at DESC)")
        db.execSQL(
            "CREATE TABLE scan_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "scanned_at INTEGER NOT NULL, wifi_count INTEGER NOT NULL, cell_count INTEGER NOT NULL, " +
                "ble_count INTEGER NOT NULL, matched_count INTEGER NOT NULL, " +
                "latitude REAL, longitude REAL, accuracy REAL, mode TEXT NOT NULL, recorded INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX scan_history_time ON scan_history(scanned_at DESC)")
    }

    private fun insertLegacyPoint(
        db: SQLiteDatabase,
        identifier: String,
        label: String,
        ssid: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        observedAt: Long,
        positionSource: String
    ): Long = db.insertOrThrow(
        "radio_points",
        null,
        ContentValues().apply {
            put("radio_type", "wifi")
            put("identifier", identifier)
            put("label", label)
            put("ssid", ssid)
            put("notes", "")
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy)
            put("last_rssi", -55)
            put("peak_rssi", -50)
            put("frequency", 2412)
            put("first_seen", observedAt - 10_000L)
            put("last_seen", observedAt)
            put("sightings", 2)
            put("position_source", positionSource)
        }
    )

    private fun insertLegacyObservation(
        db: SQLiteDatabase,
        identifier: String,
        observedAt: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        rssi: Int,
        positionSource: String
    ) {
        db.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("rssi", rssi)
                put("frequency", 2412)
                put("observed_at", observedAt)
                put("position_source", positionSource)
            }
        )
    }

    private fun countRows(table: String, where: String, vararg args: String): Int =
        sqlite!!.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun queryLong(sql: String, vararg args: String): Long =
        sqlite!!.rawQuery(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun queryNullableLong(sql: String, vararg args: String): Long? =
        sqlite!!.rawQuery(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getLong(0)
        }

    private fun queryHasRow(sql: String): Boolean =
        sqlite!!.rawQuery(sql, null).use { it.moveToFirst() }

    private fun indexExists(index: String): Boolean =
        sqlite!!.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(index)
        ).use { it.moveToFirst() }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
        private const val WIGLE_ID = "aa:bb:cc:dd:ee:e1"
        private const val LOCAL_ID = "aa:bb:cc:dd:ee:e2"
        private const val MANUAL_ID = "aa:bb:cc:dd:ee:e3"
    }
}
