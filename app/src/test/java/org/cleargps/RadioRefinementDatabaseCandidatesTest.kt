package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioRefinementDatabaseCandidatesTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }
    private val testDatabaseName = "rr_${UUID.randomUUID().toString().take(12)}.db"
    private var database: LocalRadioDatabase? = null
    private var sqlite: SQLiteDatabase? = null

    @Before
    fun setUp() {
        val databaseFile = context.getDatabasePath(testDatabaseName)
        val databaseDirectory = databaseFile.parentFile
            ?: error("Robolectric did not provide a database directory")
        check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
            "Could not create Robolectric database directory: ${databaseDirectory.absolutePath}"
        }
        context.deleteDatabase(testDatabaseName)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(testDatabaseName)
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
        addWifi("aa:bb:cc:dd:ee:06", "ImportedManual", firstScan, localSourceId, positionSource = "imported-manual-edit")

        addWifi("aa:bb:cc:dd:ee:11", "Office-1", secondScan, localSourceId)
        addWifi("aa:bb:cc:dd:ee:12", "Office-2", secondScan, localSourceId)

        val groups = database!!.loadBeaconDbRefinementSnapshots(nowMillis = now, maxGroups = 10)

        assertEquals(2, groups.size)
        assertEquals(3, groups[0].size)
        assertEquals(2, groups[1].size)
        assertEquals(
            setOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02", "aa:bb:cc:dd:ee:03"),
            groups[0].map { it.identifier }.toSet()
        )
        assertEquals(setOf("aa:bb:cc:dd:ee:11", "aa:bb:cc:dd:ee:12"), groups[1].map { it.identifier }.toSet())
        assertFalse(groups.flatten().any {
            it.identifier.endsWith(":04") || it.identifier.endsWith(":05") || it.identifier.endsWith(":06")
        })
    }

    @Test
    fun manualBeaconDbSelectionFallsBackToNearbyStoredPointsWhenNoRecentScanBatchExists() {
        openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val importedSourceId = sqlite!!.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "wigle")
                put("name", "Imported Wi-Fi test source")
                put("created_at", System.currentTimeMillis())
                put("last_seen", System.currentTimeMillis())
                put("enabled", 1)
                put("trust_weight", 1.0)
                put("fingerprint", "test-nearby-wifi-import")
            }
        )
        val now = System.currentTimeMillis()
        addWifiPointOnly("a8:bb:cc:dd:ee:21", "Nearby-local", now - 5_000L, localSourceId, 52.0000, 5.0000)
        addWifiPointOnly("a8:bb:cc:dd:ee:22", "Nearby-imported", now - 8_000L, importedSourceId, 52.0002, 5.0002, "wigle-import")
        addWifiPointOnly("a8:bb:cc:dd:ee:23", "Far-away", now - 4_000L, localSourceId, 52.1000, 5.1000)
        addWifiPointOnly("a8:bb:cc:dd:ee:24", "<unknown ssid>", now - 3_000L, localSourceId, 52.0001, 5.0001)
        addWifiPointOnly("a8:bb:cc:dd:ee:25", "Owner_nomap", now - 2_000L, localSourceId, 52.0001, 5.0001)
        addWifiPointOnly("a8:bb:cc:dd:ee:26", "Manual", now - 1_000L, localSourceId, 52.0001, 5.0001, "manual-edit")

        val selection = database!!.loadBeaconDbManualRefinementSelection(nowMillis = now, maxGroups = 10)
        println(
            "BeaconDB nearby selection: total=${selection.totalWifiPoints}, " +
                "fresh=${selection.freshLocatedWifiPoints}, usable=${selection.usableStoredWifiPoints}, " +
                "recent=${selection.recentScanGroupCount}, stored=${selection.storedPointGroupCount}, " +
                "nearby=${selection.nearbyGroupCount}, largest=${selection.largestNearbyGroupSize}, " +
                "groups=${selection.groups.map { group -> group.map { it.identifier } }}"
        )

        assertEquals(0, selection.recentScanGroupCount)
        assertEquals(0, selection.storedPointGroupCount)
        assertEquals(
            "Nearby-group diagnostics: fresh=${selection.freshLocatedWifiPoints}, " +
                "usable=${selection.usableStoredWifiPoints}, " +
                "largest=${selection.largestNearbyGroupSize}, groups=${selection.groups.map { group -> group.map { it.identifier } }}",
            1,
            selection.nearbyGroupCount
        )
        assertEquals(3, selection.largestNearbyGroupSize)
        assertEquals(6, selection.totalWifiPoints)
        assertEquals(5, selection.freshLocatedWifiPoints)
        assertEquals(4, selection.usableStoredWifiPoints)
        assertEquals(1, selection.groups.size)
        assertEquals(
            setOf("a8:bb:cc:dd:ee:21", "a8:bb:cc:dd:ee:22", "a8:bb:cc:dd:ee:24"),
            selection.groups.single().map { it.identifier }.toSet()
        )
        assertTrue(selection.groups.single().all { it.selectionKind == "nearby-database" })
    }

    @Test
    fun manualBeaconDbStoredPointFallbackDoesNotMergeDifferentSources() {
        openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val importedSourceId = sqlite!!.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "wigle")
                put("name", "Separate stored-point source")
                put("created_at", System.currentTimeMillis())
                put("last_seen", System.currentTimeMillis())
                put("enabled", 1)
                put("trust_weight", 1.0)
                put("fingerprint", "test-separate-stored-point-source")
            }
        )
        val sharedLastSeen = System.currentTimeMillis() - 10_000L
        addWifiPointSnapshotOnly("a8:bb:cc:dd:ee:51", "Local", sharedLastSeen, localSourceId)
        addWifiPointSnapshotOnly("a8:bb:cc:dd:ee:52", "Imported", sharedLastSeen, importedSourceId)

        val selection = database!!.loadBeaconDbManualRefinementSelection(
            nowMillis = System.currentTimeMillis(),
            maxGroups = 10
        )

        assertEquals(0, selection.recentScanGroupCount)
        assertEquals(0, selection.storedPointGroupCount)
        assertEquals(0, selection.nearbyGroupCount)
        assertTrue(selection.groups.isEmpty())
    }

    @Test
    fun manualBeaconDbSelectionRebuildsGroupFromStoredPointsWhenObservationHistoryIsMissing() {
        openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        val sharedLastSeen = now - 15_000L
        addWifiPointSnapshotOnly("a8:bb:cc:dd:ee:41", "", sharedLastSeen, localSourceId)
        addWifiPointSnapshotOnly("a8:bb:cc:dd:ee:42", "<unknown ssid>", sharedLastSeen, localSourceId)
        addWifiPointSnapshotOnly("a8:bb:cc:dd:ee:43", "Known network", sharedLastSeen, localSourceId)

        val selection = database!!.loadBeaconDbManualRefinementSelection(nowMillis = now, maxGroups = 10)

        assertEquals(0, selection.recentScanGroupCount)
        assertEquals(1, selection.storedPointGroupCount)
        assertEquals(0, selection.nearbyGroupCount)
        assertEquals(0, selection.freshLocatedWifiPoints)
        assertEquals(0, selection.usableStoredWifiPoints)
        assertEquals(1, selection.groups.size)
        assertEquals(
            setOf("a8:bb:cc:dd:ee:41", "a8:bb:cc:dd:ee:42", "a8:bb:cc:dd:ee:43"),
            selection.groups.single().map { it.identifier }.toSet()
        )
        assertTrue(selection.groups.single().all { it.selectionKind == "stored-snapshot" })
    }

    @Test
    fun beaconDbRefinementNeverOverwritesImportedManualCoordinate() {
        openDatabase()
        val localSourceId = database!!.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        val identifier = "a8:bb:cc:dd:ee:31"
        addWifiPointOnly(
            identifier, "Imported manual", now, localSourceId, 52.0000, 5.0000,
            positionSource = "imported-manual-edit"
        )

        val saved = database!!.saveAutoRefinementBatch(
            provider = "beacondb",
            sourceName = "BeaconDB test",
            fingerprint = "beacondb-test-imported-manual",
            trustWeight = 0.20,
            records = listOf(
                RadioAutoRefinementRecord(
                    provider = "beacondb",
                    radioType = "wifi",
                    identifier = identifier,
                    latitude = 52.0010,
                    longitude = 5.0010,
                    accuracyMeters = 250.0,
                    observedAtMillis = now
                )
            )
        )
        val source = sqlite!!.rawQuery(
            "SELECT position_source FROM radio_points WHERE identifier = ?",
            arrayOf(identifier)
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

        assertEquals(0, saved)
        assertEquals("imported-manual-edit", source)
        val refinementCount = sqlite!!.rawQuery(
            "SELECT COUNT(*) FROM radio_auto_refinements WHERE identifier = ?",
            arrayOf(identifier)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
        assertEquals(0, refinementCount)
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
        addCell("lte:262:01:100:1004", localSourceId, positionSource = "imported-manual-edit")
        val candidates = database!!.listOpenCellIdRefinementCandidates()

        assertEquals(1, candidates.size)
        assertEquals("lte:262:01:100:1001", candidates.single().identifier)
        assertFalse(candidates.any { it.identifier.endsWith("1002") })
        assertFalse(candidates.any { it.identifier.endsWith("1003") })
        assertFalse(candidates.any { it.identifier.endsWith("1004") })
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
        val databaseFile = context.getDatabasePath(testDatabaseName)
        check(databaseFile.parentFile?.isDirectory == true) {
            "Robolectric database directory is missing: ${databaseFile.absolutePath}"
        }
        val value = LocalRadioDatabase(context, testDatabaseName)
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

    private fun addWifiPointSnapshotOnly(
        identifier: String,
        ssid: String,
        lastSeen: Long,
        sourceId: Long
    ) {
        sqlite!!.insertOrThrow("radio_points", null, ContentValues().apply {
            put("radio_type", "wifi")
            put("identifier", identifier)
            put("label", ssid)
            put("ssid", ssid)
            put("notes", "")
            putNull("latitude")
            putNull("longitude")
            putNull("accuracy")
            put("last_rssi", -58)
            put("peak_rssi", -52)
            put("frequency", 2_412)
            put("first_seen", lastSeen)
            put("last_seen", lastSeen)
            put("sightings", 1)
            put("position_source", "unlocated")
            put("source_id", sourceId)
            putNull("position_observed_at")
        })
    }

    private fun addWifiPointOnly(
        identifier: String,
        ssid: String,
        observedAt: Long,
        sourceId: Long,
        latitude: Double,
        longitude: Double,
        positionSource: String = "android-location"
    ) {
        sqlite!!.insertOrThrow("radio_points", null, ContentValues().apply {
            put("radio_type", "wifi")
            put("identifier", identifier)
            put("label", ssid)
            put("ssid", ssid)
            put("notes", "")
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", 80.0)
            put("last_rssi", -55)
            put("peak_rssi", -50)
            put("frequency", 2_412)
            put("first_seen", observedAt)
            put("last_seen", observedAt)
            put("sightings", 2)
            put("position_source", positionSource)
            put("source_id", sourceId)
            put("position_observed_at", observedAt)
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

}
