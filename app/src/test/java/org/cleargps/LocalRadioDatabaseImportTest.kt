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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalRadioDatabaseImportTest {
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
    fun reimportingIdenticalWigleCsvReusesSourceAndDoesNotDuplicateRows() {
        val csv = wigleCsv(
            "aa:bb:cc:dd:ee:01,Home,52.274,40.452,2026-01-02 03:04:05,2412,-55,WIFI",
            "aa:bb:cc:dd:ee:02,Office,52.275,40.453,2026-01-02 03:05:05,2437,-68,WIFI"
        )

        val first = importCsv(csv)
        val second = importCsv(csv)

        assertEquals(2, first.observationsImported)
        assertEquals(2, first.uniqueRadios)
        assertNotNull(first.sourceId)
        assertEquals(0, second.observationsImported)
        assertEquals(2, second.uniqueRadios)
        assertEquals(first.sourceId, second.sourceId)
        assertEquals(1, countRows("radio_sources", "kind = ?", "wigle"))
        assertEquals(2, countRows("radio_observations", "source_id = ?", first.sourceId.toString()))
        assertEquals(2, countRows("radio_points", "radio_type = ?", "wifi"))
    }

    @Test
    fun invalidCoordinateRowIsSkippedAndUnknownCoordinatesStayNull() {
        val csv = wigleCsv(
            "aa:bb:cc:dd:ee:11,Home,52.274,40.452,2026-01-02 03:04:05,2412,-55,WIFI",
            "aa:bb:cc:dd:ee:12,Hidden,,,,2412,-70,WIFI",
            "aa:bb:cc:dd:ee:13,Bad,91,40.0,2026-01-02 03:06:05,2412,-65,WIFI"
        )

        val result = importCsv(csv)
        val sourceId = result.sourceId!!

        assertEquals(2, result.observationsImported)
        assertEquals(2, result.uniqueRadios)
        assertEquals(2, countRows("radio_observations", "source_id = ?", sourceId.toString()))
        assertEquals(0, countRows("radio_points", "identifier = ?", "aa:bb:cc:dd:ee:13"))
        assertNull(readPointCoordinate("aa:bb:cc:dd:ee:12", "latitude"))
        assertNull(readPointCoordinate("aa:bb:cc:dd:ee:12", "longitude"))
        assertEquals("unlocated", readPointPositionSource("aa:bb:cc:dd:ee:12"))
    }

    @Test
    fun identicalRadioImportedFromDifferentFilesKeepsBothObservationOrigins() {
        val csvOne = wigleCsv(
            "aa:bb:cc:dd:ee:21,First,52.274,40.452,2026-01-02 03:04:05,2412,-48,WIFI"
        )
        val csvTwo = wigleCsv(
            "aa:bb:cc:dd:ee:21,Second,52.280,40.460,2026-01-03 04:04:05,2412,-75,WIFI"
        )

        val first = importCsv(csvOne)
        val second = importCsv(csvTwo)

        assertNotNull(first.sourceId)
        assertNotNull(second.sourceId)
        assertFalse(first.sourceId == second.sourceId)
        assertEquals(2, countRows(
            "radio_observations", "radio_type = ? AND identifier = ?", "wifi", "aa:bb:cc:dd:ee:21"
        ))
        assertEquals(1, countRows(
            "radio_observations", "source_id = ?", first.sourceId.toString()
        ))
        assertEquals(1, countRows(
            "radio_observations", "source_id = ?", second.sourceId.toString()
        ))
        assertEquals(1, countRows(
            "radio_points", "radio_type = ? AND identifier = ?", "wifi", "aa:bb:cc:dd:ee:21"
        ))
        assertEquals("wigle-import", readPointPositionSource("aa:bb:cc:dd:ee:21"))
        // A later, weaker sample from another source must not replace a stronger existing position.
        assertEquals(52.274, readPointCoordinate("aa:bb:cc:dd:ee:21", "latitude")!!, 0.000001)
        assertEquals(40.452, readPointCoordinate("aa:bb:cc:dd:ee:21", "longitude")!!, 0.000001)
    }

    @Test
    fun rejectedCsvImportRollsBackAndKeepsPendingDeletionUndo() {
        val seed = seedLocalRows()
        val deleted = database.deleteRadioSourceSegment(seed.sourceId, seed.dayKey, "wifi")
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)
        assertEquals(0, countRows("radio_observations", "id = ?", seed.observationId.toString()))

        val allInvalid = wigleCsv(
            "aa:bb:cc:dd:ee:31,Bad,91,40.0,2026-01-02 03:06:05,2412,-65,WIFI"
        )
        var rejected = false
        try {
            importCsv(allInvalid)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("CSV with no valid rows must be rejected", rejected)
        assertTrue("failed import should leave prior Undo usable", database.undoRadioSourceDeletion(undoToken!!))
        assertEquals(1, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(1, countRows("radio_source_segment_settings",
            "source_id = ? AND day_key = ? AND radio_type = ?",
            seed.sourceId.toString(), seed.dayKey, "wifi"))
        assertEquals(1, countRows("radio_sources", "kind = ?", "local"))
        assertEquals(0, countRows("radio_sources", "kind = ?", "wigle"))
    }

    @Test
    fun rejectedNonSqliteFileKeepsPendingDeletionUndo() {
        val seed = seedLocalRows()
        val deleted = database.deleteRadioSourceSegment(seed.sourceId, seed.dayKey, "wifi")
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)
        assertEquals(0, countRows("radio_observations", "id = ?", seed.observationId.toString()))

        var rejected = false
        try {
            database.importSqliteDatabase(ByteArrayInputStream("not a SQLite database".toByteArray(Charsets.UTF_8)))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Non-SQLite input must be rejected", rejected)
        assertTrue("Rejected SQLite input must preserve prior Undo", database.undoRadioSourceDeletion(undoToken!!))
        assertEquals(1, countRows("radio_observations", "id = ?", seed.observationId.toString()))
        assertEquals(1, countRows(
            "radio_source_segment_settings",
            "source_id = ? AND day_key = ? AND radio_type = ?",
            seed.sourceId.toString(), seed.dayKey, "wifi"
        ))
        assertEquals(1, countRows("radio_sources", "kind = ?", "local"))
        assertEquals(0, countRows("radio_sources", "kind = ?", "wigle"))
        assertEquals(0, countRows("radio_sources", "kind = ?", "custom"))
    }

    @Test
    fun unsupportedButValidSqliteSchemaKeepsPendingDeletionUndo() {
        val seed = seedLocalRows()
        val deleted = database.deleteRadioSourceSegment(seed.sourceId, seed.dayKey, "wifi")
        val undoToken = deleted.undoToken
        assertNotNull(undoToken)
        assertEquals(0, countRows("radio_observations", "id = ?", seed.observationId.toString()))

        val unsupportedFile = File.createTempFile("clear-gps-unsupported-", ".sqlite", context.cacheDir)
        try {
            val unsupportedDb = SQLiteDatabase.openOrCreateDatabase(unsupportedFile.absolutePath, null)
            try {
                unsupportedDb.execSQL("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)")
            } finally {
                unsupportedDb.close()
            }

            var rejected = false
            try {
                database.importSqliteDatabase(ByteArrayInputStream(unsupportedFile.readBytes()))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Valid SQLite with an unsupported schema must be rejected", rejected)
            assertTrue("Unsupported schema must preserve prior Undo", database.undoRadioSourceDeletion(undoToken!!))
            assertEquals(1, countRows("radio_observations", "id = ?", seed.observationId.toString()))
            assertEquals(1, countRows(
                "radio_source_segment_settings",
                "source_id = ? AND day_key = ? AND radio_type = ?",
                seed.sourceId.toString(), seed.dayKey, "wifi"
            ))
            assertEquals(1, countRows("radio_sources", "kind = ?", "local"))
            assertEquals(0, countRows("radio_sources", "kind = ?", "wigle"))
            assertEquals(0, countRows("radio_sources", "kind = ?", "custom"))
        } finally {
            unsupportedFile.delete()
            File(unsupportedFile.absolutePath + "-wal").delete()
            File(unsupportedFile.absolutePath + "-shm").delete()
        }
    }


    @Test
    fun importsNeoStumblerZipWithWifiCellsAndBluetoothAndReusesSource() {
        val archive = neoStumblerZip(
            "wifis.csv" to (
                "timestamp,latitude,longitude,locationAccuracy,macAddress,frequency,signalStrength,ssid,wifiScanAge\n" +
                    "2026-01-02T03:04:05Z,52.274,40.452,8.5,aa:bb:cc:dd:ee:01,2412,-55,Home,1200\n"
                ).toByteArray(),
            "cells.csv" to (
                "timestamp,latitude,longitude,locationAccuracy,cellId,radioType,mcc,mnc,lac,psc,signalStrength,asu,arfcn,cellScanAge\n" +
                    "2026-01-02T03:04:05Z,52.275,40.453,35,456789,lte,262,01,123,, -91,15,1300,2500\n"
                ).toByteArray(),
            "beacons.csv" to (
                "timestamp,latitude,longitude,locationAccuracy,macAddress,beaconType,id1,id2,id3,bluetoothScanAge,signalStrength\n" +
                    "2026-01-02T03:04:05Z,52.276,40.454,10,aa:bb:cc:dd:ee:03, iBeacon,uuid,1,2,500,-63\n"
                ).toByteArray()
        )

        val first = database.importRadioCsv(ByteArrayInputStream(archive))
        val second = database.importRadioCsv(ByteArrayInputStream(archive))

        assertEquals("NeoStumbler CSV (ZIP)", first.format)
        assertEquals(3, first.observationsImported)
        assertEquals(3, first.uniqueRadios)
        assertEquals(0, second.observationsImported)
        assertEquals(first.sourceId, second.sourceId)
        assertEquals(1, countRows("radio_sources", "kind = ? AND name = ?", "custom", "NeoStumbler CSV"))
        assertEquals(1, countRows("radio_observations", "radio_type = ?", "wifi"))
        assertEquals(1, countRows("radio_observations", "radio_type = ?", "cell"))
        assertEquals(1, countRows("radio_observations", "radio_type = ?", "ble"))
        assertEquals(1, countRows("radio_points", "identifier = ?", "lte:262:1:123:456789"))
        assertEquals("neostumbler-import", readPointPositionSource("aa:bb:cc:dd:ee:01"))
    }

    @Test
    fun importsStandaloneNeoStumblerWifiCsv() {
        val csv = (
            "timestamp,latitude,longitude,locationAccuracy,macAddress,frequency,signalStrength,ssid,wifiScanAge\n" +
                "2026-01-02T03:04:05Z,52.274,40.452,8.5,aa:bb:cc:dd:ee:11,5180,-48,Office,1000\n"
            )
        val result = database.importRadioCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        assertEquals("NeoStumbler CSV", result.format)
        assertEquals(1, result.observationsImported)
        assertEquals(1, countRows("radio_points", "radio_type = ? AND identifier = ?", "wifi", "aa:bb:cc:dd:ee:11"))
    }

    @Test
    fun localListAndMapUseTheSameDatasetWithCoordinateFilteringOnly() {
        val now = System.currentTimeMillis()
        val localSourceId = database.listRadioSources().single { it.kind == "local" }.id
        sqlite.beginTransaction()
        try {
            repeat(1_700) { index ->
                val identifier = index.toString(16).padStart(12, '0').chunked(2).joinToString(":")
                sqlite.insertOrThrow(
                    "radio_points",
                    null,
                    ContentValues().apply {
                        put("radio_type", "wifi")
                        put("identifier", identifier)
                        put("label", "map test")
                        put("ssid", "map test")
                        put("notes", "")
                        put("latitude", 52.0 + index / 1_000_000.0)
                        put("longitude", 5.0)
                        put("accuracy", 10.0)
                        put("last_rssi", -55)
                        put("peak_rssi", -50)
                        put("frequency", 2412)
                        put("first_seen", now)
                        put("last_seen", now - index)
                        put("sightings", 1)
                        put("position_source", "test")
                    }
                )
            }
            sqlite.insertOrThrow(
                "radio_points",
                null,
                ContentValues().apply {
                    put("radio_type", "ble")
                    put("identifier", "aa:bb:cc:dd:ee:ff")
                    put("label", "unlocated BLE")
                    put("ssid", "")
                    put("notes", "")
                    putNull("latitude")
                    putNull("longitude")
                    putNull("accuracy")
                    put("last_rssi", -60)
                    put("peak_rssi", -55)
                    put("frequency", 0)
                    put("first_seen", now)
                    put("last_seen", now + 1L)
                    put("sightings", 1)
                    put("position_source", "unlocated")
                    put("source_id", localSourceId)
                }
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }

        val repository = RadioRepository(database)
        val localPoints = repository.loadLocalPoints(limit = RadioRepository.DEFAULT_LIST_LIMIT)
        val mapPoints = repository.loadMapPoints(limit = 5_000)
        val counts = repository.pointCounts()

        assertEquals(1_701, localPoints.size)
        assertEquals(1_700, mapPoints.size)
        assertEquals(1_701, counts.total)
        assertEquals(1_700, counts.geolocated)
        assertTrue(localPoints.any { it.type == "ble" && it.identifier == "aa:bb:cc:dd:ee:ff" })
        assertTrue(mapPoints.none { it.identifier == "aa:bb:cc:dd:ee:ff" })
        val indexExists = sqlite.rawQuery("PRAGMA index_list('radio_observations')", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "radio_obs_import_dedupe") {
                    found = true
                    break
                }
            }
            found
        }
        assertTrue("Observation duplicate lookup index must be present", indexExists)
    }

    private fun neoStumblerZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class Seed(val sourceId: Long, val dayKey: String, val observationId: Long)

    private fun seedLocalRows(): Seed {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val observedAt = System.currentTimeMillis()
        val dayKey = sqlite.rawQuery(
            "SELECT strftime('%Y-%m-%d', ? / 1000, 'unixepoch', 'localtime')",
            arrayOf(observedAt.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        sqlite.insertOrThrow(
            "radio_points",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", "aa:bb:cc:dd:ee:41")
                put("label", "seed")
                put("ssid", "seed")
                put("notes", "")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 10.0)
                put("last_rssi", -55)
                put("peak_rssi", -50)
                put("frequency", 2412)
                put("first_seen", observedAt)
                put("last_seen", observedAt)
                put("sightings", 1)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
                put("position_observed_at", observedAt)
            }
        )
        val observationId = sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", "aa:bb:cc:dd:ee:41")
                put("latitude", 52.274)
                put("longitude", 40.452)
                put("accuracy", 10.0)
                put("rssi", -55)
                put("frequency", 2412)
                put("observed_at", observedAt)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
            }
        )
        sqlite.insertOrThrow(
            "radio_source_segment_settings",
            null,
            ContentValues().apply {
                put("source_id", sourceId)
                put("day_key", dayKey)
                put("radio_type", "wifi")
                put("enabled", 0)
                put("trust_weight", 0.35)
            }
        )
        return Seed(sourceId, dayKey, observationId)
    }

    private fun importCsv(csv: String): RadioImportResult =
        database.importWigleCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))

    private fun wigleCsv(vararg rows: String): String = buildString {
        append("MAC,SSID,CurrentLatitude,CurrentLongitude,FirstSeen,Frequency,RSSI,Type\n")
        rows.forEach { append(it).append('\n') }
    }

    private fun countRows(table: String, where: String, vararg args: String): Int =
        sqlite.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun readPointCoordinate(identifier: String, column: String): Double? {
        require(column == "latitude" || column == "longitude")
        return sqlite.rawQuery(
            "SELECT $column FROM radio_points WHERE radio_type = 'wifi' AND identifier = ?",
            arrayOf(identifier)
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getDouble(0)
        }
    }

    private fun readPointPositionSource(identifier: String): String? =
        sqlite.rawQuery(
            "SELECT position_source FROM radio_points WHERE radio_type = 'wifi' AND identifier = ?",
            arrayOf(identifier)
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getString(0) }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
    }
}
