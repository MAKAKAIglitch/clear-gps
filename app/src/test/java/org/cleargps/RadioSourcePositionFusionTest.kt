package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSourcePositionFusionTest {
    private fun estimate(latitude: Double, longitude: Double, accuracy: Double, source: String) =
        RadioEstimate(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            updatedAt = 1_000L,
            fingerprint = source,
            matchedCount = 3,
            matchedRadioTypes = setOf("cell", "wifi"),
            matchedRadioIdentifiers = setOf("cell|$source"),
            contributingSources = setOf(source)
        )

    @Test
    fun combinesLocalBeaconDbAndOpenCellIdWhenTheyAgree() {
        val combined = RadioPositionFusion.combine(
            listOf(
                RadioPositionFusion.SourceEstimate(
                    "local", estimate(51.5000, -0.1000, 500.0, "local"), 1.0
                ),
                RadioPositionFusion.SourceEstimate(
                    "beacondb", estimate(51.5005, -0.1000, 700.0, "beacon"), 0.85
                ),
                RadioPositionFusion.SourceEstimate(
                    "opencellid", estimate(51.5003, -0.1001, 5_000.0, "cellid"), 0.30
                )
            )
        )

        assertNotNull(combined)
        assertTrue(combined!!.latitude in 51.499..51.502)
        assertTrue(combined.longitude in -0.102..-0.098)
        assertFalse(combined.sourceConflictDetected)
        assertEquals(setOf("local", "beacondb", "opencellid"), combined.contributingSources)
        assertEquals(9, combined.matchedCount)
    }

    @Test
    fun conflictingSourcesChooseBestWeightedFixAndInflateUncertainty() {
        val local = estimate(51.50, -0.10, 500.0, "local")
        val remote = estimate(52.00, -0.10, 800.0, "beacon")

        val combined = RadioPositionFusion.combine(
            listOf(
                RadioPositionFusion.SourceEstimate("local", local, 1.0),
                RadioPositionFusion.SourceEstimate("beacondb", remote, 0.85)
            )
        )

        assertNotNull(combined)
        assertTrue(combined!!.sourceConflictDetected)
        assertEquals(setOf("local"), combined.contributingSources)
        assertEquals(local.latitude, combined.latitude, 0.000001)
        assertTrue(combined.accuracyMeters > 20_000.0)
        assertEquals(local.matchedCount, combined.matchedCount)
    }

    @Test
    fun disabledSourcesAreNotIncludedInFusion() {
        val combined = RadioPositionFusion.combine(
            listOf(
                RadioPositionFusion.SourceEstimate(
                    "local", estimate(51.5, -0.1, 500.0, "local"), 1.0
                )
            )
        )
        assertNotNull(combined)
        assertEquals(setOf("local"), combined!!.contributingSources)
    }

    @Test
    fun invalidCoordinatesAndWeightsAreIgnored() {
        val combined = RadioPositionFusion.combine(
            listOf(
                RadioPositionFusion.SourceEstimate("bad", estimate(91.0, 0.0, 100.0, "bad"), 1.0),
                RadioPositionFusion.SourceEstimate("zero", estimate(51.5, 0.0, 100.0, "zero"), 0.0)
            )
        )
        assertEquals(null, combined)
    }
}
