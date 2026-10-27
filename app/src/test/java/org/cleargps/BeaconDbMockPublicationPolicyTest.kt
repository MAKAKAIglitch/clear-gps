package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconDbMockPublicationPolicyTest {
    @Test
    fun explicitPublicationWindowIsExactlyTwoMinutes() {
        assertEquals(120_000L, BeaconDbMockPublicationPolicy.OUTPUT_WINDOW_MS)
        assertFalse(
            BeaconDbMockPublicationPolicy.hasExpired(219_999L, 220_000L)
        )
        assertTrue(
            BeaconDbMockPublicationPolicy.hasExpired(220_000L, 220_000L)
        )
        assertTrue(
            BeaconDbMockPublicationPolicy.hasExpired(220_001L, 220_000L)
        )
    }

    @Test
    fun republishDelayNeverExceedsConfiguredIntervalOrDeadline() {
        assertEquals(
            5_000L,
            BeaconDbMockPublicationPolicy.nextDelayMillis(10_000L, 130_000L)
        )
        assertEquals(
            1_250L,
            BeaconDbMockPublicationPolicy.nextDelayMillis(128_750L, 130_000L)
        )
        assertEquals(
            1L,
            BeaconDbMockPublicationPolicy.nextDelayMillis(130_000L, 130_000L)
        )
        assertEquals(5_000L, BeaconDbMockPublicationPolicy.REPUBLISH_INTERVAL_MS)
    }

    @Test
    fun uncertaintyGrowsByFifteenMetresPerSecondWhileEstimateIsHeld() {
        assertEquals(
            450.0,
            BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(450.0, 0L),
            0.000001
        )
        assertEquals(
            465.0,
            BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(450.0, 1_000L),
            0.000001
        )
        assertEquals(
            600.0,
            BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(450.0, 10_000L),
            0.000001
        )
        assertEquals(
            450.0,
            BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(450.0, -1_000L),
            0.000001
        )
    }

    @Test
    fun uncertaintyIsCappedAndInvalidBaseAccuracyIsRejected() {
        assertEquals(
            100_000.0,
            BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(99_900.0, 10_000L),
            0.000001
        )
        var rejected = false
        try {
            BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(Double.NaN, 1_000L)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
