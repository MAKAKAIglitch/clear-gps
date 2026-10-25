package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Test

class MockLocationOutputPolicyTest {
    @Test
    fun gpsAndAutoModesDoNotReplacePhysicalGpsInputOnModernAndroid() {
        assertEquals(listOf("fused"), systemMockProviderNames(false, false, 35))
    }

    @Test
    fun radioOnlyCanPublishToGpsNetworkAndSystemFusedProviders() {
        assertEquals(listOf("gps", "network", "fused"), systemMockProviderNames(true, true, 35))
    }

    @Test
    fun radioOnlyWithoutNetworkOutputStillUsesGpsAndSystemFused() {
        assertEquals(listOf("gps", "fused"), systemMockProviderNames(true, false, 35))
    }

    @Test
    fun preAndroid12DoesNotRegisterUnsupportedSystemFusedProvider() {
        assertEquals(listOf("gps"), systemMockProviderNames(true, false, 30))
        assertEquals(emptyList<String>(), systemMockProviderNames(false, false, 30))
    }
}
