package org.cleargps

import android.content.Context
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellTechnology
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenStreetMapCellSiteQueueDatabaseTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val database = LocalRadioDatabase(context, null)

    @After
    fun tearDown() {
        database.close()
    }

    private val now = 1_800_000_000_000L

    private fun objectRecord(
        id: Long = 101L,
        latitude: Double = 51.5,
        longitude: Double = -0.12
    ) = OpenStreetMapCellSiteObject(
        elementType = OpenStreetMapElementType.NODE,
        osmId = id,
        latitude = latitude,
        longitude = longitude,
        coordinateUncertaintyMeters = 30.0,
        manMade = "mast",
        towerType = "communication",
        mobilePhoneCapable = true,
        telecom = null,
        operator = "Example Operator",
        name = "Cell tower",
        heightMeters = 32.0
    )

    private fun response(
        status: OpenStreetMapCellSiteLookupStatus,
        objects: List<OpenStreetMapCellSiteObject> = emptyList()
    ) = OpenStreetMapCellSiteResponse(status, objects, "test response")

    @Test
    fun repeatedNearbyRequestsShareOneTaskAndPositiveResultIsCached() {
        val first = database.enqueueOpenStreetMapCellSiteLookup(
            siteStableKey = "site:lte:250:01:100:1234",
            latitude = 51.50001,
            longitude = -0.12001,
            radiusMeters = 750,
            nowMillis = now
        )
        val duplicate = database.enqueueOpenStreetMapCellSiteLookup(
            siteStableKey = "site:lte:250:01:100:1234",
            latitude = 51.50002,
            longitude = -0.12002,
            radiusMeters = 750,
            nowMillis = now + 1L
        )

        assertTrue(first.created)
        assertFalse(duplicate.created)
        assertEquals(first.taskId, duplicate.taskId)
        assertFalse(duplicate.cachedResultAvailable)
        assertEquals(1, database.listOpenStreetMapCellSiteQueue().size)

        val claimed = database.claimNextOpenStreetMapCellSiteLookup(now + 2L)!!
        assertEquals(first.taskId, claimed.id)
        assertEquals("in_progress", claimed.status)
        assertEquals(1, claimed.attemptCount)
        assertNull(database.claimNextOpenStreetMapCellSiteLookup(now + 3L))

        assertTrue(database.finishOpenStreetMapCellSiteLookup(
            claimed.id,
            response(OpenStreetMapCellSiteLookupStatus.FOUND, listOf(objectRecord())),
            httpStatus = 200,
            nowMillis = now + 4L
        ))
        val completed = database.listOpenStreetMapCellSiteQueue().single()
        assertEquals("completed", completed.status)
        assertEquals(200, completed.lastHttpStatus)
        assertEquals(1, database.listCachedOpenStreetMapCellSiteObjects(now + 5L).size)
        val osm = database.listCachedOpenStreetMapCellSiteObjects(now + 5L).single()
        assertEquals("osm:node:101", osm.stableKey)
        assertEquals(32.0, osm.heightMeters!!, 0.0)

        val cached = database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:1234", 51.50001, -0.12001, 750, now + 6L
        )
        assertFalse(cached.created)
        assertTrue(cached.cachedResultAvailable)
        assertNull(database.claimNextOpenStreetMapCellSiteLookup(now + 7L))

        val refresh = database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:1234", 51.50001, -0.12001, 750,
            now + 30L * 24 * 60 * 60 * 1_000 + 5L
        )
        assertFalse(refresh.created)
        assertFalse(refresh.cachedResultAvailable)
        assertEquals("pending", database.listOpenStreetMapCellSiteQueue().single().status)
    }

    @Test
    fun notFoundResponseUsesNegativeCacheAndExpiresAfterSevenDays() {
        val queued = database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:9999", 40.0, -70.0, 500, now
        )
        val claimed = database.claimNextOpenStreetMapCellSiteLookup(now + 1L)!!
        assertEquals(queued.taskId, claimed.id)

        database.finishOpenStreetMapCellSiteLookup(
            claimed.id,
            response(OpenStreetMapCellSiteLookupStatus.NOT_FOUND),
            httpStatus = 200,
            nowMillis = now + 2L
        )
        val cached = database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:9999", 40.0, -70.0, 500, now + 3L
        )
        assertTrue(cached.cachedResultAvailable)
        assertEquals("not_found", database.listOpenStreetMapCellSiteQueue().single().status)

        val refresh = database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:9999", 40.0, -70.0, 500,
            now + 7L * 24 * 60 * 60 * 1_000 + 3L
        )
        assertFalse(refresh.cachedResultAvailable)
        assertEquals("pending", database.listOpenStreetMapCellSiteQueue().single().status)
    }

    @Test
    fun rateLimitUsesExponentialBackoffAndRecordsEachAttempt() {
        val queued = database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:1111", 52.0, 13.0, 1_000, now
        )
        val first = database.claimNextOpenStreetMapCellSiteLookup(now + 1L)!!
        assertEquals(queued.taskId, first.id)
        database.finishOpenStreetMapCellSiteLookup(
            first.id,
            response(OpenStreetMapCellSiteLookupStatus.RATE_LIMITED),
            httpStatus = 429,
            nowMillis = now + 2L
        )

        val deferred = database.listOpenStreetMapCellSiteQueue().single()
        assertEquals("pending", deferred.status)
        assertEquals(429, deferred.lastHttpStatus)
        assertTrue(deferred.nextAttemptAtMillis >= now + 60_000L)
        assertNull(database.claimNextOpenStreetMapCellSiteLookup(now + 30_000L))

        val second = database.claimNextOpenStreetMapCellSiteLookup(deferred.nextAttemptAtMillis)!!
        assertEquals(2, second.attemptCount)
        database.finishOpenStreetMapCellSiteLookup(
            second.id,
            response(OpenStreetMapCellSiteLookupStatus.TEMPORARY_ERROR),
            httpStatus = 503,
            nowMillis = deferred.nextAttemptAtMillis + 1L
        )
        val task = database.listOpenStreetMapCellSiteQueue().single()
        assertEquals(503, task.lastHttpStatus)
        assertTrue(task.nextAttemptAtMillis >= second.lastAttemptAtMillis!! + 120_000L)
        assertEquals(2, queryCount("SELECT COUNT(*) FROM cell_osm_attempts"))
    }

    @Test
    fun dailyRequestCapAndSingleActiveTaskAreEnforced() {
        repeat(3) { index ->
            database.enqueueOpenStreetMapCellSiteLookup(
                "site:lte:250:01:100:${2000 + index}",
                51.5 + index * 0.01,
                -0.12,
                500,
                now
            )
        }

        val first = database.claimNextOpenStreetMapCellSiteLookup(now + 1L)!!
        assertNull(database.claimNextOpenStreetMapCellSiteLookup(now + 2L))
        database.finishOpenStreetMapCellSiteLookup(
            first.id,
            response(OpenStreetMapCellSiteLookupStatus.NOT_FOUND),
            httpStatus = 200,
            nowMillis = now + 3L
        )
        val second = database.claimNextOpenStreetMapCellSiteLookup(now + 4L)!!
        database.finishOpenStreetMapCellSiteLookup(
            second.id,
            response(OpenStreetMapCellSiteLookupStatus.NOT_FOUND),
            httpStatus = 200,
            nowMillis = now + 5L
        )

        assertNull(database.claimNextOpenStreetMapCellSiteLookup(now + 6L))
        assertEquals(2, queryCount("SELECT COUNT(*) FROM cell_osm_attempts WHERE attempted_at >= ${now}"))
    }

    @Test
    fun staleInProgressTaskIsRecoveredAndCanBeRetried() {
        database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:3333", 51.5, -0.12, 500, now
        )
        val first = database.claimNextOpenStreetMapCellSiteLookup(now + 1L)!!
        val recovered = database.claimNextOpenStreetMapCellSiteLookup(
            now + 15L * 60 * 1_000 + 2L
        )!!

        assertEquals(first.id, recovered.id)
        assertEquals(2, recovered.attemptCount)
        assertTrue(recovered.lastAttemptAtMillis!! > first.lastAttemptAtMillis!!)
        assertTrue(recovered.status == "in_progress")
    }

    @Test
    fun invalidResponseIsMarkedFailedAndCacheCanBeCleared() {
        database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:4444", 51.5, -0.12, 500, now
        )
        val claimed = database.claimNextOpenStreetMapCellSiteLookup(now + 1L)!!
        database.finishOpenStreetMapCellSiteLookup(
            claimed.id,
            response(OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE),
            httpStatus = 200,
            nowMillis = now + 2L
        )
        assertEquals("failed", database.listOpenStreetMapCellSiteQueue().single().status)
        assertTrue(database.enqueueOpenStreetMapCellSiteLookup(
            "site:lte:250:01:100:4444", 51.5, -0.12, 500, now + 3L
        ).taskId == claimed.id)
        assertEquals("failed", database.listOpenStreetMapCellSiteQueue().single().status)

        val removed = database.clearOpenStreetMapCellSiteData()
        assertEquals(0, removed)
        assertTrue(database.listOpenStreetMapCellSiteQueue().isEmpty())
        assertTrue(database.listCachedOpenStreetMapCellSiteObjects(now + 4L).isEmpty())
    }

    @Test
    fun savingAutomaticSitesQueuesPreciseAndCoarseDiscoveryButSkipsLockedSites() {
        fun site(
            cellId: Long,
            uncertainty: Double,
            confidence: Double,
            locked: Boolean = false
        ): CellSiteGroup {
            val identity = CellIdentity(
                technology = CellTechnology.LTE,
                mcc = "250",
                mnc = "01",
                areaCode = 100L,
                cellId = cellId
            )
            return CellSiteGroup(
                stableKey = "site:${identity.stableKey}",
                members = listOf(identity),
                latitude = 51.5 + cellId / 1_000_000.0,
                longitude = -0.12,
                uncertaintyRadiusMeters = uncertainty,
                confidenceScore = confidence,
                lockedByUser = locked
            )
        }

        database.saveCellSiteGroups(
            groups = listOf(
                site(cellId = 7001L, uncertainty = 80.0, confidence = 0.7),
                site(cellId = 7002L, uncertainty = 251.0, confidence = 0.9),
                site(cellId = 7003L, uncertainty = 80.0, confidence = 0.7, locked = true)
            ),
            calculatedAtMillis = now
        )

        val queued = database.listOpenStreetMapCellSiteQueue()
        assertEquals(2, queued.size)

        val precise = queued.single { it.siteStableKey.contains("7001") }
        assertEquals(250, precise.radiusMeters)

        val coarseDiscovery = queued.single { it.siteStableKey.contains("7002") }
        assertEquals(450, coarseDiscovery.radiusMeters)

        assertTrue(queued.none { it.siteStableKey.contains("7003") })
        assertEquals(1, database.listCellSites().count { it.lockedByUser })
    }

    private fun queryCount(sql: String): Int =
        database.readableDatabase.rawQuery(sql, null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
