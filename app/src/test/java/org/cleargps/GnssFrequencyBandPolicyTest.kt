package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssFrequencyBandPolicyTest {
    @Test
    fun classifiesKnownGnssFrequencyRanges() {
        assertEquals(GnssFrequencyBand.L1_E1_B1, GnssFrequencyBandPolicy.classify(1_575_420_000.0))
        assertEquals(GnssFrequencyBand.L5_E5A, GnssFrequencyBandPolicy.classify(1_176_450_000.0))
        assertEquals(GnssFrequencyBand.L2_E5B, GnssFrequencyBandPolicy.classify(1_207_140_000.0))
    }

    @Test
    fun groupsNearbyGlonassChannelsIntoOneL1Band() {
        val bands = GnssFrequencyBandPolicy.observedBands(
            listOf(1_602_000_000.0, 1_605_000_000.0, 1_575_420_000.0)
        )
        assertEquals(setOf(GnssFrequencyBand.L1_E1_B1), bands)
        assertFalse(GnssFrequencyBandPolicy.indicatesMultiFrequency(bands))
    }

    @Test
    fun distinctBandsAreEvidenceOfMultifrequencyReception() {
        val bands = GnssFrequencyBandPolicy.observedBands(
            listOf(1_575_420_000.0, 1_176_450_000.0)
        )
        assertEquals(2, bands.size)
        assertTrue(GnssFrequencyBandPolicy.indicatesMultiFrequency(bands))
    }

    @Test
    fun rejectsInvalidAndUnknownFrequencies() {
        assertNull(GnssFrequencyBandPolicy.classify(Double.NaN))
        assertNull(GnssFrequencyBandPolicy.classify(Double.POSITIVE_INFINITY))
        assertNull(GnssFrequencyBandPolicy.classify(900_000_000.0))
    }
}
