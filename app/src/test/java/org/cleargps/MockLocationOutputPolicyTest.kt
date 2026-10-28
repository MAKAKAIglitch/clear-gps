package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationOutputPolicyTest {
    private val apiLevels = listOf(26, 30, 31, 35)

    @Test
    fun gpsAndAutoModesNeverReplacePhysicalGpsOrNetworkInputs() {
        for (apiLevel in apiLevels) {
            val expected = if (apiLevel >= 31) listOf("fused") else emptyList()
            assertEquals(
                "publishGpsProvider=false, api=$apiLevel",
                expected,
                systemMockProviderNames(
                    publishGpsProvider = false,
                    alsoPublishNetworkProvider = false,
                    apiLevel = apiLevel
                )
            )
            // Network output must not be enabled on its own when the physical GPS provider is preserved.
            assertEquals(
                "GPS preserved even if network flag was set, api=$apiLevel",
                expected,
                systemMockProviderNames(
                    publishGpsProvider = false,
                    alsoPublishNetworkProvider = true,
                    apiLevel = apiLevel
                )
            )
        }
    }

    @Test
    fun radioModePublishesOnlyProvidersAllowedByApiAndOptions() {
        for (apiLevel in apiLevels) {
            val expectedWithNetwork = buildList {
                add("gps")
                add("network")
                if (apiLevel >= 31) add("fused")
            }
            assertEquals(
                "radio + network, api=$apiLevel",
                expectedWithNetwork,
                systemMockProviderNames(
                    publishGpsProvider = true,
                    alsoPublishNetworkProvider = true,
                    apiLevel = apiLevel
                )
            )

            val expectedWithoutNetwork = buildList {
                add("gps")
                if (apiLevel >= 31) add("fused")
            }
            assertEquals(
                "radio without network, api=$apiLevel",
                expectedWithoutNetwork,
                systemMockProviderNames(
                    publishGpsProvider = true,
                    alsoPublishNetworkProvider = false,
                    apiLevel = apiLevel
                )
            )
        }
    }

    @Test
    fun fusedSystemProviderAppearsAtAndroid12AndLaterOnly() {
        assertFalse(systemMockProviderNames(true, false, 30).contains("fused"))
        assertTrue(systemMockProviderNames(true, false, 31).contains("fused"))
    }

    @Test
    fun mockLocationTimestampsMustBePresentAndNotFromTheFuture() {
        assertTrue(isValidMockLocationTimestamp(1_798_000_000_000L, 10L, 10L))
        assertTrue(isValidMockLocationTimestamp(1_798_000_000_000L, 9L, 10L))

        assertFalse(isValidMockLocationTimestamp(0L, 10L, 10L))
        assertFalse(isValidMockLocationTimestamp(1_798_000_000_000L, 0L, 10L))
        assertFalse(isValidMockLocationTimestamp(1_798_000_000_000L, -1L, 10L))
        assertFalse(isValidMockLocationTimestamp(1_798_000_000_000L, 11L, 10L))
        assertFalse(isValidMockLocationTimestamp(1_798_000_000_000L, 10L, 0L))
    }

    @Test
    fun validCoordinatesIncludeLegalEdges() {
        assertTrue(isValidMockCoordinate(0.0, 0.0))
        assertTrue(isValidMockCoordinate(55.0, 37.0))
        assertTrue(isValidMockCoordinate(-90.0, -180.0))
        assertTrue(isValidMockCoordinate(90.0, 180.0))
    }

    @Test
    fun invalidCoordinatesAreRejected() {
        assertFalse(isValidMockCoordinate(Double.NaN, 0.0))
        assertFalse(isValidMockCoordinate(0.0, Double.NaN))
        assertFalse(isValidMockCoordinate(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(isValidMockCoordinate(0.0, Double.NEGATIVE_INFINITY))
        assertFalse(isValidMockCoordinate(-90.000001, 0.0))
        assertFalse(isValidMockCoordinate(90.000001, 0.0))
        assertFalse(isValidMockCoordinate(0.0, -180.000001))
        assertFalse(isValidMockCoordinate(0.0, 180.000001))
    }
}
