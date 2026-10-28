package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalRadioDatabaseBackupRoundTripTest {
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
    fun clearGpsSqliteBackupRoundTripPreservesLocatedAndUnknownRowsAndIsIdempotent() {
        seedBackupRows()
        val backupBytes = ByteArrayOutputStream().also(database::exportSqliteBackup).toByteArray()
        assertTrue(backupBytes.isNotEmpty())

        val namespace = "clear-gps-roundtrip-" + UUID.randomUUID().toString()
        // SQLiteOpenHelper opens this path directly; ensure its parent exists on Windows/Robolectric too.
        val targetDatabaseName = namespace + ".sqlite"
        val targetFile = context.getDatabasePath(targetDatabaseName)
        val databaseDirectory = targetFile.parentFile ?: context.cacheDir
        assertTrue(
            "Unable to prepare isolated SQLite test directory: $databaseDirectory",
            databaseDirectory.mkdirs() || databaseDirectory.isDirectory
        )
        val targetContext = IsolatedDatabaseContext(context, targetDatabaseName, namespace)
        val target = LocalRadioDatabase(targetContext)
        try {
            target.writableDatabase
            val first = target.importSqliteDatabase(ByteArrayInputStream(backupBytes))

            assertEquals("SQLite Clear GPS", first.format)
            assertNotNull(first.sourceId)
            assertEquals(2, first.observationsImported)
            assertEquals(2, target.observationCount())
            assertEquals(2, target.pointCount())
            assertEquals(1, countRows(
                target.writableDatabase, "scan_history", "mode = ?", "backup-roundtrip"
            ))
            assertEquals(1, countRows(
                target.writableDatabase, "radio_sources", "kind = ? AND id = ?", "custom", first.sourceId.toString()
            ))
            assertEquals(2, countRows(
                target.writableDatabase, "radio_observations", "source_id = ?", first.sourceId.toString()
            ))

            val located = target.listPoints().single { it.identifier == LOCATED_ID }
            assertEquals(52.274, located.latitude!!, 0.000001)
            assertEquals(40.452, located.longitude!!, 0.000001)
            assertNotNull(located.sourceId)
            val unknown = target.listPoints().single { it.identifier == UNKNOWN_ID }
            assertNull(unknown.latitude)
            assertNull(unknown.longitude)
            assertEquals("unlocated", unknown.positionSource)
            assertNull(unknown.sourceId)

            val second = target.importSqliteDatabase(ByteArrayInputStream(backupBytes))
            assertEquals("SQLite Clear GPS", second.format)
            assertEquals(first.sourceId, second.sourceId)
            assertEquals(0, second.observationsImported)
            assertEquals(2, target.observationCount())
            assertEquals(2, target.pointCount())
            assertEquals(1, countRows(
                target.writableDatabase, "scan_history", "mode = ?", "backup-roundtrip"
            ))
            assertEquals(1, countRows(
                target.writableDatabase, "radio_sources", "kind = ? AND fingerprint LIKE ?",
                "custom", "clear-gps:%"
            ))
        } finally {
            target.close()
            context.deleteDatabase(targetDatabaseName)
            targetFile.delete()
            File(targetFile.absolutePath + "-wal").delete()
            File(targetFile.absolutePath + "-shm").delete()
            targetContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    private fun seedBackupRows() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", LOCATED_ID)
                put("label", "located fixture")
                put("ssid", "backup-home")
                put("notes", "known coordinates")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 18.0)
                put("last_rssi", -55)
                put("peak_rssi", -49)
                put("frequency", 2412)
                put("first_seen", now - 30_000L)
                put("last_seen", now)
                put("sightings", 2)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
                put("position_observed_at", now)
            }
        )
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", LOCATED_ID)
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 18.0)
                put("rssi", -55)
                put("frequency", 2412)
                put("observed_at", now)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
            }
        )
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", UNKNOWN_ID)
                put("label", "unlocated fixture")
                put("ssid", "hidden-network")
                put("notes", "")
                putNull("latitude")
                putNull("longitude")
                putNull("accuracy")
                put("last_rssi", -74)
                put("peak_rssi", -70)
                put("frequency", 2437)
                put("first_seen", now)
                put("last_seen", now)
                put("sightings", 1)
                put("position_source", "unlocated")
            }
        )
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", UNKNOWN_ID)
                putNull("latitude")
                putNull("longitude")
                putNull("accuracy")
                put("rssi", -74)
                put("frequency", 2437)
                put("observed_at", now)
                put("position_source", "unlocated")
                put("source_id", sourceId)
            }
        )
        sqlite.insertOrThrow(
            "scan_history",
            null,
            ContentValues().apply {
                put("scanned_at", now)
                put("wifi_count", 2)
                put("cell_count", 0)
                put("ble_count", 0)
                put("matched_count", 1)
                putNull("latitude")
                putNull("longitude")
                putNull("accuracy")
                put("mode", "backup-roundtrip")
                put("recorded", 1)
            }
        )
    }

    private fun countRows(db: SQLiteDatabase, table: String, where: String, vararg args: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private class IsolatedDatabaseContext(
        base: Context,
        private val databaseName: String,
        private val namespace: String
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File =
            baseContext.getDatabasePath(databaseName)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase {
            ensureParentDirectory()
            return baseContext.openOrCreateDatabase(databaseName, mode, factory)
        }

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase {
            ensureParentDirectory()
            return baseContext.openOrCreateDatabase(databaseName, mode, factory, errorHandler)
        }

        private fun ensureParentDirectory() {
            val parent = baseContext.getDatabasePath(databaseName).parentFile
                ?: error("Isolated SQLite database has no parent: $databaseName")
            check(parent.isDirectory || parent.mkdirs()) {
                "Unable to create isolated SQLite database directory: $parent"
            }
        }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$name-$namespace", mode)
    }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
        private const val LOCATED_ID = "aa:bb:cc:dd:ee:a1"
        private const val UNKNOWN_ID = "aa:bb:cc:dd:ee:a2"
    }
}
