package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMockLocationPolicyTest {
    @Test
    fun mockGpsIsFlaggedWhenClearGpsMockOutputIsOff() {
        assertTrue(shouldFlagExternalMockLocation("gps", true, false))
        assertTrue(shouldFlagExternalMockLocation("GPS", true, false))
    }

    @Test
    fun mockNetworkProviderIsFlaggedWhenClearGpsMockOutputIsOff() {
        assertTrue(shouldFlagExternalMockLocation("network", true, false))
    }

    @Test
    fun nonMockLocationIsNeverAnExternalMockAlert() {
        assertFalse(shouldFlagExternalMockLocation("gps", false, false))
        assertFalse(shouldFlagExternalMockLocation("network", false, false))
    }

    @Test
    fun expectedMockCallbacksAreIgnoredWhenOurMockOutputIsEnabled() {
        assertFalse(shouldFlagExternalMockLocation("gps", true, true))
        assertFalse(shouldFlagExternalMockLocation("network", true, true))
    }

    @Test
    fun unmonitoredOrMissingProviderIsNotClassifiedAsExternalGpsMock() {
        assertFalse(shouldFlagExternalMockLocation("fused", true, false))
        assertFalse(shouldFlagExternalMockLocation(null, true, false))
        assertFalse(shouldFlagExternalMockLocation("", true, false))
    }
}
