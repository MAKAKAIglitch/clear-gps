package org.cleargps

import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSignalMetadata
import org.cleargps.cell.CellTechnology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCellSitePositionEstimatorTest {
    @Test
    fun exactLocalTowerMatchProducesCoarseTimestampedEstimate() {
        val identity = identity()
        val signal = signal(identity)
        val site = site(identity)

        val estimate = LocalCellSitePositionEstimator.estimate(listOf(signal to site), nowMillis = 50_000L)

        assertNotNull(estimate)
        estimate!!
        assertEquals(60.0, estimate.latitude, 1e-9)
        assertEquals(24.0, estimate.longitude, 1e-9)
        assertTrue(estimate.accuracyMeters >= 5_000.0)
        assertEquals(1, estimate.matchedCount)
        assertEquals(setOf("cell|lte:250:01:100:12345"), estimate.matchedRadioIdentifiers)
        assertEquals(10_000_000_000L, estimate.measurementElapsedRealtimeNanos)
        assertEquals(setOf("cell-sites-local"), estimate.contributingSources)
    }

    @Test
    fun repeatedScansOfTheSameTowerDoNotRefreshTheAbsoluteAnchorTimestamp() {
        LocalCellSitePositionEstimator.reset()
        val identity = identity()
        val site = site(identity)
        val first = LocalCellSitePositionEstimator.estimate(listOf(signal(identity) to site))
        val laterSignal = signal(identity).copy(observedAtElapsedRealtimeNanos = 20_000_000_000L)
        val repeated = LocalCellSitePositionEstimator.estimate(listOf(laterSignal to site))

        assertNotNull(first)
        assertNotNull(repeated)
        assertEquals(10_000_000_000L, first!!.measurementElapsedRealtimeNanos)
        assertEquals(10_000_000_000L, repeated!!.measurementElapsedRealtimeNanos)
        LocalCellSitePositionEstimator.reset()
    }

    @Test
    fun mismatchedIdentityAndUnknownMncWidthAreNotUsed() {
        val liveIdentity = identity()
        val otherIdentity = liveIdentity.copy(cellId = 54321L)
        val unknownWidthIdentity = liveIdentity.copy(mncLengthKnown = false)

        assertNull(
            LocalCellSitePositionEstimator.estimate(
                listOf(signal(liveIdentity) to site(otherIdentity))
            )
        )
        assertNull(
            LocalCellSitePositionEstimator.estimate(
                listOf(signal(unknownWidthIdentity) to site(unknownWidthIdentity))
            )
        )
    }

    @Test
    fun invalidOrUntrustedSiteCoordinatesAreIgnored() {
        val identity = identity()
        val invalid = site(identity).copy(latitude = Double.NaN)
        val zeroConfidence = site(identity).copy(confidenceScore = 0.0)

        assertNull(LocalCellSitePositionEstimator.estimate(listOf(signal(identity) to invalid)))
        assertNull(LocalCellSitePositionEstimator.estimate(listOf(signal(identity) to zeroConfidence)))
    }

    private fun identity() = CellIdentity(
        technology = CellTechnology.LTE,
        mcc = "250",
        mnc = "01",
        areaCode = 100L,
        cellId = 12345L,
        mncLengthKnown = true
    )

    private fun signal(identity: CellIdentity) = RadioSignal(
        type = "cell",
        identifier = "${identity.technology.token}:${identity.mcc}:${identity.mnc}:${identity.areaCode}:${identity.cellId}",
        rssi = -70,
        observedAtElapsedRealtimeNanos = 10_000_000_000L,
        cellMetadata = CellSignalMetadata(
            technology = identity.technology,
            mcc = identity.mcc,
            mnc = identity.mnc,
            mncLengthKnown = identity.mncLengthKnown,
            areaCode = identity.areaCode,
            cellId = identity.cellId
        )
    )

    private fun site(identity: CellIdentity) = CellSiteRecord(
        id = 1L,
        stableKey = "site-1",
        latitude = 60.0,
        longitude = 24.0,
        uncertaintyMeters = 75.0,
        confidenceScore = 0.9,
        lockedByUser = false,
        algorithmVersion = "test",
        calculatedAtMillis = 1L,
        batchId = "test",
        members = listOf(identity)
    )
}
