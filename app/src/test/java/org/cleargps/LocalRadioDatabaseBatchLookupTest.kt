package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun fingerprintCacheReusesSamplesAndInvalidatesOnLocatedObservationChanges() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val observedAt = System.currentTimeMillis() - 5_000L
        insertFingerprintObservation("wifi", "aa:bb:cc:dd:ee:71", sourceId, observedAt, 52.0, 4.0)
        insertFingerprintObservation("cell", "lte:250:1:123:456789", sourceId, observedAt, 52.0001, 4.0001)

        val signals = listOf(
            RadioSignal("wifi", "aa:bb:cc:dd:ee:71", -52, 2412, "Test"),
            RadioSignal("cell", "lte:250:1:123:456789", -82, 0)
        )
        val first = database.evaluatePosition(signals, useWifi = true, useCells = true, useBle = false)
        assertNotNull(first.estimate)
        assertFalse(first.fingerprintCacheHit)

        val second = database.evaluatePosition(signals, useWifi = true, useCells = true, useBle = false)
        assertTrue(second.fingerprintCacheHit)
        assertEquals(0L, second.fingerprintCandidateQueryDurationMillis)
        assertEquals(0L, second.fingerprintExpansionQueryDurationMillis)
        assertEquals(first.fingerprintSampleCount, second.fingerprintSampleCount)

        // Unlocated passive observations cannot affect historical fingerprints or their cache.
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "wifi")
                put("identifier", "aa:bb:cc:dd:ee:72")
                putNull("latitude")
                putNull("longitude")
                put("rssi", -70)
                put("frequency", 2412)
                put("observed_at", observedAt)
                put("position_source", "unlocated")
                put("source_id", sourceId)
            }
        )
        val afterUnlocatedInsert = database.evaluatePosition(
            signals, useWifi = true, useCells = true, useBle = false
        )
        assertTrue(afterUnlocatedInsert.fingerprintCacheHit)

        val wifiObservationId = sqlite.rawQuery(
            "SELECT id FROM radio_observations WHERE radio_type = 'wifi' AND identifier = ?",
            arrayOf("aa:bb:cc:dd:ee:71")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        sqlite.update(
            "radio_observations",
            ContentValues().apply { put("rssi", -35) },
            "id = ?",
            arrayOf(wifiObservationId.toString())
        )
        val afterLocatedUpdate = database.evaluatePosition(
            signals, useWifi = true, useCells = true, useBle = false
        )
        assertFalse(afterLocatedUpdate.fingerprintCacheHit)
    }

    @Test
    fun locatedFingerprintIndexesAreUsedByAllSqlStages() {
        assertTrue(indexExists("radio_obs_radio_compact_located_time"))
        assertTrue(indexExists("radio_obs_radio_exact_located_time"))
        assertTrue(indexExists("radio_obs_located_time"))

        val compactPlan = sqlite.rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT o.observed_at
            FROM (SELECT ? AS radio_type, ? AS compact_identifier) r
            JOIN radio_observations o INDEXED BY radio_obs_radio_compact_located_time
              ON o.radio_type = r.radio_type
              AND replace(replace(replace(lower(o.identifier), ':', ''), '-', ''), '.', '') = r.compact_identifier
            WHERE o.observed_at >= ? AND o.observed_at <= ?
              AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
            """.trimIndent(),
            arrayOf("wifi", "aabbccddeeff", "0", Long.MAX_VALUE.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3).orEmpty()) }.joinToString(" ")
        }
        assertTrue(compactPlan.contains("radio_obs_radio_compact_located_time"))

        val exactPlan = sqlite.rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT o.observed_at
            FROM (SELECT ? AS radio_type, ? AS identifier) r
            JOIN radio_observations o INDEXED BY radio_obs_radio_exact_located_time
              ON o.radio_type = r.radio_type AND o.identifier = r.identifier
            WHERE o.observed_at >= ? AND o.observed_at <= ?
              AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
            """.trimIndent(),
            arrayOf("cell", "lte:250:1:123:456789", "0", Long.MAX_VALUE.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3).orEmpty()) }.joinToString(" ")
        }
        assertTrue(exactPlan.contains("radio_obs_radio_exact_located_time"))

        val expansionPlan = sqlite.rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT o.id FROM radio_observations o INDEXED BY radio_obs_located_time
            WHERE o.observed_at IN (?) AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
            """.trimIndent(),
            arrayOf("1")
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3).orEmpty()) }.joinToString(" ")
        }
        assertTrue(expansionPlan.contains("radio_obs_located_time"))
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
            type = "wifi",
            identifier = "aa:bb:cc:dd:ee:02",
            sourceId = sourceId,
            latitude = 52.00002,
            longitude = 4.00002,
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
            RadioSignal("wifi", "aa:bb:cc:dd:ee:02", -56, 2437, "Home"),
            RadioSignal("cell", "lte:250:1:123:456789", -82, 0),
            RadioSignal("ble", "aa:bb:cc:dd:ee:03", -64, 0)
        )
        val evaluation = database.evaluatePosition(
            signals = signals,
            useWifi = true,
            useCells = true,
            useBle = true
        )

        assertEquals(4, evaluation.matchCounts.knownRadios)
        assertEquals(3, evaluation.matchCounts.usableCoordinateRadios)
        assertEquals(4, evaluation.candidateSignalCount)
        assertTrue(evaluation.pointLookupDurationMillis >= 0L)
        assertTrue(evaluation.fingerprintQueryDurationMillis >= 0L)
        assertTrue(evaluation.fingerprintCandidateQueryDurationMillis >= 0L)
        assertTrue(evaluation.fingerprintExpansionQueryDurationMillis >= 0L)
        assertTrue(
            evaluation.fingerprintCandidateQueryDurationMillis +
                evaluation.fingerprintExpansionQueryDurationMillis <=
                evaluation.fingerprintQueryDurationMillis + 1L
        )
        assertTrue(evaluation.fingerprintCandidateTimestampCount <= 250)
        assertEquals(21, evaluation.fingerprintDatabaseVersion)
        assertTrue(evaluation.compactMacIndexAvailable)
        assertTrue(evaluation.fingerprintMacKeyCount > 0)
        assertTrue(evaluation.fingerprintExactKeyCount > 0)
        assertTrue(evaluation.fingerprintScoringDurationMillis >= 0L)
        assertTrue(evaluation.fallbackEstimateDurationMillis >= 0L)
        assertTrue(evaluation.fingerprintSampleCount >= 0)
        assertTrue(evaluation.elapsedMillis >= evaluation.pointLookupDurationMillis)

        val estimate = evaluation.estimate
        assertNotNull(estimate)
        assertEquals(3, estimate!!.matchedCount)
        assertEquals(setOf("wifi", "ble"), estimate.matchedRadioTypes)
        assertTrue(estimate.accuracyMeters > 0.0)

        // The compatibility entry points must produce the same result as the combined pass.
        assertEquals(
            evaluation.matchCounts,
            database.countMatchingPoints(
                signals = signals,
                useWifi = true,
                useCells = true,
                useBle = true,
                now = now
            )
        )
        val standaloneEstimate = database.estimatePosition(
            signals = signals,
            useWifi = true,
            useCells = true,
            useBle = true
        )
        assertNotNull(standaloneEstimate)
        assertEquals(estimate.matchedCount, standaloneEstimate!!.matchedCount)
        assertEquals(estimate.matchedRadioTypes, standaloneEstimate.matchedRadioTypes)
        assertEquals(estimate.latitude, standaloneEstimate.latitude, 0.000001)
        assertEquals(estimate.longitude, standaloneEstimate.longitude, 0.000001)
    }

    @Test
    fun evaluationPreservesLegacyDifferentMacStringFormsForEstimateButCountsOneRadio() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        insertPoint(
            type = "wifi",
            identifier = "aabbccddee09",
            sourceId = sourceId,
            latitude = 52.1000,
            longitude = 4.1000,
            observedAt = now
        )
        insertPoint(
            type = "ble",
            identifier = "aa:bb:cc:dd:ee:0a",
            sourceId = null,
            latitude = 52.10005,
            longitude = 4.10005,
            observedAt = now
        )

        val evaluation = database.evaluatePosition(
            signals = listOf(
                RadioSignal("wifi", "aa:bb:cc:dd:ee:09", -50, 2412, "Home"),
                RadioSignal("wifi", "AA-BB-CC-DD-EE-09", -53, 2437, "Home"),
                RadioSignal("ble", "aa:bb:cc:dd:ee:0a", -62, 0)
            ),
            useWifi = true,
            useCells = false,
            useBle = true
        )

        assertEquals(2, evaluation.matchCounts.knownRadios)
        assertEquals(2, evaluation.matchCounts.usableCoordinateRadios)
        // The legacy estimator counted both stored string forms; the diagnostics path
        // continued to regard them as one normalized radio identity.
        assertEquals(3, evaluation.candidateSignalCount)
        assertNotNull(evaluation.estimate)
        assertEquals(3, evaluation.estimate!!.matchedCount)
    }

    @Test
    fun fingerprintCandidateCteMatchesLegacyOrQueryForMacAliasesAndExactCellKeys() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val now = System.currentTimeMillis()
        val keys = listOf(
            "aa:bb:cc:dd:ee:41" to "wifi",
            "aabbccddee42" to "ble",
            "lte:250:1:123:456789" to "cell"
        )
        val timestamps = listOf(now - 30_000L, now - 20_000L, now - 10_000L)
        keys.forEachIndexed { index, (identifier, type) ->
            sqlite.insertOrThrow(
                "radio_observations",
                null,
                ContentValues().apply {
                    put("radio_type", type)
                    put("identifier", identifier)
                    put("latitude", 52.0 + index * 0.0001)
                    put("longitude", 4.0 + index * 0.0001)
                    put("accuracy", 12.0)
                    put("rssi", -55 - index)
                    put("frequency", if (type == "wifi") 2412 else 0)
                    put("observed_at", timestamps[index])
                    put("position_source", "manual-reference")
                    put("source_id", sourceId)
                }
            )
        }

        val legacySql = """
            SELECT o.observed_at
            FROM radio_observations o
            JOIN radio_sources s ON s.id = o.source_id
            LEFT JOIN radio_source_segment_settings sg ON sg.source_id = o.source_id
              AND sg.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime')
              AND sg.radio_type = o.radio_type
            WHERE s.enabled = 1 AND s.trust_weight > 0
              AND (sg.enabled IS NULL OR sg.enabled = 1)
              AND (sg.trust_weight IS NULL OR sg.trust_weight > 0)
              AND o.observed_at >= ? AND o.observed_at <= ?
              AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
              AND o.position_source NOT IN (?, ?, ?)
              AND (
                (o.radio_type = ? AND replace(replace(replace(lower(o.identifier), ':', ''), '-', ''), '.', '') = ?) OR
                (o.radio_type = ? AND replace(replace(replace(lower(o.identifier), ':', ''), '-', ''), '.', '') = ?) OR
                (o.radio_type = ? AND o.identifier = ?)
              )
            GROUP BY o.observed_at
            ORDER BY COUNT(DISTINCT o.radio_type || ':' || o.identifier) DESC, o.observed_at DESC
            LIMIT ?
        """.trimIndent()
        val startTime = now - 90L * 24 * 60 * 60 * 1_000L
        val commonArguments = listOf(
            startTime.toString(), now.toString(),
            "unlocated", "local-radio-estimate", "radio-estimate"
        )
        val legacyArguments = (
            commonArguments + listOf(
                "wifi", "aabbccddee41",
                "ble", "aabbccddee42",
                "cell", "lte:250:1:123:456789",
                "250"
            )
        ).toTypedArray()
        val cteSql = """
            WITH requested_mac(radio_type, compact_identifier) AS (VALUES (?, ?), (?, ?)),
                 requested_exact(radio_type, identifier) AS (VALUES (?, ?)),
                 candidates AS (
                    SELECT o.observed_at, o.radio_type, o.identifier
                    FROM requested_mac r
                    JOIN radio_observations o ON o.radio_type = r.radio_type
                      AND replace(replace(replace(lower(o.identifier), ':', ''), '-', ''), '.', '') = r.compact_identifier
                    JOIN radio_sources s ON s.id = o.source_id
                    LEFT JOIN radio_source_segment_settings sg ON sg.source_id = o.source_id
                      AND sg.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime')
                      AND sg.radio_type = o.radio_type
                    WHERE s.enabled = 1 AND s.trust_weight > 0
                      AND (sg.enabled IS NULL OR sg.enabled = 1)
                      AND (sg.trust_weight IS NULL OR sg.trust_weight > 0)
                      AND o.observed_at >= ? AND o.observed_at <= ?
                      AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
                      AND o.position_source NOT IN (?, ?, ?)
                    UNION ALL
                    SELECT o.observed_at, o.radio_type, o.identifier
                    FROM requested_exact r
                    JOIN radio_observations o ON o.radio_type = r.radio_type AND o.identifier = r.identifier
                    JOIN radio_sources s ON s.id = o.source_id
                    LEFT JOIN radio_source_segment_settings sg ON sg.source_id = o.source_id
                      AND sg.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime')
                      AND sg.radio_type = o.radio_type
                    WHERE s.enabled = 1 AND s.trust_weight > 0
                      AND (sg.enabled IS NULL OR sg.enabled = 1)
                      AND (sg.trust_weight IS NULL OR sg.trust_weight > 0)
                      AND o.observed_at >= ? AND o.observed_at <= ?
                      AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
                      AND o.position_source NOT IN (?, ?, ?)
                 )
            SELECT observed_at
            FROM candidates
            GROUP BY observed_at
            ORDER BY COUNT(DISTINCT radio_type || ':' || identifier) DESC, observed_at DESC
            LIMIT ?
        """.trimIndent()
        val cteArguments = (
            listOf(
                "wifi", "aabbccddee41",
                "ble", "aabbccddee42",
                "cell", "lte:250:1:123:456789"
            ) + commonArguments + commonArguments + "250"
        ).toTypedArray()

        val legacyRows = sqlite.rawQuery(legacySql, legacyArguments).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val cteRows = sqlite.rawQuery(cteSql, cteArguments).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        assertEquals(legacyRows, cteRows)
        assertEquals(timestamps.sortedDescending(), cteRows)
    }

    @Test
    fun fingerprintCandidateCteSupportsOnlyExactCellKeys() {
        val sourceId = database.listRadioSources().single { it.kind == "local" }.id
        val observedAt = System.currentTimeMillis() - 5_000L
        val identifiers = listOf(
            "lte:250:1:123:456789",
            "nr:250:1:321:987654"
        )
        identifiers.forEachIndexed { index, identifier ->
            sqlite.insertOrThrow(
                "radio_observations",
                null,
                ContentValues().apply {
                    put("radio_type", "cell")
                    put("identifier", identifier)
                    put("latitude", 52.1000 + index * 0.0001)
                    put("longitude", 4.1000 + index * 0.0001)
                    put("accuracy", if (index == 0) 12.0 else 18.0)
                    put("rssi", -80 - index)
                    put("frequency", 0)
                    put("observed_at", observedAt)
                    put("position_source", "manual-reference")
                    put("source_id", sourceId)
                }
            )
        }

        val estimate = database.estimatePosition(
            signals = identifiers.mapIndexed { index, identifier ->
                RadioSignal("cell", identifier, -80 - index, 0)
            },
            useWifi = false,
            useCells = true,
            useBle = false
        )

        assertNotNull(estimate)
        assertEquals(2, estimate!!.matchedCount)
        assertEquals(setOf("cell"), estimate.matchedRadioTypes)
        assertEquals(52.1000, estimate.latitude, 0.000001)
        assertEquals(4.1000, estimate.longitude, 0.000001)
    }

    @Test
    fun exactIdentityQueryUsesExistingCompositeIndex() {
        val details = sqlite.rawQuery(
            "EXPLAIN QUERY PLAN WITH requested(radio_type, identifier) AS (VALUES (?, ?)) " +
                "SELECT p.* FROM requested JOIN radio_points p " +
                "ON p.radio_type = requested.radio_type AND p.identifier = requested.identifier",
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

    @Test
    fun fingerprintMacExpressionUsesCompactRadioIndex() {
        assertTrue(indexExists("radio_obs_radio_compact_time"))
        val plan = sqlite.rawQuery(
            """
            EXPLAIN QUERY PLAN
            WITH requested_mac(radio_type, compact_identifier) AS (VALUES (?, ?)),
                 requested_exact(radio_type, identifier) AS (VALUES ('', ''))
            SELECT o.observed_at
            FROM requested_mac r
            JOIN radio_observations o ON o.radio_type = r.radio_type
              AND replace(replace(replace(lower(o.identifier), ':', ''), '-', ''), '.', '') = r.compact_identifier
            WHERE o.observed_at >= ? AND o.observed_at <= ?
            """.trimIndent(),
            arrayOf("wifi", "aabbccddee01", "0", Long.MAX_VALUE.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3).orEmpty())
            }.joinToString(" ")
        }

        assertTrue(
            "Expected compact MAC expression index, got: $plan",
            plan.contains("radio_obs_radio_compact_time")
        )
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

    private fun insertFingerprintObservation(
        type: String,
        identifier: String,
        sourceId: Long,
        observedAt: Long,
        latitude: Double,
        longitude: Double
    ) {
        sqlite.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", type)
                put("identifier", identifier)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", 12.0)
                put("rssi", -60)
                put("frequency", if (type == "wifi") 2412 else 0)
                put("observed_at", observedAt)
                put("position_source", "manual-reference")
                put("source_id", sourceId)
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

    private fun indexExists(index: String): Boolean =
        sqlite.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(index)
        ).use { it.moveToFirst() }

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
    }
}
