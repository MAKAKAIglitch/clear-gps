package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    @Test
    fun validCoordinateResponseIsAcceptedWithoutFallback() {
        val result = BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":52.123456,"lng":40.654321},"accuracy":450.0}"""
        )

        val coordinate = result
            ?: throw AssertionError("A valid response without fallback must be accepted.")
        assertEquals(52.123456, coordinate.latitude, 0.000001)
        assertEquals(40.654321, coordinate.longitude, 0.000001)
        assertEquals(450.0, coordinate.accuracyMeters, 0.000001)
    }

    @Test
    fun serverFallbackResponsesAreRejectedEvenWhenCoordinatesArePresent() {
        val lacf = """{"location":{"lat":52.1,"lng":40.2},"accuracy":450.0,"fallback":"lacf"}"""
        val ipf = """{"location":{"lat":52.1,"lng":40.2},"accuracy":450.0,"fallback":"ipf"}"""

        assertNull(BeaconDbProtocol.parseCoordinateResponse(lacf))
        assertNull(BeaconDbProtocol.parseCoordinateResponse(ipf))
    }

    @Test
    fun missingLocationMalformedJsonAndOutOfRangeCoordinatesAreRejected() {
        assertNull(BeaconDbProtocol.parseCoordinateResponse("""{"accuracy":450.0}"""))
        assertNull(BeaconDbProtocol.parseCoordinateResponse("""{"location":{"lat":52.1}"""))
        assertNull(
            BeaconDbProtocol.parseCoordinateResponse(
                """{"location":{"lat":91.0,"lng":40.2},"accuracy":450.0}"""
            )
        )
        assertNull(
            BeaconDbProtocol.parseCoordinateResponse(
                """{"location":{"lat":52.1,"lng":181.0},"accuracy":450.0}"""
            )
        )
    }

    @Test
    fun nonPositiveAndExcessiveAccuracyAreRejected() {
        assertNull(
            BeaconDbProtocol.parseCoordinateResponse(
                """{"location":{"lat":52.1,"lng":40.2},"accuracy":0.0}"""
            )
        )
        assertNull(
            BeaconDbProtocol.parseCoordinateResponse(
                """{"location":{"lat":52.1,"lng":40.2},"accuracy":10000001.0}"""
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyOrUnsupportedCellListCannotCreateRequest() {
        BeaconDbProtocol.buildCellOnlyRequestBody(
            listOf(BeaconDbCell("nr", 250, 1, 11, 30, -86))
        )
    }
}
