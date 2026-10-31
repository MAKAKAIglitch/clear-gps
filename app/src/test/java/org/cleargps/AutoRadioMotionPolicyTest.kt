package org.cleargps

import org.cleargps.fusion.PositionMeasurement
import org.cleargps.fusion.PositionMeasurementSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRadioMotionPolicyTest {
    @Test
    fun firstRadioFixCanEstablishMotionAnchor() {
        assertTrue(AutoRadioMotionPolicy.allows(null, radio(52.0, 5.0, 10_000_000_000L)))
    }

    @Test
    fun plausibleMovementIsAccepted() {
        assertTrue(AutoRadioMotionPolicy.allows(radio(52.0, 5.0, 10_000_000_000L), radio(52.0005, 5.0, 20_000_000_000L)))
    }

    @Test
    fun implausibleJumpIsRejected() {
        assertFalse(AutoRadioMotionPolicy.allows(radio(52.0, 5.0, 10_000_000_000L), radio(52.2, 5.0, 40_000_000_000L)))
    }

    @Test
    fun nonMonotonicTimestampsAreRejected() {
        assertFalse(AutoRadioMotionPolicy.allows(radio(52.0, 5.0, 20_000_000_000L), radio(52.0001, 5.0, 19_000_000_000L)))
    }

    @Test
    fun antimeridianUsesShortestLongitudeDistance() {
        assertTrue(AutoRadioMotionPolicy.allows(radio(10.0, 179.999, 10_000_000_000L), radio(10.0, -179.999, 20_000_000_000L)))
    }

    @Test
    fun gpsMeasurementCannotBeARadioAnchor() {
        val gps = PositionMeasurement(PositionMeasurementSource.GNSS, 52.0, 5.0, 10.0, 10_000_000_000L)
        assertFalse(AutoRadioMotionPolicy.allows(gps, radio(52.0001, 5.0, 20_000_000_000L)))
    }

    @Test
    fun largeUncertaintyDoesNotAuthorizeAnUnlimitedJump() {
        val previous = PositionMeasurement(PositionMeasurementSource.RADIO, 52.0, 5.0, 50_000.0, 10_000_000_000L)
        val next = PositionMeasurement(PositionMeasurementSource.RADIO, 52.02, 5.0, 50_000.0, 11_000_000_000L)
        assertFalse(AutoRadioMotionPolicy.allows(previous, next))
    }

    private fun radio(latitude: Double, longitude: Double, timestampNanos: Long) =
        PositionMeasurement(PositionMeasurementSource.RADIO, latitude, longitude, 10.0, timestampNanos)
}
