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
}
