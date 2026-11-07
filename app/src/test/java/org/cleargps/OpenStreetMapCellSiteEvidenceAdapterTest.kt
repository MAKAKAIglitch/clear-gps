package org.cleargps

import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellTechnology
import org.cleargps.cell.CellEvidenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenStreetMapCellSiteEvidenceAdapterTest {
    private fun identity(cellId: Long, technology: CellTechnology = CellTechnology.LTE) =
        CellIdentity(technology, "250", "01", 100L, cellId, true)

    private fun group(
        identity: CellIdentity,
        latitude: Double,
        longitude: Double,
        uncertainty: Double = 25.0,
        confidence: Double = 0.8,
        locked: Boolean = false
    ): CellSiteGroup = CellSiteGroup(
        stableKey = "site:" + identity.stableKey,
        members = listOf(identity),
        latitude = latitude,
        longitude = longitude,
        uncertaintyRadiusMeters = uncertainty,
        confidenceScore = confidence,
        lockedByUser = locked
    )

    private fun osm(
        id: Long,
        latitude: Double,
        longitude: Double,
        mobilePhoneCapable: Boolean? = true,
        uncertainty: Double = 30.0
    ) = OpenStreetMapCellSiteObject(
        elementType = OpenStreetMapElementType.NODE,
        osmId = id,
        latitude = latitude,
        longitude = longitude,
        coordinateUncertaintyMeters = uncertainty,
        manMade = "mast",
        towerType = "communication",
        mobilePhoneCapable = mobilePhoneCapable,
        telecom = null,
        operator = null,
        name = null,
        heightMeters = null
    )

    @Test
    fun uniqueNearbyCommunicationObjectBecomesWeakEvidenceForEverySiteMember() {
        val lte = identity(1001L)
        val nr = identity(2002L, CellTechnology.NR)
        val site = group(lte, 51.5, -0.12).copy(
            stableKey = "site:" + listOf(lte, nr).sortedBy { it.stableKey }
                .joinToString("~") { it.stableKey },
            members = listOf(lte, nr)
        )
        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(site),
            listOf(osm(501L, 51.5001, -0.1201))
        )

        assertEquals(2, result.size)
        assertEquals(setOf(lte.stableKey, nr.stableKey), result.map { it.identity.stableKey }.toSet())
        result.forEach { bundle ->
            assertEquals(1, bundle.evidence.size)
            assertEquals(CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE, bundle.evidence.single().source)
            assertEquals("osm:communications-catalogue", bundle.evidence.single().dependencyGroup)
            assertTrue(bundle.evidence.single().uncertaintyMeters >= 100.0)
        }
    }

    @Test
    fun falseMobileCapabilityAndUserLockedSitesAreIgnored() {
        val active = group(identity(1001L), 51.5, -0.12)
        val locked = group(identity(1002L), 51.5001, -0.1201, locked = true)
        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(active, locked),
            listOf(
                osm(601L, 51.5, -0.12, mobilePhoneCapable = false),
                osm(602L, 51.5001, -0.1201, mobilePhoneCapable = true)
            )
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun oneOsmObjectIsNotAssignedToTwoEquallyCloseCellSites() {
        val first = group(identity(1001L), 51.5, -0.1200, uncertainty = 150.0)
        val second = group(identity(1002L), 51.5, -0.1220, uncertainty = 150.0)
        val midpoint = osm(701L, 51.5, -0.1210, uncertainty = 30.0)

        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(first, second),
            listOf(midpoint)
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun multipleOsmRepresentationsDoNotInflateOneCatalogueDependency() {
        val site = group(identity(1001L), 51.5, -0.12, uncertainty = 100.0)
        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(site),
            listOf(
                osm(801L, 51.50005, -0.12, uncertainty = 30.0),
                osm(802L, 51.50010, -0.12, uncertainty = 30.0)
            )
        )

        // Similar nearby representations are intentionally considered ambiguous.
        assertTrue(result.isEmpty())
    }

    @Test
    fun coarseOpenCellIdOnlyCandidateCannotBePulledToANearbyOsmObject() {
        val coarse = group(
            identity(1001L),
            latitude = 51.5,
            longitude = -0.12,
            uncertainty = 5_000.0,
            confidence = 0.3
        )

        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(coarse),
            listOf(osm(901L, 51.5001, -0.1201))
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun lowConfidenceSiteIsNotUsedToAttributeAnOsmStructure() {
        val weak = group(
            identity(1002L), 51.5, -0.12,
            uncertainty = 40.0,
            confidence = 0.2
        )

        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(weak),
            listOf(osm(902L, 51.5001, -0.1201))
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun boundedMediumConfidenceSiteCanMatchTowerBeyondFormer150MeterLimit() {
        val site = group(
            identity(1003L),
            latitude = 51.5,
            longitude = -0.12,
            uncertainty = 250.0,
            confidence = 0.35
        )
        val tower = osm(
            id = 903L,
            latitude = 51.5023,
            longitude = -0.12,
            uncertainty = 30.0
        )

        val result = OpenStreetMapCellSiteEvidenceAdapter.toEvidenceBundles(
            listOf(site),
            listOf(tower)
        )

        assertEquals(1, result.size)
        assertEquals("osm:node:903", result.single().evidence.single().evidenceId)
        assertTrue(result.single().evidence.single().uncertaintyMeters < 250.0)
    }

}
