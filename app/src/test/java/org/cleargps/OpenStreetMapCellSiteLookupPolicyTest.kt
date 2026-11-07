package org.cleargps

import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellTechnology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenStreetMapCellSiteLookupPolicyTest {
    private fun group(
        uncertainty: Double = 80.0,
        confidence: Double = 0.7,
        locked: Boolean = false,
        knownMncWidth: Boolean = true
    ): CellSiteGroup {
        val identity = CellIdentity(
            technology = CellTechnology.LTE,
            mcc = "250",
            mnc = if (knownMncWidth) "01" else "1",
            mncLengthKnown = knownMncWidth,
            areaCode = 100L,
            cellId = 12345L
        )
        return CellSiteGroup(
            stableKey = "site:${identity.stableKey}",
            members = listOf(identity),
            latitude = 51.5,
            longitude = -0.12,
            uncertaintyRadiusMeters = uncertainty,
            confidenceScore = confidence,
            lockedByUser = locked
        )
    }

    @Test
    fun queueAcceptsBoundedMediumConfidenceSitesButProtectsLockedAndAmbiguousIdentities() {
        assertTrue(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group()))
        assertTrue(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(uncertainty = 251.0)))
        assertTrue(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(confidence = 0.449)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(uncertainty = 501.0)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(confidence = 0.299)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(locked = true)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(knownMncWidth = false)))
    }

    @Test
    fun coarseCandidatesDiscoverNearbyOsmObjectsWithoutBecomingMatchableSites() {
        assertTrue(OpenStreetMapCellSiteLookupPolicy.shouldDiscover(
            group(uncertainty = 5_000.0, confidence = 0.18)
        ))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldDiscover(
            group(uncertainty = 5_001.0, confidence = 0.5)
        ))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldDiscover(
            group(uncertainty = 100.0, confidence = 0.179)
        ))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldDiscover(
            group(uncertainty = 100.0, confidence = 0.5, locked = true)
        ))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldDiscover(
            group(uncertainty = 100.0, confidence = 0.5, knownMncWidth = false)
        ))
        assertEquals(1_000, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(5_000.0))
    }

    @Test
    fun radiusGrowsWithUncertaintyAndRemainsBounded() {
        assertEquals(200, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(10.0))
        assertEquals(200, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(50.0))
        assertEquals(250, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(100.0))
        assertEquals(400, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(250.0))
        assertEquals(650, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(500.0))
    }
}
