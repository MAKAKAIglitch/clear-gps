package org.cleargps.cell

import org.junit.Assert.*
import org.junit.Test

class CellSiteCoordinateEstimatorTest {
    private fun e(id: String, source: CellEvidenceSource, lat: Double, lon: Double, group: String) =
        CellSiteCoordinateEvidence(id, source, lat, lon, 80.0, dependencyGroup = group)

    @Test fun correlatedSourcesCountOnce() {
        val result = CellSiteCoordinateEstimator.estimate(listOf(
            e("a", CellEvidenceSource.OPENCELLID, 51.5, -0.12, "same-feed"),
            e("b", CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE, 51.50001, -0.12001, "same-feed")
        ))!!
        assertEquals(1, result.independentEvidenceCount)
        assertEquals(listOf("b"), result.ignoredCorrelatedEvidenceIds)
    }

    @Test fun userLockedCoordinateIsNeverMoved() {
        val locked = e("manual", CellEvidenceSource.USER_VERIFIED, 51.5, -0.12, "manual")
            .copy(lockedByUser = true)
        val external = e("osm", CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE, 52.0, -0.3, "osm")
        val result = CellSiteCoordinateEstimator.estimate(listOf(locked, external))!!
        assertTrue(result.lockedByUser)
        assertEquals(51.5, result.latitude, 0.0)
        assertEquals(-0.12, result.longitude, 0.0)
    }

    @Test fun emptyEvidenceDoesNotInventCoordinate() {
        assertNull(CellSiteCoordinateEstimator.estimate(emptyList()))
    }
}
