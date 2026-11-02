package org.cleargps

import android.content.Context
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellTechnology
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CellSiteDatabaseTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val database = LocalRadioDatabase(context, null)

    @After
    fun tearDown() {
        database.close()
    }

    private fun identity(
        technology: CellTechnology = CellTechnology.LTE,
        mnc: String = "01",
        cellId: Long
    ) = CellIdentity(
        technology = technology,
        mcc = "250",
        mnc = mnc,
        areaCode = 1024L,
        cellId = cellId,
        mncLengthKnown = true
    )

    private fun group(
        members: List<CellIdentity>,
        latitude: Double = 51.5,
        longitude: Double = -0.12,
        uncertainty: Double = 24.0,
        confidence: Double = 0.82,
        locked: Boolean = false,
        algorithmVersion: String = "cell-site-grouping-1"
    ): CellSiteGroup {
        val sortedMembers = members.sortedBy { it.stableKey }
        return CellSiteGroup(
            stableKey = "site:" + sortedMembers.joinToString("~") { it.stableKey },
            members = sortedMembers,
            latitude = latitude,
            longitude = longitude,
            uncertaintyRadiusMeters = uncertainty,
            confidenceScore = confidence,
            lockedByUser = locked,
            algorithmVersion = algorithmVersion
        )
    }

    @Test
    fun savesMembersAndUpsertsSameStableGroupWithoutChangingDatabaseId() {
        val lte = identity(cellId = 987654L)
        val nr = identity(CellTechnology.NR, "01", 998877L)
        val candidate = group(listOf(lte, nr))

        val first = database.saveCellSiteGroups(listOf(candidate), calculatedAtMillis = 1_700_000_000_000L)
        val firstRecord = database.listCellSites().single()

        assertEquals(1, first.inserted)
        assertEquals(0, first.updated)
        assertEquals(2, firstRecord.members.size)
        assertEquals(setOf(CellTechnology.LTE, CellTechnology.NR), firstRecord.members.map { it.technology }.toSet())
        assertEquals(candidate.stableKey, firstRecord.stableKey)
        assertEquals("cell-site-grouping-1", firstRecord.algorithmVersion)
        assertEquals(first.batchId, firstRecord.batchId)

        val second = database.saveCellSiteGroups(listOf(candidate), calculatedAtMillis = 1_700_000_001_000L)
        val secondRecord = database.listCellSites().single()

        assertEquals(0, second.inserted)
        assertEquals(1, second.updated)
        assertEquals(firstRecord.id, secondRecord.id)
        assertEquals(2, secondRecord.members.size)
        assertNotEquals(firstRecord.batchId, secondRecord.batchId)
        assertEquals(1, queryCount("SELECT COUNT(*) FROM cell_sites"))
        assertEquals(2, queryCount("SELECT COUNT(*) FROM cell_site_links"))
        val latestBatch = database.listCellSiteBatches().first()
        assertEquals(1, latestBatch.candidateSiteCount)
        assertEquals(0, latestBatch.insertedCount)
        assertEquals(1, latestBatch.updatedCount)
    }

    @Test
    fun aNewCalculationRemovesStaleAutomaticSites() {
        val first = group(listOf(identity(cellId = 1001L)))
        val second = group(listOf(identity(cellId = 1002L, mnc = "10")), latitude = 51.51)
        database.saveCellSiteGroups(listOf(first, second), calculatedAtMillis = 100L)
        assertEquals(2, database.listCellSites().size)

        val replacement = group(listOf(identity(CellTechnology.NR, "01", 2001L)), latitude = 51.52)
        val result = database.saveCellSiteGroups(listOf(replacement), calculatedAtMillis = 200L)

        assertEquals(1, result.inserted)
        assertEquals(2, result.removed)
        val records = database.listCellSites()
        assertEquals(1, records.size)
        assertEquals(replacement.stableKey, records.single().stableKey)
        assertEquals(1, queryCount("SELECT COUNT(*) FROM cell_site_links"))
    }

    @Test
    fun lockedCoordinateSurvivesRecalculationAndCanAcceptNewMembersWhenLockIsCarried() {
        val anchor = identity(cellId = 3001L)
        val nearby = identity(CellTechnology.NR, "10", 3002L)
        database.saveCellSiteGroups(
            listOf(group(listOf(anchor), latitude = 51.5, longitude = -0.12)),
            calculatedAtMillis = 100L
        )
        val original = database.listCellSites().single()

        assertTrue(database.lockCellSiteCoordinate(
            original.id, latitude = 52.0, longitude = 13.0, uncertaintyMeters = 6.0,
            updatedAtMillis = 150L
        ))
        val locked = database.listCellSites().single()
        assertTrue(locked.lockedByUser)
        assertEquals(52.0, locked.latitude, 0.0)
        assertEquals(13.0, locked.longitude, 0.0)

        val automaticProposal = group(
            listOf(anchor, nearby), latitude = 51.5001, longitude = -0.1201, locked = false
        )
        val protected = database.saveCellSiteGroups(listOf(automaticProposal), calculatedAtMillis = 200L)
        val afterAutomatic = database.listCellSites().single()
        assertEquals(1, protected.preservedLockedGroups)
        assertEquals(original.id, afterAutomatic.id)
        assertEquals(52.0, afterAutomatic.latitude, 0.0)
        assertEquals(13.0, afterAutomatic.longitude, 0.0)
        assertEquals(listOf(anchor.stableKey), afterAutomatic.members.map { it.stableKey })

        val lockCarryingProposal = automaticProposal.copy(
            latitude = 52.0, longitude = 13.0, lockedByUser = true
        )
        database.saveCellSiteGroups(listOf(lockCarryingProposal), calculatedAtMillis = 300L)
        val expanded = database.listCellSites().single()
        assertEquals(original.id, expanded.id)
        assertTrue(expanded.lockedByUser)
        assertEquals(52.0, expanded.latitude, 0.0)
        assertEquals(13.0, expanded.longitude, 0.0)
        assertEquals(setOf(anchor.stableKey, nearby.stableKey), expanded.members.map { it.stableKey }.toSet())
        assertTrue(expanded.uncertaintyMeters >= lockCarryingProposal.uncertaintyRadiusMeters)
    }

    @Test
    fun conflictingLockedSitesAreNeverMergedAndRemainStored() {
        val first = identity(cellId = 4001L)
        val second = identity(cellId = 4002L)
        database.saveCellSiteGroups(
            listOf(
                group(listOf(first), latitude = 40.0, longitude = -70.0, locked = true),
                group(listOf(second), latitude = 40.001, longitude = -70.0, locked = true)
            ),
            calculatedAtMillis = 100L
        )
        val before = database.listCellSites()
        assertEquals(2, before.size)

        val proposal = group(listOf(first, second), latitude = 40.0005, longitude = -70.0, locked = true)
        val result = database.saveCellSiteGroups(listOf(proposal), calculatedAtMillis = 200L)
        val after = database.listCellSites()

        assertEquals(1, result.preservedLockedGroups)
        assertEquals(2, after.size)
        assertEquals(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        assertEquals(setOf(40.0, 40.001), after.map { it.latitude }.toSet())
        assertEquals(setOf(first.stableKey, second.stableKey), after.flatMap { it.members }.map { it.stableKey }.toSet())
    }

    @Test
    fun duplicateIdentityAcrossGroupsIsRejectedBeforeChangingStoredSnapshot() {
        val first = identity(cellId = 5001L)
        val second = identity(CellTechnology.NR, "01", 5002L)
        val valid = group(listOf(first, second))
        database.saveCellSiteGroups(listOf(valid), calculatedAtMillis = 100L)
        val before = database.listCellSites().single()

        val invalid = listOf(
            group(listOf(first)),
            group(listOf(first, second), latitude = 51.6)
        )
        var threw = false
        try {
            database.saveCellSiteGroups(invalid, calculatedAtMillis = 200L)
        } catch (_: IllegalArgumentException) {
            threw = true
        }

        assertTrue(threw)
        val after = database.listCellSites().single()
        assertEquals(before.id, after.id)
        assertEquals(before.stableKey, after.stableKey)
        assertEquals(before.members.map { it.stableKey }.toSet(), after.members.map { it.stableKey }.toSet())
        assertFalse(after.lockedByUser)
    }

    @Test
    fun ambiguousMncWidthCannotBePersistedAsAPhysicalSite() {
        val ambiguousIdentity = identity(cellId = 7001L).copy(mncLengthKnown = false)
        val invalidGroup = group(listOf(ambiguousIdentity))

        var rejected = false
        try {
            database.saveCellSiteGroups(listOf(invalidGroup), calculatedAtMillis = 300L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(database.listCellSites().isEmpty())
        assertTrue(database.listCellSiteBatches().isEmpty())
    }

    @Test
    fun emptyCalculationDoesNotDeletePreviouslySavedAutomaticSites() {
        val previous = group(listOf(identity(cellId = 6001L)))
        database.saveCellSiteGroups(listOf(previous), calculatedAtMillis = 100L)
        val before = database.listCellSites().single()

        val result = database.saveCellSiteGroups(emptyList(), calculatedAtMillis = 200L)

        val after = database.listCellSites().single()
        assertEquals(0, result.removed)
        assertEquals(before.id, after.id)
        assertEquals(before.stableKey, after.stableKey)
        assertEquals(0, database.listCellSiteBatches().first().candidateSiteCount)
        assertEquals("no-candidate-groups", database.listCellSiteBatches().first().algorithmVersion)
    }

    private fun queryCount(sql: String): Int =
        database.readableDatabase.rawQuery(sql, null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
