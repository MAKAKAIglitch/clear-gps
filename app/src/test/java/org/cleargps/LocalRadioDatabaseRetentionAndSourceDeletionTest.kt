package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun deletingOneAutoRefinementKeepsObservationsAndRemovesOnlyDerivedCoordinate() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        val identifier = "aa:bb:cc:dd:ee:c8"
        val pointId = sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("label", "auto-refined fixture")
                put("ssid", "auto-network")
                put("notes", "")
                putNull("latitude")
                putNull("longitude")
                putNull("accuracy")
                put("last_rssi", -61)
                put("peak_rssi", -58)
                put("frequency", 2412)
                put("first_seen", now - 10_000L)
                put("last_seen", now)
                put("sightings", 2)
                put("position_source", "unlocated")
                putNull("source_id")
                putNull("position_observed_at")
            }
        )
        // Keep the training observation unlocated; only the separate refinement carries coordinates.
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                putNull("latitude")
                putNull("longitude")
                putNull("accuracy")
                put("rssi", -61)
                put("frequency", 2412)
                put("observed_at", now)
                put("position_source", "unlocated")
                put("source_id", localSourceId)
            }
        )

        val fingerprint = "beacondb-auto-refined-v1:point-delete:$now"
        assertEquals(
            1,
            database.saveAutoRefinementBatch(
                provider = "beacondb",
                sourceName = "BeaconDB point-delete fixture",
                fingerprint = fingerprint,
                trustWeight = 1.0,
                records = listOf(
                    RadioAutoRefinementRecord(
                        provider = "beacondb",
                        radioType = "wifi",
                        identifier = identifier,
                        latitude = 52.280,
                        longitude = 40.460,
                        accuracyMeters = 30.0,
                        observedAtMillis = now
                    )
                )
            )
        )

        val refined = database.findPoint(pointId) ?: throw AssertionError("Auto-refined point disappeared")
        assertEquals("beacondb-auto-refined", refined.positionSource)
        assertEquals(52.280, refined.latitude!!, 0.000001)
        assertEquals(1, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?", "wifi", identifier
        ))

        assertEquals(1, database.deleteAutoRefinementForPoint("wifi", identifier))

        val restored = database.findPoint(pointId) ?: throw AssertionError("Observed radio point was deleted")
        assertEquals("unlocated", restored.positionSource)
        assertNull(restored.latitude)
        assertNull(restored.longitude)
        assertNull(restored.sourceId)
        assertEquals(1, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?", "wifi", identifier
        ))
        assertEquals(0, countRows(
            "radio_auto_refinements", "radio_type = ? AND identifier = ?", "wifi", identifier
        ))
        assertEquals(0, countRows("radio_sources", "fingerprint = ?", fingerprint))
    }

    @Test
    fun deletingStaleAutoRefinementCannotChangeManualEditedCoordinate() {
        val now = System.currentTimeMillis()
        val identifier = "aa:bb:cc:dd:ee:c9"
        val sourceFingerprint = "beacondb-auto-refined-v1:manual-pin:$now"
        val sourceId = sqlite.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "beacondb")
                put("name", "Stale BeaconDB manual pin fixture")
                put("created_at", now)
                put("last_seen", now)
                put("enabled", 1)
                put("trust_weight", 0.2)
                put("fingerprint", sourceFingerprint)
            }
        )
        val pointId = sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("label", "manual pin")
                put("ssid", "manual-network")
                put("notes", "")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 3.0)
                put("last_rssi", -55)
                put("peak_rssi", -50)
                put("frequency", 2412)
                put("first_seen", now - 10_000L)
                put("last_seen", now)
                put("sightings", 5)
                put("position_source", "manual-edit")
                putNull("source_id")
                putNull("position_observed_at")
            }
        )
        sqlite.insertOrThrow(
            "radio_auto_refinements",
            null,
            ContentValues().apply {
                put("source_id", sourceId)
                put("provider", "beacondb")
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("latitude", 52.280)
                put("longitude", 40.460)
                put("accuracy", 25.0)
                put("observed_at", now)
            }
        )

        assertEquals(1, database.deleteAutoRefinementForPoint("wifi", identifier))

        val manual = database.findPoint(pointId) ?: throw AssertionError("Manual point disappeared")
        assertEquals("manual-edit", manual.positionSource)
        assertEquals(52.274, manual.latitude!!, 0.000001)
        assertEquals(40.452, manual.longitude!!, 0.000001)
        assertEquals(0, countRows(
            "radio_auto_refinements", "radio_type = ? AND identifier = ?", "wifi", identifier
        ))
        assertEquals(0, countRows("radio_sources", "id = ?", sourceId.toString()))
    }

    @Test
    fun listAndSearchApplyObservationSortBeforeLimit() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val baseTime = 1_800_000_000_000L
        insertSortPoint(localSourceId, "sort-low", "Zeta", baseTime, baseTime + 500L, 2, -35, 8.0)
        insertSortPoint(localSourceId, "sort-high", "Alpha", baseTime + 1L, baseTime + 300L, 12, -90, null)
        insertSortPoint(localSourceId, "sort-middle", "Beta", baseTime + 2L, baseTime + 700L, 5, -58, 35.0)

        val mostObserved = RadioPointSort(RadioPointSortField.SIGHTINGS, descending = true)
        assertEquals(
            "sort-high",
            database.listPoints(limit = 1, sort = mostObserved).single().identifier
        )
        assertEquals(
            listOf("sort-high", "sort-middle", "sort-low"),
            database.searchPoints("sort-", limit = 3, sort = mostObserved).map { it.identifier }
        )
        assertEquals(
            listOf("sort-low", "sort-middle", "sort-high"),
            database.listPoints(
                limit = 3,
                sort = RadioPointSort(RadioPointSortField.SIGHTINGS, descending = false)
            ).map { it.identifier }
        )
    }

    @Test
    fun signalAccuracyNameAndDateSortsUseStableNullSafeOrder() {
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        val baseTime = 1_800_000_100_000L
        insertSortPoint(localSourceId, "sort-low", "Zeta", baseTime, baseTime + 500L, 2, -35, 8.0)
        insertSortPoint(localSourceId, "sort-high", "Alpha", baseTime + 1L, baseTime + 300L, 12, -90, null)
        insertSortPoint(localSourceId, "sort-middle", "Beta", baseTime + 2L, baseTime + 700L, 5, -58, 35.0)

        assertEquals(
            listOf("sort-low", "sort-middle", "sort-high"),
            database.listPoints(sort = RadioPointSort(RadioPointSortField.SIGNAL, true))
                .map { it.identifier }
        )
        assertEquals(
            listOf("sort-low", "sort-middle", "sort-high"),
            database.listPoints(sort = RadioPointSort(RadioPointSortField.ACCURACY, false))
                .map { it.identifier }
        )
        assertEquals(
            listOf("sort-middle", "sort-low", "sort-high"),
            database.listPoints(sort = RadioPointSort(RadioPointSortField.ACCURACY, true))
                .map { it.identifier }
        )
        assertEquals(
            listOf("sort-high", "sort-middle", "sort-low"),
            database.listPoints(sort = RadioPointSort(RadioPointSortField.NAME, false))
                .map { it.identifier }
        )
        assertEquals(
            listOf("sort-middle", "sort-low", "sort-high"),
            database.listPoints(sort = RadioPointSort(RadioPointSortField.LAST_SEEN, true))
                .map { it.identifier }
        )
    }

    private fun insertSortPoint(
        sourceId: Long,
        identifier: String,
        label: String,
        firstSeen: Long,
        lastSeen: Long,
        sightings: Int,
        rssi: Int,
        accuracy: Double?
    ) {
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", identifier)
                put("label", label)
                put("ssid", "")
                put("notes", "")
                putNull("latitude")
                putNull("longitude")
                if (accuracy == null) putNull("accuracy") else put("accuracy", accuracy)
                put("last_rssi", rssi)
                put("peak_rssi", rssi)
                put("frequency", 2412)
                put("first_seen", firstSeen)
                put("last_seen", lastSeen)
                put("sightings", sightings)
                put("position_source", "local-test")
                put("source_id", sourceId)
                putNull("position_observed_at")
            }
        )
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
