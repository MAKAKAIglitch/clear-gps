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
    fun additionalTimingAdvanceCandidateCanCreateASiteWithoutCatalogueCoordinates() {
        val identity = identity(CellTechnology.LTE, 6001L)
        val timingEstimate = CellSiteCoordinateEstimate(
            latitude = 51.5000,
            longitude = -0.1200,
            uncertaintyRadiusMeters = 75.0,
            confidenceScore = 0.82,
            independentEvidenceCount = 12,
            acceptedEvidenceIds = (0 until 12).map { "lte-ta-$it" },
            rejectedEvidenceIds = emptyList(),
            ignoredCorrelatedEvidenceIds = emptyList(),
            conflictDetected = false,
            lockedByUser = false,
            algorithmVersion = "cell-ta-multilateration-1"
        )

        val result = CellSiteIntelligenceEngine.calculate(
            bundles = emptyList(),
            groupingConfig = CellSiteGroupingConfig(
                minimumConfidenceScore = 0.4,
                minimumIndependentEvidenceCount = 1,
                maximumCandidateUncertaintyMeters = 500.0
            ),
            additionalCandidates = listOf(CellSiteCandidate(identity, timingEstimate))
        )

        assertEquals(timingEstimate, result.estimatesByIdentity[identity.stableKey])
        assertEquals(1, result.groups.size)
        assertEquals(identity.stableKey, result.groups.single().members.single().stableKey)
    }

    @Test
    fun conflictingTimingAdvanceAndCatalogueEstimatesAreExcluded() {
        val identity = identity(CellTechnology.LTE, 6002L)
        val timingEstimate = CellSiteCoordinateEstimate(
            latitude = 51.5000,
            longitude = -0.1200,
            uncertaintyRadiusMeters = 50.0,
            confidenceScore = 0.9,
            independentEvidenceCount = 12,
            acceptedEvidenceIds = (0 until 12).map { "lte-ta-$it" },
            rejectedEvidenceIds = emptyList(),
            ignoredCorrelatedEvidenceIds = emptyList(),
            conflictDetected = false,
            lockedByUser = false,
            algorithmVersion = "cell-ta-multilateration-1"
        )
        val catalogueEvidence = CellSiteEvidenceBundle(
            identity,
            listOf(
                CellSiteCoordinateEvidence(
                    evidenceId = "catalogue-conflict",
                    source = CellEvidenceSource.OPENCELLID,
                    latitude = 51.5100,
                    longitude = -0.1200,
                    uncertaintyMeters = 60.0
                )
            )
        )

        val result = CellSiteIntelligenceEngine.calculate(
            bundles = listOf(catalogueEvidence),
            groupingConfig = CellSiteGroupingConfig(
                minimumConfidenceScore = 0.1,
                minimumIndependentEvidenceCount = 1,
                maximumCandidateUncertaintyMeters = 10_000.0
            ),
            additionalCandidates = listOf(CellSiteCandidate(identity, timingEstimate))
        )

        assertTrue(result.estimatesByIdentity.getValue(identity.stableKey).conflictDetected)
        assertTrue(result.groups.isEmpty())
        assertEquals(
            CellSiteExclusionReason.CONFLICTING_EVIDENCE,
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

    @Test
    fun matchedOsmStructureRefinesTimingAdvanceCoordinateInsteadOfLosingToIt() {
        val identity = identity(CellTechnology.LTE, 7001L)
        val timingEstimate = CellSiteCoordinateEstimate(
            latitude = 51.5000,
            longitude = -0.1200,
            uncertaintyRadiusMeters = 75.0,
            confidenceScore = 0.82,
            independentEvidenceCount = 12,
            acceptedEvidenceIds = (0 until 12).map { "lte-ta-$it" },
            rejectedEvidenceIds = emptyList(),
            ignoredCorrelatedEvidenceIds = emptyList(),
            conflictDetected = false,
            lockedByUser = false,
            algorithmVersion = "cell-ta-multilateration-1"
        )
        val osm = CellSiteCoordinateEvidence(
            evidenceId = "osm:node:7001",
            source = CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE,
            latitude = 51.5005,
            longitude = -0.1203,
            uncertaintyMeters = 35.0,
            dependencyGroup = "osm:communications-catalogue"
        )

        val result = CellSiteIntelligenceEngine.calculate(
            bundles = listOf(CellSiteEvidenceBundle(identity, listOf(osm))),
            groupingConfig = CellSiteGroupingConfig(
                minimumConfidenceScore = 0.4,
                minimumIndependentEvidenceCount = 1,
                maximumCandidateUncertaintyMeters = 500.0
            ),
            additionalCandidates = listOf(CellSiteCandidate(identity, timingEstimate))
        )

        val fused = result.estimatesByIdentity.getValue(identity.stableKey)
        val originalToOsm = CellSiteCoordinateEstimator.distanceMeters(
            timingEstimate.latitude, timingEstimate.longitude, osm.latitude, osm.longitude
        )
        val fusedToOsm = CellSiteCoordinateEstimator.distanceMeters(
            fused.latitude, fused.longitude, osm.latitude, osm.longitude
        )
        assertTrue("OSM evidence must measurably refine the local coordinate", fusedToOsm < originalToOsm)
        assertTrue("The local coordinate must also contribute to the fused result",
            CellSiteCoordinateEstimator.distanceMeters(
                timingEstimate.latitude, timingEstimate.longitude, fused.latitude, fused.longitude
            ) > 0.0
        )
        assertFalse(fused.conflictDetected)
        assertEquals(timingEstimate.confidenceScore, fused.confidenceScore, 0.0)
        assertTrue(fused.independentEvidenceCount >= timingEstimate.independentEvidenceCount)
        assertEquals(1, result.groups.size)
    }

    @Test
    fun osmEvidenceCannotMoveUserLockedTimingAdvanceCoordinate() {
        val identity = identity(CellTechnology.LTE, 7002L)
        val lockedEstimate = CellSiteCoordinateEstimate(
            latitude = 51.5000,
            longitude = -0.1200,
            uncertaintyRadiusMeters = 8.0,
            confidenceScore = 1.0,
            independentEvidenceCount = 1,
            acceptedEvidenceIds = listOf("user-locked"),
            rejectedEvidenceIds = emptyList(),
            ignoredCorrelatedEvidenceIds = emptyList(),
            conflictDetected = false,
            lockedByUser = true,
            algorithmVersion = "manual-coordinate"
        )
        val osm = CellSiteCoordinateEvidence(
            evidenceId = "osm:node:7002",
            source = CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE,
            latitude = 51.5010,
            longitude = -0.1200,
            uncertaintyMeters = 35.0,
            dependencyGroup = "osm:communications-catalogue"
        )

        val result = CellSiteIntelligenceEngine.calculate(
            bundles = listOf(CellSiteEvidenceBundle(identity, listOf(osm))),
            additionalCandidates = listOf(CellSiteCandidate(identity, lockedEstimate))
        )

        val actual = result.estimatesByIdentity.getValue(identity.stableKey)
        assertEquals(lockedEstimate.latitude, actual.latitude, 0.0)
        assertEquals(lockedEstimate.longitude, actual.longitude, 0.0)
        assertTrue(actual.lockedByUser)
    }

    @Test
    fun distantOsmStructureIsFlaggedAsConflictRatherThanTrustedAsTowerPosition() {
        val identity = identity(CellTechnology.LTE, 7003L)
        val timingEstimate = CellSiteCoordinateEstimate(
            latitude = 51.5000,
            longitude = -0.1200,
            uncertaintyRadiusMeters = 75.0,
            confidenceScore = 0.82,
            independentEvidenceCount = 12,
            acceptedEvidenceIds = (0 until 12).map { "lte-ta-$it" },
            rejectedEvidenceIds = emptyList(),
            ignoredCorrelatedEvidenceIds = emptyList(),
            conflictDetected = false,
            lockedByUser = false,
            algorithmVersion = "cell-ta-multilateration-1"
        )
        val distantOsm = CellSiteCoordinateEvidence(
            evidenceId = "osm:node:7003",
            source = CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE,
            latitude = 51.5100,
            longitude = -0.1200,
            uncertaintyMeters = 35.0,
            dependencyGroup = "osm:communications-catalogue"
        )

        val result = CellSiteIntelligenceEngine.calculate(
            bundles = listOf(CellSiteEvidenceBundle(identity, listOf(distantOsm))),
            groupingConfig = CellSiteGroupingConfig(
                minimumConfidenceScore = 0.1,
                minimumIndependentEvidenceCount = 1,
                maximumCandidateUncertaintyMeters = 10_000.0
            ),
            additionalCandidates = listOf(CellSiteCandidate(identity, timingEstimate))
        )

        assertTrue(result.estimatesByIdentity.getValue(identity.stableKey).conflictDetected)
        assertTrue(result.groups.isEmpty())
        assertEquals(
            CellSiteExclusionReason.CONFLICTING_EVIDENCE,
            result.grouping.excludedIdentityReasons[identity.stableKey]
        )
    }

}
