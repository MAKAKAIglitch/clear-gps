package org.cleargps

import org.cleargps.core.FilterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionPublicationPolicyTest {
    @Test
    fun highRiskGpsIsHeldEvenBeforeTemporalConfirmation() {
        assertTrue(PositionPublicationPolicy.shouldHoldGpsBackedOutput(
            FilterState.SUSPECT, 74, false, "GPS"
        ))
        assertTrue(PositionPublicationPolicy.shouldHideLiveGpsCoordinate(
            FilterState.SUSPECT, 74, false
        ))
    }

    @Test
    fun configuredRiskZoneAlwaysHidesGpsCandidate() {
        assertTrue(PositionPublicationPolicy.shouldHoldGpsBackedOutput(
            FilterState.ZONE_RISK, 75, true, "GPS"
        ))
        assertTrue(PositionPublicationPolicy.shouldHideLiveGpsCoordinate(
            FilterState.NORMAL, 10, true
        ))
        assertNotNull(PositionPublicationPolicy.gpsCoordinateHoldReason(
            FilterState.ZONE_RISK, 75, true
        ))
    }

    @Test
    fun independentNetworkFallbackIsNotBlockedBySuspiciousGps() {
        assertFalse(PositionPublicationPolicy.shouldHoldGpsBackedOutput(
            FilterState.CORRECTING, 75, true, "NETWORK"
        ))
    }

    @Test
    fun normalLowRiskGpsOutsideZoneRemainsUsable() {
        assertFalse(PositionPublicationPolicy.shouldHoldGpsBackedOutput(
            FilterState.NORMAL, 22, false, "GPS"
        ))
        assertFalse(PositionPublicationPolicy.shouldHideLiveGpsCoordinate(
            FilterState.NORMAL, 22, false
        ))
        assertEquals(null, PositionPublicationPolicy.gpsCoordinateHoldReason(
            FilterState.NORMAL, 22, false
        ))
    }
}
