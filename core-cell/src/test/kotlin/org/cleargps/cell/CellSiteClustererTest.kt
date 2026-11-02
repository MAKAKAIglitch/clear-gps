package org.cleargps.cell

import org.junit.Assert.*
import org.junit.Test

class CellSiteClustererTest {
    private fun identity(
        technology: CellTechnology,
        cellId: Long,
        mcc: String = "250",
        mnc: String = "01",
        knownWidth: Boolean = true
    ) = CellIdentity(
        technology = technology,
        mcc = mcc,
        mnc = mnc,
        areaCode = 100L,
        cellId = cellId,
        mncLengthKnown = knownWidth
    )

    private fun candidate(
        technology: CellTechnology = CellTechnology.LTE,
        cellId: Long,
        latitude: Double,
        longitude: Double,
        confidence: Double = 0.8,
        uncertainty: Double = 12.0,
        evidenceCount: Int = 5,
        knownWidth: Boolean = true,
        conflict: Boolean = false,
        mcc: String = "250",
        mnc: String = "01"
    ) = CellSiteCandidate(
        identity(technology, cellId, mcc, mnc, knownWidth),
        CellSiteCoordinateEstimate(
            latitude = latitude,
            longitude = longitude,
            uncertaintyRadiusMeters = uncertainty,
            confidenceScore = confidence,
            independentEvidenceCount = evidenceCount,
            acceptedEvidenceIds = (1..evidenceCount).map { "e$cellId-$it" },
            rejectedEvidenceIds = emptyList(),
            ignoredCorrelatedEvidenceIds = emptyList(),
            conflictDetected = conflict,
            lockedByUser = false
        )
    )

    @Test fun coLocatedRatsAndOperatorsAreGroupedWithoutLosingIdentities() {
        val result = CellSiteClusterer.group(listOf(
            candidate(cellId = 1001L, latitude = 51.500000, longitude = -0.120000),
            candidate(CellTechnology.NR, 2002L, 51.500080, -0.120020, mnc = "10"),
            candidate(CellTechnology.WCDMA, 3003L, 51.499940, -0.119970)
        ))

        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(3, group.memberCount)
        assertEquals(setOf(CellTechnology.LTE, CellTechnology.NR, CellTechnology.WCDMA), group.technologies)
        assertEquals(3, group.members.map { it.stableKey }.distinct().size)
        assertTrue(group.uncertaintyRadiusMeters >= 12.0)
        assertEquals(0.8, group.confidenceScore, 0.0)
    }

    @Test fun neighbouringTowersRemainSeparate() {
        val result = CellSiteClusterer.group(listOf(
            candidate(cellId = 1001L, latitude = 51.500000, longitude = -0.120000),
            candidate(cellId = 1002L, latitude = 51.501500, longitude = -0.120000)
        ))

        assertEquals(2, result.groups.size)
        assertTrue(result.groups.all { it.memberCount == 1 })
    }

    @Test fun completeLinkPreventsTransitiveTowerChaining() {
        // A-B and B-C are close enough, but A-C are farther apart than the merge threshold.
        val a = candidate(cellId = 1001L, latitude = 51.500000, longitude = -0.120000, uncertainty = 30.0)
        val b = candidate(cellId = 1002L, latitude = 51.500270, longitude = -0.120000, uncertainty = 30.0)
        val c = candidate(cellId = 1003L, latitude = 51.500540, longitude = -0.120000, uncertainty = 30.0)

        val result = CellSiteClusterer.group(listOf(c, b, a))

        assertEquals(2, result.groups.size)
        assertTrue(result.groups.all { group ->
            val points = listOf(a, b, c).filter { candidate ->
                group.members.any { it.stableKey == candidate.identity.stableKey }
            }
            points.indices.all { i ->
                (i + 1 until points.size).all { j ->
                    CellSiteCoordinateEstimator.distanceMeters(
                        points[i].estimate.latitude, points[i].estimate.longitude,
                        points[j].estimate.latitude, points[j].estimate.longitude
                    ) <= 50.0
                }
            }
        })
    }

    @Test fun ambiguousAndWeakCandidatesAreExcluded() {
        val result = CellSiteClusterer.group(listOf(
            candidate(cellId = 1001L, latitude = 51.5, longitude = -0.12, knownWidth = false),
            candidate(cellId = 1002L, latitude = 51.5, longitude = -0.12, evidenceCount = 1),
            candidate(cellId = 1003L, latitude = 51.5, longitude = -0.12, confidence = 0.1),
            candidate(cellId = 1004L, latitude = 51.5, longitude = -0.12, conflict = true),
            candidate(cellId = 1005L, latitude = 51.5, longitude = -0.12, uncertainty = 700.0)
        ))

        assertTrue(result.groups.isEmpty())
        assertEquals(5, result.excludedIdentityReasons.size)
        assertEquals(CellSiteExclusionReason.UNKNOWN_MNC_WIDTH,
            result.excludedIdentityReasons[identity(CellTechnology.LTE, 1001L, knownWidth = false).stableKey])
        assertEquals(CellSiteExclusionReason.INSUFFICIENT_EVIDENCE,
            result.excludedIdentityReasons[identity(CellTechnology.LTE, 1002L).stableKey])
        assertEquals(CellSiteExclusionReason.LOW_CONFIDENCE,
            result.excludedIdentityReasons[identity(CellTechnology.LTE, 1003L).stableKey])
        assertEquals(CellSiteExclusionReason.CONFLICTING_EVIDENCE,
            result.excludedIdentityReasons[identity(CellTechnology.LTE, 1004L).stableKey])
        assertEquals(CellSiteExclusionReason.HIGH_UNCERTAINTY,
            result.excludedIdentityReasons[identity(CellTechnology.LTE, 1005L).stableKey])
    }

    @Test fun dateLineSitesAreAveragedAcrossTheShortArc() {
        val result = CellSiteClusterer.group(listOf(
            candidate(cellId = 1001L, latitude = 0.0, longitude = 179.99990, uncertainty = 20.0),
            candidate(cellId = 1002L, latitude = 0.0, longitude = -179.99990, uncertainty = 20.0)
        ))

        assertEquals(1, result.groups.size)
        assertTrue(kotlin.math.abs(result.groups.single().longitude) > 179.9)
    }

    @Test fun duplicateIdentityKeepsBestEstimateAndReportsDuplicate() {
        val weak = candidate(cellId = 1001L, latitude = 51.5, longitude = -0.12, confidence = 0.6)
        val strong = candidate(cellId = 1001L, latitude = 51.50001, longitude = -0.12001, confidence = 0.9)
        val other = candidate(cellId = 1002L, latitude = 51.50002, longitude = -0.12002, confidence = 0.95)

        val first = CellSiteClusterer.group(listOf(weak, strong, other))
        val second = CellSiteClusterer.group(listOf(other, strong, weak))

        assertEquals(1, first.groups.size)
        assertEquals(first.groups.map { it.stableKey }, second.groups.map { it.stableKey })
        assertEquals(listOf(weak.identity.stableKey), first.duplicateIdentityKeys)
        assertEquals(0.9, first.groups.single().confidenceScore, 0.0)
        assertEquals(weak.identity.stableKey, first.groups.single().members.first().stableKey)
    }
}
