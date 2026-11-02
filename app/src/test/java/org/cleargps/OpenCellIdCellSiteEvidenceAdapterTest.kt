package org.cleargps

import org.cleargps.cell.CellEvidenceSource
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellTechnology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCellIdCellSiteEvidenceAdapterTest {
    private val now = 2_000_000_000_000L

    private fun identity(mnc: String, technology: CellTechnology = CellTechnology.LTE) =
        CellIdentity(
            technology = technology,
            mcc = "234",
            mnc = mnc,
            areaCode = 100L,
            cellId = 55221L,
            mncLengthKnown = true
        )

    private fun record(
        radio: String = "LTE",
        checkedAt: Long = now,
        status: String = "found",
        latitude: Double? = 51.5,
        longitude: Double? = -0.12,
        range: Int? = 800
    ) = OpenCellIdCellRecord(
        key = OpenCellIdCellKey(radio, 234, 1, 100, 55221L),
        latitude = latitude,
        longitude = longitude,
        rangeMeters = range,
        samples = 20,
        changeable = false,
        checkedAtMillis = checkedAt,
        status = status
    )

    @Test
    fun knownUniqueMncMapsToASeparateCoarseEvidenceBundle() {
        val identity = identity("01")
        val result = OpenCellIdCellSiteEvidenceAdapter.toEvidenceBundles(
            identities = listOf(identity),
            records = listOf(record()),
            nowMillis = now
        )

        assertEquals(1, result.size)
        val bundle = result.single()
        assertEquals(identity.stableKey, bundle.identity.stableKey)
        val evidence = bundle.evidence.single()
        assertEquals(CellEvidenceSource.OPENCELLID, evidence.source)
        assertEquals(51.5, evidence.latitude, 0.0)
        assertEquals(-0.12, evidence.longitude, 0.0)
        assertTrue(evidence.uncertaintyMeters >= OpenCellIdLookupPolicy.MIN_CELL_COORDINATE_UNCERTAINTY_METERS)
        assertEquals("opencellid:LTE:234:1:100:55221", evidence.dependencyGroup)
    }

    @Test
    fun numericMncCollisionAcrossDifferentWidthsIsRejectedForBothIdentities() {
        val twoDigit = identity("01")
        val threeDigit = identity("001")
        val result = OpenCellIdCellSiteEvidenceAdapter.toEvidenceBundles(
            identities = listOf(twoDigit, threeDigit),
            records = listOf(record()),
            nowMillis = now
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun legacyUnknownMncWidthAlsoBlocksNumericAlias() {
        val known = identity("01")
        val legacy = CellIdentity(
            technology = CellTechnology.LTE,
            mcc = "234",
            mnc = "1",
            areaCode = 100L,
            cellId = 55221L,
            mncLengthKnown = false
        )

        val result = OpenCellIdCellSiteEvidenceAdapter.toEvidenceBundles(
            identities = listOf(known, legacy),
            records = listOf(record()),
            nowMillis = now
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun staleNegativeAndInvalidCoordinatesAreNotEvidence() {
        val identity = identity("01")
        val stale = record(checkedAt = now - OpenCellIdLookupPolicy.POSITION_MAX_AGE_MILLIS - 1L)
        val negative = record(status = "not_found", latitude = null, longitude = null, range = null)
        val invalid = record(latitude = 91.0)

        val result = OpenCellIdCellSiteEvidenceAdapter.toEvidenceBundles(
            identities = listOf(identity),
            records = listOf(stale, negative, invalid),
            nowMillis = now
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun umtsCatalogueRadioMapsToWcdmaIdentity() {
        val identity = identity("01", CellTechnology.WCDMA)
        val result = OpenCellIdCellSiteEvidenceAdapter.toEvidenceBundles(
            identities = listOf(identity),
            records = listOf(record(radio = "UMTS")),
            nowMillis = now
        )

        assertEquals(1, result.size)
        assertEquals(CellTechnology.WCDMA, result.single().identity.technology)
    }
}
