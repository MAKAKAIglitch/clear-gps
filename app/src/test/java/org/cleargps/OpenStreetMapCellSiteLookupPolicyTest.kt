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
    fun onlyCertainUnlockedSitesAreQueued() {
        assertTrue(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group()))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(uncertainty = 251.0)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(confidence = 0.449)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(locked = true)))
        assertFalse(OpenStreetMapCellSiteLookupPolicy.shouldEnqueue(group(knownMncWidth = false)))
    }

    @Test
    fun radiusGrowsWithUncertaintyButStaysInsideMatcherBounds() {
        assertEquals(100, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(10.0))
        assertEquals(100, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(50.0))
        assertEquals(150, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(100.0))
        assertEquals(150, OpenStreetMapCellSiteLookupPolicy.searchRadiusMeters(250.0))
    }
}
