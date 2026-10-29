package org.cleargps

import org.json.JSONObject
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
        val cell = BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:250:1:1234:567890", -91))
        assertEquals("lte", cell?.radioType)
        assertEquals(250, cell?.mobileCountryCode)
        assertEquals(1, cell?.mobileNetworkCode)
        assertEquals(1234, cell?.locationAreaCode)
        assertEquals(567890L, cell?.cellId)
        assertEquals(-91, cell?.signalStrength)
    }

    @Test
    fun wifiAndBleSignalsAreMappedWithAgeAndFrequencyButNamesAreNeverSent() {
        val wifi = BeaconDbProtocol.wifiFromSignal(
            RadioSignal(
                "wifi", "aa:bb:cc:dd:ee:ff", -50,
                frequency = 5_180,
                ssid = "Visible Network",
                observedAtElapsedRealtimeNanos = 9_000_000_000L
            ),
            nowElapsedRealtimeNanos = 10_000_000_000L
        )
        val beacon = BeaconDbProtocol.bluetoothFromSignal(
            RadioSignal("ble", "00:11:22:33:44:55", -73, observedAtElapsedRealtimeNanos = 8_500_000_000L),
            nowElapsedRealtimeNanos = 10_000_000_000L
        )
        assertEquals(1_000L, wifi?.ageMillis)
        assertEquals(5_180, wifi?.frequencyMhz)
        assertEquals(1_500L, beacon?.ageMillis)

        val request = BeaconDbRequest(
            cells = listOf(BeaconDbCell("lte", 250, 1, 10, 101, -90)),
            wifiAccessPoints = listOf(wifi!!),
            bluetoothBeacons = listOf(beacon!!)
        )
        val json = JSONObject(BeaconDbProtocol.buildRequestBody(request))
        assertTrue(json.has("wifiAccessPoints"))
        assertTrue(json.has("bluetoothBeacons"))
        assertTrue(json.has("cellTowers"))
        assertFalse(json.toString().contains("Visible Network"))
        assertFalse(json.toString().contains("advertisementPayload"))
    }

    @Test
    fun hiddenUnknownAndNomapWifiAreFiltered() {
        val valid = BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:04", -44, ssid = "VisibleNetwork")
        val normalized = BeaconDbRequest(wifiAccessPoints = listOf(
            BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:01", -40, ssid = ""),
            BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:02", -41, ssid = "<unknown ssid>"),
            BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:03", -42, ssid = "MyNetwork_nomap"),
            valid
        )).normalized()
        assertEquals(listOf("aa:bb:cc:dd:ee:04"), normalized.wifiAccessPoints.map { it.identifier })
    }

    @Test
    fun randomOrMulticastBleAddressesAreExcluded() {
        assertTrue(BeaconDbBluetoothBeacon("00:11:22:33:44:55", -70).isValid())
        assertFalse(BeaconDbBluetoothBeacon("02:11:22:33:44:55", -71).isValid())
        assertFalse(BeaconDbBluetoothBeacon("01:11:22:33:44:55", -72).isValid())
        assertFalse(BeaconDbBluetoothBeacon("invalid", -70).isValid())
    }

    @Test
    fun wifiAndBleRequireTwoUniqueIdentifiersOnlyWhenNoCellExists() {
        val oneWifi = BeaconDbRequest(wifiAccessPoints = listOf(
            BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:01", -45, ssid = "one")
        ))
        val twoWifi = oneWifi.copy(wifiAccessPoints = oneWifi.wifiAccessPoints + BeaconDbWifiAccessPoint(
            "aa:bb:cc:dd:ee:02", -66, ssid = "two"
        ))
        assertFalse(oneWifi.canLookup())
        assertTrue(twoWifi.canLookup())
        assertTrue(oneWifi.copy(cells = listOf(BeaconDbCell("lte", 250, 1, 10, 101, -80))).canLookup())
    }

    @Test
    fun requestContainsAllRadiosAndDisablesIpAndCoarseFallbacks() {
        val body = JSONObject(BeaconDbProtocol.buildRequestBody(BeaconDbRequest(
            cells = listOf(
                BeaconDbCell("gsm", 250, 1, 10, 20, -70),
                BeaconDbCell("lte", 250, 1, 11, 30, -86)
            ),
            wifiAccessPoints = listOf(
                BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:01", -50, ageMillis = 10, frequencyMhz = 2_412, ssid = "Home"),
                BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:02", -72, ssid = "Office")
            ),
            bluetoothBeacons = listOf(BeaconDbBluetoothBeacon("00:11:22:33:44:55", -74, 80))
        )))
        assertFalse(body.getBoolean("considerIp"))
        assertFalse(body.getJSONObject("fallbacks").getBoolean("lacf"))
        assertFalse(body.getJSONObject("fallbacks").getBoolean("ipf"))
        assertEquals(2, body.getJSONArray("cellTowers").length())
        assertEquals(2, body.getJSONArray("wifiAccessPoints").length())
        assertEquals(1, body.getJSONArray("bluetoothBeacons").length())
        assertEquals(-50, body.getJSONArray("wifiAccessPoints").getJSONObject(0).getInt("signalStrength"))
        assertFalse(body.toString().contains("Home"))
        assertFalse(body.toString().contains("Office"))
    }

    @Test
    fun duplicatesAreRemovedAndFingerprintIsOrderIndependent() {
        val cell = BeaconDbCell("lte", 250, 1, 11, 30, -86)
        val wifi = BeaconDbWifiAccessPoint("aa:bb:cc:dd:ee:01", -50, ssid = "A")
        val a = BeaconDbRequest(cells = listOf(cell, cell), wifiAccessPoints = listOf(wifi, wifi))
        val b = BeaconDbRequest(cells = listOf(cell), wifiAccessPoints = listOf(wifi))
        val json = JSONObject(BeaconDbProtocol.buildRequestBody(a))
        assertEquals(1, json.getJSONArray("cellTowers").length())
        assertEquals(1, json.getJSONArray("wifiAccessPoints").length())
        assertEquals(BeaconDbProtocol.cacheFingerprint(a), BeaconDbProtocol.cacheFingerprint(b))
    }

    @Test
    fun unsupportedRadiosAndInvalidCellsAreRejected() {
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("wifi", "aa:bb:cc:dd:ee:ff", -50)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("ble", "aa:bb:cc:dd:ee:ff", -50)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "nr:250:1:123:987654321", -75)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:250:1:123:0", -70)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:9999:1:123:100", -70)))
        assertNull(BeaconDbProtocol.cellFromSignal(RadioSignal("cell", "lte:250:1:123:100", -200)))
    }

    @Test
    fun validCoordinateResponseIsAcceptedWithoutFallback() {
        val result = BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":52.123456,"lng":40.654321},"accuracy":450.0}"""
        ) ?: throw AssertionError("Valid response without fallback should be accepted.")
        assertEquals(52.123456, result.latitude, 0.000001)
        assertEquals(40.654321, result.longitude, 0.000001)
        assertEquals(450.0, result.accuracyMeters, 0.000001)
    }

    @Test
    fun serverFallbackResponsesAreRejectedEvenWhenCoordinatesArePresent() {
        assertNull(BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":52.1,"lng":40.2},"accuracy":450.0,"fallback":"lacf"}"""
        ))
        assertNull(BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":52.1,"lng":40.2},"accuracy":450.0,"fallback":"ipf"}"""
        ))
    }

    @Test
    fun malformedCoordinatesAndUnreliableAccuracyAreRejected() {
        assertNull(BeaconDbProtocol.parseCoordinateResponse("""{"accuracy":450.0}"""))
        assertNull(BeaconDbProtocol.parseCoordinateResponse("""{"location":{"lat":52.1},"accuracy":450.0}"""))
        assertNull(BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":91.0,"lng":40.2},"accuracy":450.0}"""
        ))
        assertNull(BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":52.1,"lng":181.0},"accuracy":450.0}"""
        ))
        assertNull(BeaconDbProtocol.parseCoordinateResponse(
            """{"location":{"lat":52.1,"lng":40.2},"accuracy":0.0}"""
        ))
    }
}
