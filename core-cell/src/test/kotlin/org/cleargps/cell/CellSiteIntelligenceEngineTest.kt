package org.cleargps.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellSiteIntelligenceEngineTest {
    private fun identity(
        technology: CellTechnology,
        cellId: Long,
        mnc: String = "01",
        knownWidth: Boolean = true
    ) = CellIdentity(
        technology = technology,
        mcc = "250",
        mnc = mnc,
        areaCode = 1024L,
        cellId = cellId,
        mncLengthKnown = knownWidth
    )

    private fun independentEvidence(
        prefix: String,
        latitude: Double,
        longitude: Double
    ) = listOf(
        CellSiteCoordinateEvidence(
            evidenceId = "$prefix-opencellid",
            source = CellEvidenceSource.OPENCELLID,
            latitude = latitude,
            longitude = longitude,
            uncertaintyMeters = 30.0,
            dependencyGroup = "$prefix-provider-a"
        ),
        CellSiteCoordinateEvidence(
            evidenceId = "$prefix-import",
            source = CellEvidenceSource.IMPORTED_CATALOGUE,
            latitude = latitude + 0.000005,
            longitude = longitude + 0.000005,
            uncertaintyMeters = 32.0,
            dependencyGroup = "$prefix-provider-b"
        )
    )

    @Test
    fun fusesBundlesPerIdentityThenGroupsColocatedDifferentRats() {
        val lte = identity(CellTechnology.LTE, 1001L)
        val nr = identity(CellTechnology.NR, 2001L, mnc = "10")
        val lteEvidence = independentEvidence("lte", 51.500000, -0.120000)
        val nrEvidence = independentEvidence("nr", 51.500030, -0.120010)

        val result = CellSiteIntelligenceEngine.calculate(listOf(
            CellSiteEvidenceBundle(lte, lteEvidence.take(1)),
            CellSiteEvidenceBundle(lte, lteEvidence.drop(1)),
            CellSiteEvidenceBundle(nr, nrEvidence)
        ))

        assertEquals(2, result.estimatesByIdentity.size)
        assertTrue(result.positionExclusions.isEmpty())
        assertEquals(1, result.groups.size)
        assertEquals(2, result.groups.single().memberCount)
        assertEquals(setOf(CellTechnology.LTE, CellTechnology.NR), result.groups.single().technologies)
        assertTrue(result.estimatesByIdentity.values.all { it.independentEvidenceCount == 2 })
    }

    @Test
    fun legacyMncWidthAndMissingEvidenceAreReportedWithoutInventingCoordinates() {
        val ambiguous = identity(CellTechnology.LTE, 3001L, mnc = "1", knownWidth = false)
        val knownButEmpty = identity(CellTechnology.NR, 3002L)
        val result = CellSiteIntelligenceEngine.calculate(listOf(
            CellSiteEvidenceBundle(ambiguous, independentEvidence("ambiguous", 51.5, -0.12)),
            CellSiteEvidenceBundle(knownButEmpty, emptyList())
        ))

        assertTrue(result.groups.isEmpty())
        assertTrue(result.estimatesByIdentity.isEmpty())
        assertEquals(
            CellSitePositionExclusionReason.UNKNOWN_MNC_WIDTH,
            result.positionExclusions[ambiguous.stableKey]
        )
        assertEquals(
            CellSitePositionExclusionReason.NO_VALID_COORDINATE_EVIDENCE,
            result.positionExclusions[knownButEmpty.stableKey]
        )
    }

    @Test
    fun oneWeakExternalSourceRemainsVisibleAsAnEstimateButCannotCreateASite() {
        val identity = identity(CellTechnology.LTE, 4001L)
        val onlyEvidence = CellSiteCoordinateEvidence(
            evidenceId = "single-opencellid",
            source = CellEvidenceSource.OPENCELLID,
            latitude = 51.5,
            longitude = -0.12,
            uncertaintyMeters = 25.0
        )

        val result = CellSiteIntelligenceEngine.calculate(
            listOf(CellSiteEvidenceBundle(identity, listOf(onlyEvidence)))
        )

        assertNotNull(result.estimatesByIdentity[identity.stableKey])
        assertTrue(result.groups.isEmpty())
        assertEquals(
            CellSiteExclusionReason.INSUFFICIENT_EVIDENCE,
            result.grouping.excludedIdentityReasons[identity.stableKey]
        )
    }

    @Test
    fun duplicateEvidenceIdsAreCountedOnlyOnce() {
        val identity = identity(CellTechnology.LTE, 5001L)
        val duplicate = CellSiteCoordinateEvidence(
            evidenceId = "same-id",
            source = CellEvidenceSource.OPENCELLID,
            latitude = 51.5,
            longitude = -0.12,
            uncertaintyMeters = 25.0
        )
        val result = CellSiteIntelligenceEngine.calculate(listOf(
            CellSiteEvidenceBundle(identity, listOf(duplicate)),
            CellSiteEvidenceBundle(identity, listOf(duplicate.copy(latitude = 51.5001)))
        ))

        val estimate = result.estimatesByIdentity[identity.stableKey]
        assertNotNull(estimate)
        assertEquals(1, estimate!!.independentEvidenceCount)
        assertFalse(result.groups.isNotEmpty())
        assertEquals(
            CellSiteExclusionReason.INSUFFICIENT_EVIDENCE,
            result.grouping.excludedIdentityReasons[identity.stableKey]
        )
    }
}
