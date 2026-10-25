package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconDbProtocolTest {
    @Test
    fun cellSignalIsMappedOnlyForSupportedCellularRadios() {
        val cell = BeaconDbProtocol.cellFromSignal(
            RadioSignal("cell", "lte:250:1:1234:567890", -91)
        )

        assertEquals("lte", cell?.radioType)
        assertEquals(250, cell?.mobileCountryCode)
        assertEquals(1, cell?.mobileNetworkCode)
        assertEquals(1234, cell?.locationAreaCode)
        assertEquals(567890L, cell?.cellId)
        assertEquals(-91, cell?.signalStrength)
    }

    @Test
    fun wifiBleAndUndocumentedNrTypesAreNotMapped() {
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("wifi", "aa:bb:cc:dd:ee:ff", -50)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("ble", "aa:bb:cc:dd:ee:ff", -50)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "nr:250:1:123:987654321", -75)))
    }

    @Test
    fun malformedAndInvalidCellIdentifiersAreRejected() {
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:250:1:123:0", -70)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:9999:1:123:100", -70)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:250:1:123:100", -200)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "not-a-cell", -70)))
    }

    @Test
    fun requestContainsOnlyCellTowersAndDisablesIpFallback() {
        val body = BeaconDbProtocol.buildCellOnlyRequestBody(
            listOf(
                BeaconDbCell("gsm", 250, 1, 10, 20, -70),
                BeaconDbCell("lte", 250, 1, 11, 30, -86)
            )
        )

        assertTrue(body.contains("\"considerIp\":false"))
        assertTrue(body.contains("\"lacf\":false"))
        assertTrue(body.contains("\"ipf\":false"))
        assertTrue(body.contains("\"cellTowers\":["))
        assertTrue(body.contains("\"radioType\":\"gsm\""))
        assertTrue(body.contains("\"radioType\":\"lte\""))
        assertFalse(body.contains("wifiAccessPoints"))
        assertFalse(body.contains("bluetoothBeacons"))
        assertFalse(body.contains("\"latitude\""))
        assertFalse(body.contains("\"longitude\""))
        assertFalse(body.contains("\"items\""))
    }

    @Test
    fun duplicateCellsAreSerializedOnce() {
        val cell = BeaconDbCell("lte", 250, 1, 11, 30, -86)
        val body = BeaconDbProtocol.buildCellOnlyRequestBody(listOf(cell, cell))

        assertEquals(1, Regex("\"cellId\":30").findAll(body).count())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyOrUnsupportedCellListCannotCreateRequest() {
        BeaconDbProtocol.buildCellOnlyRequestBody(
            listOf(BeaconDbCell("nr", 250, 1, 11, 30, -86))
        )
    }
}
