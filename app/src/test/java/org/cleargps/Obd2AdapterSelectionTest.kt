package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2AdapterSelectionTest {
    @Test
    fun legacyMacPreferenceRemainsClassicSpp() {
        val selection = Obd2AdapterSelection.parse("aa:bb:cc:dd:ee:ff")
        assertEquals(Obd2TransportType.CLASSIC_SPP, selection.transportType)
        assertEquals("AA:BB:CC:DD:EE:FF", selection.address)
        assertEquals("AA:BB:CC:DD:EE:FF", selection.key)
    }

    @Test
    fun bleKeyKeepsTransportTypeAndHasDistinctPrefix() {
        val selection = Obd2AdapterSelection.parse("ble:aa:bb:cc:dd:ee:ff")
        assertEquals(Obd2TransportType.BLE_GATT, selection.transportType)
        assertEquals("AA:BB:CC:DD:EE:FF", selection.address)
        assertEquals("ble:AA:BB:CC:DD:EE:FF", selection.key)
        assertEquals(selection, Obd2AdapterSelection.parse(selection.key))
    }

    @Test
    fun invalidAddressesAndMalformedBleKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Obd2AdapterSelection.parse("not-an-address")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Obd2AdapterSelection.parse("ble:obd-adapter")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Obd2AdapterSelection.parse("bluetooth:AA:BB:CC:DD:EE:FF")
        }
    }

    @Test
    fun filterRecognizesCommonObdNamesButDoesNotMatchArbitraryDevices() {
        assertTrue(Obd2AdapterNameFilter.looksLikeObdAdapter("VEEPEAK BLE+"))
        assertTrue(Obd2AdapterNameFilter.looksLikeObdAdapter("ELM327 v2.1"))
        assertTrue(Obd2AdapterNameFilter.looksLikeObdAdapter("V-LINK"))
        assertTrue(Obd2AdapterNameFilter.looksLikeObdAdapter("Carista OBD"))
        assertFalse(Obd2AdapterNameFilter.looksLikeObdAdapter("Pixel Buds"))
        assertFalse(Obd2AdapterNameFilter.looksLikeObdAdapter("My headset"))
        assertFalse(Obd2AdapterNameFilter.looksLikeObdAdapter(null))
        assertFalse(Obd2AdapterNameFilter.looksLikeObdAdapter("   "))
    }
    @Test
    fun privateWifiIpv4AndPortHaveRoundTrippableSelectionKey() {
        val selection = Obd2AdapterSelection.parse("wifi:192.168.0.10:35000")
        assertEquals(Obd2TransportType.WIFI_TCP, selection.transportType)
        assertEquals("192.168.0.10", selection.address)
        assertEquals(35000, selection.port)
        assertEquals("wifi:192.168.0.10:35000", selection.key)
        assertEquals(selection, Obd2AdapterSelection.parse(selection.key))
    }

    @Test
    fun wifiEndpointRejectsPublicAddressesInvalidPortsAndMalformedHosts() {
        listOf(
            "wifi:8.8.8.8:35000",
            "wifi:192.168.0.10:0",
            "wifi:192.168.0.10:65536",
            "wifi:192.168.0.10:abc",
            "wifi:192.168.0.10",
            "wifi:192.168.0.256:35000",
            "wifi:192.168.000.10:35000",
            "wifi:2001:db8::1:35000"
        ).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                Obd2AdapterSelection.parse(key)
            }
        }
        assertTrue(Obd2WifiAddress.isPrivateIpv4("10.0.0.2"))
        assertTrue(Obd2WifiAddress.isPrivateIpv4("172.16.1.2"))
        assertTrue(Obd2WifiAddress.isPrivateIpv4("192.168.0.10"))
        assertFalse(Obd2WifiAddress.isPrivateIpv4("8.8.8.8"))
        assertFalse(Obd2WifiAddress.isPrivateIpv4("169.254.1.2"))
    }

}
