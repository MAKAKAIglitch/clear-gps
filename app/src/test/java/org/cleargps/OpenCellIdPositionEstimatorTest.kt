package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCellIdPositionEstimatorTest {
    private val now = 2_000_000_000_000L

    private fun evidence(
        radio: String,
        id: Long,
        lat: Double,
        lon: Double,
        range: Int = 800,
        samples: Int = 20,
        checkedAt: Long = now,
        status: String = "found",
        changeable: Boolean = false
    ): Pair<RadioSignal, OpenCellIdCellRecord> {
        val key = OpenCellIdCellKey(radio, 234, 15, 100, id)
        val signal = RadioSignal("cell", "${radio.lowercase()}:234:15:100:$id", -80)
        val record = OpenCellIdCellRecord(
            key, lat.takeIf { status == "found" }, lon.takeIf { status == "found" },
            range.takeIf { status == "found" }, samples, changeable, checkedAt, status
        )
        return signal to record
    }

    @Test fun aSingleTowerIsAnEstimateWithConservativeUncertainty() {
        val estimate = OpenCellIdPositionEstimator.estimate(
            listOf(evidence("LTE", 123L, 51.5, -0.1)), now
        )
        assertNotNull(estimate)
        assertEquals(51.5, estimate!!.latitude, 0.000001)
        assertTrue(estimate.accuracyMeters >= 5_000.0)
        assertEquals(now, estimate.updatedAt)
        assertEquals(setOf("opencellid"), estimate.contributingSources)
        assertTrue(estimate.fallbackUsed)
    }

    @Test fun severalTowersProduceAWeightedCentre() {
        val estimate = OpenCellIdPositionEstimator.estimate(
            listOf(
                evidence("LTE", 123L, 51.5000, -0.1000),
                evidence("LTE", 124L, 51.5010, -0.1000, range = 1_000, samples = 30)
            ), now
        )
        assertNotNull(estimate)
        assertTrue(estimate!!.latitude in 51.5000..51.5011)
        assertTrue(estimate.accuracyMeters >= 5_000.0)
        assertEquals(2, estimate.matchedCount)
    }

    @Test fun deprecatedChangeableFlagDoesNotChangeCellConfidence() {
        val fixed = OpenCellIdPositionEstimator.estimate(
            listOf(evidence("LTE", 123L, 51.5, -0.1, changeable = false)), now
        )
        val deprecated = OpenCellIdPositionEstimator.estimate(
            listOf(evidence("LTE", 123L, 51.5, -0.1, changeable = true)), now
        )
        assertNotNull(fixed)
        assertNotNull(deprecated)
        assertEquals(fixed!!.accuracyMeters, deprecated!!.accuracyMeters, 0.000001)
        assertEquals(fixed.latitude, deprecated.latitude, 0.000001)
    }

    @Test fun absentStaleAndInvalidTowerRecordsAreIgnored() {
        val estimate = OpenCellIdPositionEstimator.estimate(
            listOf(
                evidence("LTE", 123L, 51.5, -0.1, status = "not_found"),
                evidence("LTE", 124L, 51.5, -0.1,
                    checkedAt = now - OpenCellIdLookupPolicy.POSITION_MAX_AGE_MILLIS - 1L),
                evidence("LTE", 125L, 91.0, -0.1)
            ), now
        )
        assertEquals(null, estimate)
    }
}
