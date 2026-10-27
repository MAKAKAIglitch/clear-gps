package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
class LocalRadioDatabaseWigleSqliteRoundTripTest {
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
    fun nativeWigleSqliteRoundTripPreservesLocationsAndSummaryOnlyRadios() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        seedLocatedRadio(localSourceId, now)
        seedSummaryOnlyRadio(now)

        val bytes = ByteArrayOutputStream().also(database::exportWigleSqlite).toByteArray()
        assertTrue(bytes.isNotEmpty())

        val namespace = "clear-gps-wigle-roundtrip-" + UUID.randomUUID().toString()
        val targetFile = File(context.cacheDir, namespace + ".sqlite")
        val targetContext = IsolatedDatabaseContext(context, targetFile, namespace)
        val target = LocalRadioDatabase(targetContext)
        try {
            target.writableDatabase
            val first = target.importSqliteDatabase(ByteArrayInputStream(bytes))

            assertEquals("SQLite WiGLE", first.format)
            assertNotNull(first.sourceId)
            assertEquals(2, first.observationsImported)
            assertEquals(2, first.uniqueRadios)
            assertEquals(2, target.observationCount())
            assertEquals(2, target.pointCount())
            assertEquals(1, countRows(
                target.writableDatabase, "radio_sources",
                "kind = ? AND id = ?", "wigle", first.sourceId.toString()
            ))
            assertEquals(2, countRows(
                target.writableDatabase, "radio_observations", "source_id = ?", first.sourceId.toString()
            ))

            val located = target.listPoints().single { it.identifier == LOCATED_ID }
            assertEquals(52.274, located.latitude!!, 0.000001)
            assertEquals(40.452, located.longitude!!, 0.000001)
            assertNotNull(located.sourceId)
            val summaryOnly = target.listPoints().single { it.identifier == SUMMARY_ONLY_ID }
            assertNull(summaryOnly.latitude)
            assertNull(summaryOnly.longitude)
            assertEquals("unlocated", summaryOnly.positionSource)

            val sightingsBeforeReimport = summaryOnly.sightings
            val second = target.importSqliteDatabase(ByteArrayInputStream(bytes))
            assertEquals("SQLite WiGLE", second.format)
            assertEquals(first.sourceId, second.sourceId)
            assertEquals(0, second.observationsImported)
            assertEquals(2, second.uniqueRadios)
            assertEquals(2, target.observationCount())
            assertEquals(2, target.pointCount())
            assertEquals(1, countRows(
                target.writableDatabase, "radio_sources", "kind = ?", "wigle"
            ))
            assertEquals(2, countRows(
                target.writableDatabase, "radio_observations", "source_id = ?", first.sourceId.toString()
            ))
            assertEquals(
                sightingsBeforeReimport,
                target.listPoints().single { it.identifier == SUMMARY_ONLY_ID }.sightings
            )
        } finally {
            target.close()
            targetFile.delete()
            File(targetFile.absolutePath + "-wal").delete()
            File(targetFile.absolutePath + "-shm").delete()
            targetContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    private fun seedLocatedRadio(sourceId: Long, now: Long) {
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", LOCATED_ID)
                put("label", "located fixture")
                put("ssid", "wigle-home")
                put("notes", "")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 5.0)
                put("last_rssi", -45)
                put("peak_rssi", -42)
                put("frequency", 2412)
                put("first_seen", now - 4_000L)
                put("last_seen", now - 2_000L)
                put("sightings", 2)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
                put("position_observed_at", now - 2_000L)
            }
        )
        insertObservation(sourceId, now - 4_000L, 52.270, 40.450, 25.0, -61)
        insertObservation(sourceId, now - 2_000L, 52.274, 40.452, 5.0, -45)
    }

    private fun seedSummaryOnlyRadio(now: Long) {
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", SUMMARY_ONLY_ID)
                put("label", "summary only")
                put("ssid", "unlocated-ssid")
                put("notes", "")
                putNull("latitude")
                putNull("longitude")
                putNull("accuracy")
                put("last_rssi", -79)
                put("peak_rssi", -74)
                put("frequency", 2437)
                put("first_seen", now - 1_000L)
                put("last_seen", now)
                put("sightings", 1)
                put("position_source", "unlocated")
            }
        )
    }

    private fun insertObservation(
        sourceId: Long,
        observedAt: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        rssi: Int
    ) {
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", LOCATED_ID)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("rssi", rssi)
                put("frequency", 2412)
                put("observed_at", observedAt)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
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
        private val databaseFile: File,
        private val namespace: String
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File = databaseFile

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$name-$namespace", mode)
    }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
        private const val LOCATED_ID = "aa:bb:cc:dd:ee:d1"
        private const val SUMMARY_ONLY_ID = "aa:bb:cc:dd:ee:d2"
    }
}
