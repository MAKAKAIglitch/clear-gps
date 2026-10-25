package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2ProtocolTest {
    @Test
    fun requestsOnlyKnownMode01ReadPids() {
        assertEquals("0100", Obd2Protocol.requestFor(0x00))
        assertEquals("010C", Obd2Protocol.requestFor(0x0C))
        assertEquals("010D", Obd2Protocol.requestFor(0x0D))
        assertEquals("0105", Obd2Protocol.requestFor(0x05))
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x04) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x04 + 0x10) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.decode(0x04, "4104 80") }
    }

    @Test
    fun discoversSupportedPidsFromStandardBitmap() {
        // 41 00 BE 1F A8 13 advertises common Mode 01 values including 05, 0C and 0D.
        val supported = Obd2Protocol.supportedPids("7E8064100BE1FA813>")
        assertTrue(supported!!.contains(0x05))
        assertTrue(supported.contains(0x0C))
        assertTrue(supported.contains(0x0D))
        assertTrue(supported.contains(0x20))
        assertFalse(supported.contains(0x08))
    }

    @Test
    fun fallsBackToProbingWhenCapabilityBitmapIsMissing() {
        assertNull(Obd2Protocol.supportedPids("NO DATA>"))
        assertNull(Obd2Protocol.supportedPids("4100BE1F"))
    }

    @Test
    fun decodesRpmWithAdapterEchoAndWhitespace() {
        // 0x1A F8 = 6904; divide by four => 1726 rpm.
        assertEquals(1726, Obd2Protocol.decode(0x0C, "010C\r\r41 0C 1A F8\r>"))
    }

    @Test
    fun decodesRpmWhenCanHeaderIsPresentAndSpacesAreRemoved() {
        // CAN header + length byte + positive response + two data bytes.
        assertEquals(1726, Obd2Protocol.decode(0x0C, "7E804410C1AF8>"))
    }

    @Test
    fun decodesVehicleSpeedAndCoolantTemperature() {
        assertEquals(88, Obd2Protocol.decode(0x0D, "410D58>"))
        assertEquals(90, Obd2Protocol.decode(0x05, "410582>"))
    }

    @Test
    fun ignoresNoDataAndNegativeResponses() {
        assertNull(Obd2Protocol.decode(0x0C, "NO DATA>"))
        assertNull(Obd2Protocol.decode(0x0D, "7F 01 12>"))
        assertNull(Obd2Protocol.decode(0x05, "?"))
    }

    @Test
    fun rejectsIncompleteAndWrongPidResponses() {
        assertNull(Obd2Protocol.decode(0x0C, "41 0C 1A>"))
        assertNull(Obd2Protocol.decode(0x0D, "41 0C 1A F8>"))
        assertNull(Obd2Protocol.decode(0x05, "41 05>"))
    }

    @Test
    fun telemetryCountsOnlyParametersActuallyReceived() {
        val telemetry = Obd2Protocol.telemetry(
            rpmResponse = "410C1AF8",
            speedResponse = "NO DATA>",
            coolantResponse = "410582",
            now = 1_234L
        )
        assertEquals(1726, telemetry.engineRpm)
        assertNull(telemetry.vehicleSpeedKmh)
        assertEquals(90, telemetry.coolantTemperatureC)
        assertEquals(1_234L, telemetry.updatedAt)
        assertEquals(2, telemetry.parametersReceived)
        assertTrue(telemetry.parametersReceived in 0..3)
        assertFalse(telemetry.parametersReceived > 3)
    }
}