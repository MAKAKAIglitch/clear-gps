package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleBeaconAdvertisementParserTest {
    @Test
    fun decodesIBeaconIdentityAndIgnoresBluetoothAddress() {
        val payload = byteArrayOf(
            0x02, 0x15,
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
            0x00, 0x12, 0x00, 0x34, 0xC5.toByte()
        )

        val first = BleBeaconAdvertisementParser.parse(
            mapOf(BleBeaconAdvertisementParser.APPLE_COMPANY_ID to payload),
            emptyMap()
        ).filterIsInstance<BleBeaconFrame.StableIdentity>().single()
        val second = BleBeaconAdvertisementParser.parse(
            mapOf(BleBeaconAdvertisementParser.APPLE_COMPANY_ID to payload.copyOf()),
            emptyMap()
        ).filterIsInstance<BleBeaconFrame.StableIdentity>().single()

        assertEquals("ibeacon", first.protocol)
        assertEquals("ibeacon:00112233-4455-6677-8899-aabbccddeeff:18:52", first.identifier)
        assertEquals(-59, first.calibratedTxPowerDbm)
        assertEquals(first.identifier, second.identifier)
    }

    @Test
    fun rejectsMalformedIBeaconPayloadsAndWrongCompanyId() {
        val payload = ByteArray(23)
        payload[0] = 0x02
        payload[1] = 0x15
        assertTrue(BleBeaconAdvertisementParser.parse(
            mapOf(0x1234 to payload), emptyMap()
        ).isEmpty())
        assertTrue(BleBeaconAdvertisementParser.parse(
            mapOf(BleBeaconAdvertisementParser.APPLE_COMPANY_ID to payload.copyOf(22)), emptyMap()
        ).isEmpty())
    }

    @Test
    fun decodesAltBeaconIdentityIncludingManufacturerNamespace() {
        val payload = ByteArray(24)
        payload[0] = 0xBE.toByte()
        payload[1] = 0xAC.toByte()
        for (index in 0 until 20) payload[index + 2] = index.toByte()
        payload[22] = 0xC5.toByte()
        payload[23] = 0x7F

        val beacon = BleBeaconAdvertisementParser.parse(
            mapOf(0x0118 to payload), emptyMap()
        ).filterIsInstance<BleBeaconFrame.StableIdentity>().single()

        assertEquals("altbeacon", beacon.protocol)
        assertEquals(
            "altbeacon:0118:000102030405060708090a0b0c0d0e0f10111213",
            beacon.identifier
        )
        assertEquals(-59, beacon.calibratedTxPowerDbm)
    }

    @Test
    fun altBeaconCompanyIdAndBeaconIdBothParticipateInIdentity() {
        val payload = ByteArray(24)
        payload[0] = 0xBE.toByte()
        payload[1] = 0xAC.toByte()
        payload[22] = (-60).toByte()

        val a = BleBeaconAdvertisementParser.parse(mapOf(0x0118 to payload), emptyMap())
            .filterIsInstance<BleBeaconFrame.StableIdentity>().single()
        val b = BleBeaconAdvertisementParser.parse(mapOf(0x004C to payload), emptyMap())
            .filterIsInstance<BleBeaconFrame.StableIdentity>().single()

        assertFalse(a.identifier == b.identifier)
    }

    @Test
    fun rejectsMalformedAltBeaconLengthAndReferencePower() {
        val short = ByteArray(23)
        short[0] = 0xBE.toByte()
        short[1] = 0xAC.toByte()
        assertTrue(BleBeaconAdvertisementParser.parse(mapOf(0x0118 to short), emptyMap()).isEmpty())

        val invalidPower = ByteArray(24)
        invalidPower[0] = 0xBE.toByte()
        invalidPower[1] = 0xAC.toByte()
        invalidPower[22] = 1
        assertTrue(BleBeaconAdvertisementParser.parse(
            mapOf(0x0118 to invalidPower), emptyMap()
        ).isEmpty())
    }

    @Test
    fun decodesEddystoneUidIncludingLegacyShortFrame() {
        val standard = byteArrayOf(
            0x00, 0xE2.toByte(),
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x00, 0x00
        )
        val legacy = standard.copyOf(18)
        val first = parseEddystone(standard).filterIsInstance<BleBeaconFrame.StableIdentity>().single()
        val second = parseEddystone(legacy).filterIsInstance<BleBeaconFrame.StableIdentity>().single()

        assertEquals("eddystone-uid", first.protocol)
        assertEquals(
            "eddystone-uid:00010203040506070809:0a0b0c0d0e0f",
            first.identifier
        )
        assertEquals(first.identifier, second.identifier)
        assertEquals(-30, first.calibratedTxPowerDbm)
    }

    @Test
    fun rejectsEddystoneUidWithInvalidReservedBytesOrLength() {
        val valid = ByteArray(20)
        valid[0] = 0x00
        valid[1] = 0xE2.toByte()
        valid[18] = 1
        assertTrue(parseEddystone(valid).isEmpty())
        assertTrue(parseEddystone(valid.copyOf(19)).isEmpty())
    }

    @Test
    fun decodesEddystoneUrlUsingCompressedPrefixAndSuffix() {
        val payload = byteArrayOf(
            0x10, 0xE2.toByte(), 0x01,
            'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x08
        )
        val url = parseEddystone(payload).filterIsInstance<BleBeaconFrame.EddystoneUrl>().single()
        assertEquals("https://www.example.org", url.url)
        assertEquals(-30, url.calibratedTxPowerDbm)
        assertTrue(parseEddystone(byteArrayOf(0x10, 0xE2.toByte(), 0x7F, 0x41)).isEmpty())
    }

    @Test
    fun decodesEddystoneTelemetryAndUnavailableTemperature() {
        val payload = byteArrayOf(
            0x20, 0x00,
            0x0B, 0xB8.toByte(), // 3000 mV
            0x19, 0x80.toByte(), // 25.5 C, signed 8.8
            0x00, 0x00, 0x03, 0xE8.toByte(), // 1000 advertisements
            0x00, 0x00, 0x02, 0x58  // 600 seconds uptime
        )
        val telemetry = parseEddystone(payload)
            .filterIsInstance<BleBeaconFrame.EddystoneTelemetry>().single()
        assertEquals(3000, telemetry.batteryMillivolts)
        assertEquals(25.5, telemetry.temperatureCelsius!!, 0.001)
        assertEquals(1000L, telemetry.advertisementCount)
        assertEquals(600L, telemetry.uptimeSeconds)

        val unavailable = payload.copyOf().apply {
            this[4] = 0x80.toByte()
            this[5] = 0x00
        }
        assertNull(parseEddystone(unavailable)
            .filterIsInstance<BleBeaconFrame.EddystoneTelemetry>().single().temperatureCelsius)
    }

    @Test
    fun recognizesEddystoneEidButDoesNotPromoteItToStableIdentity() {
        val payload = byteArrayOf(
            0x30, 0xE2.toByte(),
            0x01, 0x23, 0x45, 0x67, 0x11, 0x22, 0x33, 0x44
        )
        val frames = parseEddystone(payload)
        assertTrue(frames.single() is BleBeaconFrame.EddystoneEphemeralId)
        assertTrue(frames.none { it is BleBeaconFrame.StableIdentity })
    }

    @Test
    fun recognizesSemBeaconAsAltBeaconIdentityWithEddystoneUrlScanResponse() {
        val alt = ByteArray(24).apply {
            this[0] = 0xBE.toByte()
            this[1] = 0xAC.toByte()
            this[22] = (-60).toByte()
        }
        val url = byteArrayOf(
            0x10, 0xE2.toByte(), 0x03,
            's'.code.toByte(), 'e'.code.toByte(), 'm'.code.toByte(),
            'b'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte(),
            'o'.code.toByte(), 'n'.code.toByte(), 0x00
        )
        val frames = BleBeaconAdvertisementParser.parse(
            mapOf(0x0118 to alt),
            mapOf(BleBeaconAdvertisementParser.EDDYSTONE_SERVICE_UUID to url)
        )
        val identity = frames.filterIsInstance<BleBeaconFrame.StableIdentity>().single()
        assertEquals("sembeacon", identity.protocol)
        assertTrue(frames.any { it is BleBeaconFrame.EddystoneUrl })
    }

    @Test
    fun randomizedAnonymousUnknownAndNonUnicastAddressesAreNotStableFallbacks() {
        assertTrue(BleAddressPolicy.isStablePublicAddress(0, 0, "00:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(1, 0, "00:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(255, 0, "00:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(65535, 0, "00:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(null, 0, "00:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(0, 0, "02:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(0, 0, "01:11:22:33:44:55"))
        assertFalse(BleAddressPolicy.isStablePublicAddress(0, 0, "not-a-mac"))
    }

    @Test
    fun decodesRuuviRawV2TelemetryAndUsesItsEmbeddedMacAsStableIdentity() {
        val payload = byteArrayOf(
            0x05,
            0x13, 0x88.toByte(),       // 25.00 C
            0x4E, 0x20,               // 50.00 %RH
            0xC8.toByte(), 0x7D,      // 101325 Pa
            0x03, 0xE8.toByte(),      // +1.000 g
            0xF8.toByte(), 0x30,      // -2.000 g
            0x00, 0x00,               // 0.000 g
            0xAF.toByte(), 0x0A,      // 3000 mV, -20 dBm
            0x07,                     // movement counter
            0x12, 0x34,               // measurement sequence
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55
        )
        val frame = parseRuuvi(payload)

        assertEquals("ruuvi", frame.identifier?.substringBefore(':'))
        assertEquals("ruuvi:00:11:22:33:44:55", frame.identifier)
        assertEquals(25.0, frame.temperatureCelsius!!, 0.001)
        assertEquals(50.0, frame.humidityPercent!!, 0.001)
        assertEquals(101325, frame.pressurePascals)
        assertEquals(1.0, frame.accelerationXG!!, 0.001)
        assertEquals(-2.0, frame.accelerationYG!!, 0.001)
        assertEquals(0.0, frame.accelerationZG!!, 0.001)
        assertEquals(3000, frame.batteryMillivolts)
        assertEquals(-20, frame.txPowerDbm)
        assertEquals(7, frame.movementCounter)
        assertEquals(0x1234, frame.measurementSequence)
        assertTrue(BleBeaconAdvertisementParser.summary(frame)!!.contains("temperature 25.0 °C"))
        assertTrue(BleBeaconAdvertisementParser.summary(frame)!!.contains("humidity 50.0 %"))
    }

    @Test
    fun ruuviRawV2UnavailableFieldsStayNullAndInvalidMacIsNotAnIdentity() {
        val payload = ByteArray(24).apply {
            this[0] = 0x05
            this[1] = 0x80.toByte(); this[2] = 0x00 // unavailable temperature
            this[3] = 0xFF.toByte(); this[4] = 0xFF.toByte()
            this[5] = 0xFF.toByte(); this[6] = 0xFF.toByte()
            this[7] = 0x80.toByte(); this[8] = 0x00
            this[9] = 0x80.toByte(); this[10] = 0x00
            this[11] = 0x80.toByte(); this[12] = 0x00
            this[13] = 0xFF.toByte(); this[14] = 0xFF.toByte()
            this[15] = 0xFF.toByte()
            this[16] = 0xFF.toByte(); this[17] = 0xFF.toByte()
            for (index in 18..23) this[index] = 0xFF.toByte()
        }
        val frame = parseRuuvi(payload)
        assertNull(frame.identifier)
        assertNull(frame.temperatureCelsius)
        assertNull(frame.humidityPercent)
        assertNull(frame.pressurePascals)
        assertNull(frame.accelerationXG)
        assertNull(frame.accelerationYG)
        assertNull(frame.accelerationZG)
        assertNull(frame.batteryMillivolts)
        assertNull(frame.txPowerDbm)
        assertNull(frame.movementCounter)
        assertNull(frame.measurementSequence)
    }

    @Test
    fun rejectsMalformedRuuviFramesAndWrongManufacturerId() {
        val payload = ByteArray(24).apply { this[0] = 0x05 }
        assertTrue(BleBeaconAdvertisementParser.parse(
            mapOf(0x1234 to payload), emptyMap()
        ).none { it is BleBeaconFrame.RuuviTelemetry })
        assertTrue(BleBeaconAdvertisementParser.parse(
            mapOf(BleBeaconAdvertisementParser.RUUVI_COMPANY_ID to payload.copyOf(23)),
            emptyMap()
        ).none { it is BleBeaconFrame.RuuviTelemetry })
        assertTrue(BleBeaconAdvertisementParser.parse(
            mapOf(BleBeaconAdvertisementParser.RUUVI_COMPANY_ID to payload.copyOf().apply { this[0] = 0x03 }),
            emptyMap()
        ).none { it is BleBeaconFrame.RuuviTelemetry })
    }

    private fun parseRuuvi(payload: ByteArray): BleBeaconFrame.RuuviTelemetry =
        BleBeaconAdvertisementParser.parse(
            mapOf(BleBeaconAdvertisementParser.RUUVI_COMPANY_ID to payload),
            emptyMap()
        ).filterIsInstance<BleBeaconFrame.RuuviTelemetry>().single()

    private fun parseEddystone(payload: ByteArray): List<BleBeaconFrame> =
        BleBeaconAdvertisementParser.parse(
            emptyMap(),
            mapOf(BleBeaconAdvertisementParser.EDDYSTONE_SERVICE_UUID to payload)
        )
}
