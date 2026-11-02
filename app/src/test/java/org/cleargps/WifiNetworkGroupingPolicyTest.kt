package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkGroupingPolicyTest {
    @Test
    fun onlyMatchingMldMacGroupsDifferentBssidsOnTheMap() {
        val first = point(1, "a8:bb:cc:00:00:01", ssid = "Home", mld = "02:11:22:33:44:55")
        val second = point(2, "a8:bb:cc:00:00:02", ssid = "Home", mld = "02:11:22:33:44:55")
        val neighbour = point(3, "a8:bb:cc:00:00:03", ssid = "Home", mld = null)

        val markers = WifiNetworkGroupingPolicy.buildMapMarkers(listOf(first, second, neighbour))

        assertEquals(2, markers.size)
        assertTrue(markers.any { it.pointIds == listOf(1L, 2L) })
        assertTrue(markers.any { it.pointIds == listOf(3L) })
    }

    @Test
    fun sameSsidAndVendorPrefixAreOnlySuggestedNotAutoGrouped() {
        val first = point(1, "a8:bb:cc:00:00:01", "Home")
        val second = point(2, "a8:bb:cc:00:00:02", "Home")

        val suggestions = WifiNetworkGroupingPolicy.suggestCandidates(first, listOf(first, second))

        assertEquals(1, suggestions.size)
        assertEquals(2L, suggestions.single().point.id)
        assertTrue(suggestions.single().reasons.any { it.contains("SSID") })
    }

    @Test
    fun sameSsidAloneDoesNotCreateCandidate() {
        val first = point(1, "a8:bb:cc:00:00:01", "Home", latitude = 52.0, longitude = 13.0)
        val second = point(2, "de:ad:be:00:00:02", "Home", latitude = 52.02, longitude = 13.02)

        assertTrue(WifiNetworkGroupingPolicy.suggestCandidates(first, listOf(first, second)).isEmpty())
    }

    @Test
    fun hiddenInterfaceRequiresVendorProximityAndNearSimultaneousSightings() {
        val first = point(1, "a8:bb:cc:00:00:01", "Home")
        val hidden = point(2, "a8:bb:cc:00:00:02", "", latitude = 52.0002, longitude = 13.0002)

        val suggestions = WifiNetworkGroupingPolicy.suggestCandidates(first, listOf(first, hidden))

        assertEquals(1, suggestions.size)
        assertTrue(suggestions.single().reasons.any { it.contains("возможна скрытая сеть") })
    }

    @Test
    fun excludedManualGroupMembersAreNotSuggestedAgain() {
        val first = point(1, "a8:bb:cc:00:00:01", "Home")
        val second = point(2, "a8:bb:cc:00:00:02", "Home")

        assertTrue(
            WifiNetworkGroupingPolicy.suggestCandidates(first, listOf(first, second), setOf(2L)).isEmpty()
        )
    }

    private fun point(
        id: Long,
        mac: String,
        ssid: String,
        latitude: Double? = 52.0,
        longitude: Double? = 13.0,
        mld: String? = null
    ) = RadioPointRecord(
        id = id,
        type = "wifi",
        identifier = mac,
        label = ssid,
        ssid = ssid,
        notes = "",
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 15.0,
        rssi = -55,
        peakRssi = -45,
        frequency = 2412,
        firstSeen = 1_000_000L,
        lastSeen = 1_060_000L,
        sightings = 5,
        positionSource = "test",
        wifiMldMac = mld
    )
}
