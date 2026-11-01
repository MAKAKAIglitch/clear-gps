package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioRefinementDatabaseCandidatesTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }
    private var database: LocalRadioDatabase? = null
    private var sqlite: SQLiteDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun beaconDbManualQueueKeepsRecentScansSeparateAndFiltersIneligibleWifi() {
        val db = openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val now = 1_800_000_000_000L
        val firstScan = now - 30_000L
        val secondScan = now - 60_000L

        addWifi("aa:bb:cc:dd:ee:01", "Home-1", firstScan, localSourceId)
        addWifi("aa:bb:cc:dd:ee:02", "Home-2", firstScan, localSourceId)
        addWifi("aa:bb:cc:dd:ee:03", "<unknown ssid>", firstScan, localSourceId)
        addWifi("aa:bb:cc:dd:ee:04", "Guest_nomap", firstScan, localSourceId)
        addWifi("aa:bb:cc:dd:ee:05", "Manual", firstScan, localSourceId, positionSource = "manual-edit")

        addWifi("aa:bb:cc:dd:ee:11", "Office-1", secondScan, localSourceId)
        addWifi("aa:bb:cc:dd:ee:12", "Office-2", secondScan, localSourceId)

        val groups = database!!.loadBeaconDbRefinementSnapshots(nowMillis = now, maxGroups = 10)

        assertEquals(2, groups.size)
        assertEquals(2, groups[0].size)
        assertEquals(2, groups[1].size)
        assertEquals(setOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"), groups[0].map { it.identifier }.toSet())
        assertEquals(setOf("aa:bb:cc:dd:ee:11", "aa:bb:cc:dd:ee:12"), groups[1].map { it.identifier }.toSet())
        assertFalse(groups.flatten().any { it.identifier.endsWith(":03") || it.identifier.endsWith(":04") || it.identifier.endsWith(":05") })
    }

    @Test
    fun openCellIdManualQueueRequiresLocalEnabledObservationsAndPreservesManualCoordinates() {
        val db = openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val importedSourceId = sqlite!!.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "wigle")
                put("name", "Imported test source")
                put("created_at", 1_800_000_000_000L)
                put("last_seen", 1_800_000_000_000L)
                put("enabled", 1)
                put("trust_weight", 1.0)
                put("fingerprint", "test-import-source")
            }
        )

        addCell("lte:262:01:100:1001", localSourceId, positionSource = "local-observation")
        addCell("lte:262:01:100:1002", importedSourceId, positionSource = "wigle-import", addLocalObservation = false)
        addCell("lte:262:01:100:1003", localSourceId, positionSource = "manual-edit")
        val candidates = database!!.listOpenCellIdRefinementCandidates()

        assertEquals(1, candidates.size)
        assertEquals("lte:262:01:100:1001", candidates.single().identifier)
        assertFalse(candidates.any { it.identifier.endsWith("1002") })
        assertFalse(candidates.any { it.identifier.endsWith("1003") })
    }

    @Test
    fun openCellIdManualQueueExcludesDisabledLocalSource() {
        openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        addCell("lte:262:01:200:2001", localSourceId, positionSource = "local-observation")
        sqlite!!.execSQL("UPDATE radio_sources SET enabled = 0 WHERE id = ?", arrayOf(localSourceId))

        assertTrue(database!!.listOpenCellIdRefinementCandidates().isEmpty())
    }

    @Test
    fun openCellIdManualQueueExcludesDisabledLocalDaySegments() {
        openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val observedAt = addCell("lte:262:01:300:3001", localSourceId, positionSource = "local-observation")
        val dayKey = sqlite!!.rawQuery(
            "SELECT strftime('%Y-%m-%d', ? / 1000, 'unixepoch', 'localtime')",
            arrayOf(observedAt.toString())
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        sqlite!!.insertOrThrow("radio_source_segment_settings", null, ContentValues().apply {
            put("source_id", localSourceId)
            put("day_key", dayKey)
            put("radio_type", "cell")
            put("enabled", 0)
            put("trust_weight", 1.0)
        })

        assertTrue(database!!.listOpenCellIdRefinementCandidates().isEmpty())
    }

    private fun openDatabase(): SQLiteDatabase {
        val value = LocalRadioDatabase(context, DB_NAME)
        database = value
        sqlite = value.writableDatabase
        return sqlite!!
    }

    private fun addWifi(
        identifier: String,
        ssid: String,
        observedAt: Long,
        sourceId: Long,
        positionSource: String = "local-observation"
    ) {
        val db = sqlite!!
        db.insertOrThrow("radio_points", null, ContentValues().apply {
            put("radio_type", "wifi")
            put("identifier", identifier)
            put("label", ssid)
            put("ssid", ssid)
            put("notes", "")
            put("latitude", 52.0)
            put("longitude", 5.0)
            put("accuracy", 100.0)
            put("last_rssi", -55)
            put("peak_rssi", -50)
            put("frequency", 2412)
            put("first_seen", observedAt)
            put("last_seen", observedAt)
            put("sightings", 1)
            put("position_source", positionSource)
            put("source_id", sourceId)
            put("position_observed_at", observedAt)
        })
        db.insertOrThrow("radio_observations", null, ContentValues().apply {
            put("radio_type", "wifi")
            put("identifier", identifier)
            put("latitude", 52.0)
            put("longitude", 5.0)
            put("accuracy", 100.0)
            put("rssi", -55)
            put("frequency", 2412)
            put("observed_at", observedAt)
            put("position_source", positionSource)
            put("source_id", sourceId)
        })
    }

    private fun addCell(
        identifier: String,
        sourceId: Long,
        positionSource: String,
        addLocalObservation: Boolean = true
    ): Long {
        val db = sqlite!!
        check(database!!.listRadioSources().any { it.id == sourceId }) { "Test source was not found." }
        val now = System.currentTimeMillis()
        db.insertOrThrow("radio_points", null, ContentValues().apply {
            put("radio_type", "cell")
            put("identifier", identifier)
            put("label", identifier)
            put("ssid", "")
            put("notes", "")
            put("latitude", 52.0)
            put("longitude", 5.0)
            put("accuracy", 500.0)
            put("last_rssi", -80)
            put("peak_rssi", -70)
            put("frequency", 0)
            put("first_seen", now)
            put("last_seen", now)
            put("sightings", 1)
            put("position_source", positionSource)
            put("source_id", sourceId)
            put("position_observed_at", now)
        })
        if (addLocalObservation) {
            db.insertOrThrow("radio_observations", null, ContentValues().apply {
                put("radio_type", "cell")
                put("identifier", identifier)
                put("latitude", 52.0)
                put("longitude", 5.0)
                put("accuracy", 500.0)
                put("rssi", -80)
                put("frequency", 0)
                put("observed_at", now)
                put("position_source", positionSource)
                put("source_id", sourceId)
            })
        }
        return now
    }

    companion object {
        private const val DB_NAME = "radio_refinement_candidates_test.sqlite"
    }
}
