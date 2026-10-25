package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2ProtocolTest {
    @Test
    fun requestsOnlyCapabilityBitmapAndVehicleSpeed() {
        assertEquals("0100", Obd2Protocol.requestFor(0x00))
        assertEquals("010D", Obd2Protocol.requestFor(0x0D))
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x04) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x05) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x0C) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.decode(0x0C, "410C1AF8") }
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
    fun fallsBackToSpeedProbeWhenCapabilityBitmapIsMissing() {
        assertNull(Obd2Protocol.supportedPids("NO DATA>"))
        assertNull(Obd2Protocol.supportedPids("4100BE1F"))
    }

    @Test
    fun decodesVehicleSpeedAndHandlesAdapterHeaders() {
        assertEquals(88, Obd2Protocol.decode(0x0D, "410D58>"))
        assertEquals(88, Obd2Protocol.decode(0x0D, "010D\r\r41 0D 58\r>"))
        assertEquals(88, Obd2Protocol.decode(0x0D, "7E803410D58>"))
        assertEquals(0, Obd2Protocol.decode(0x0D, "410D00>"))
    }

    @Test
    fun rejectsNoDataNegativeIncompleteAndWrongPidResponses() {
        assertNull(Obd2Protocol.decode(0x0D, "NO DATA>"))
        assertNull(Obd2Protocol.decode(0x0D, "7F 01 12>"))
        assertNull(Obd2Protocol.decode(0x0D, "41 0D>"))
        assertNull(Obd2Protocol.decode(0x0D, "41 0C 58>"))
    }

    @Test
    fun rejectsImplausibleVehicleSpeed() {
        assertEquals(250, Obd2Protocol.decode(0x0D, "410DFA>"))
        assertNull(Obd2Protocol.decode(0x0D, "410DFB>"))
        assertNull(Obd2Protocol.decode(0x0D, "410DFF>"))
    }

    @Test
    fun distinguishesUnsupportedPidFromInvalidResponse() {
        val unsupported = Obd2Protocol.telemetry(
            speedResponse = "NO DATA>",
            speedPidSupported = false,
            now = 1_234L
        )
        assertNull(unsupported.vehicleSpeedKmh)
        assertEquals(Obd2SpeedIssue.PID_UNSUPPORTED, unsupported.speedIssue)

        val noResponse = Obd2Protocol.telemetry(
            speedResponse = "NO DATA>",
            speedPidSupported = null,
            now = 1_235L
        )
        assertNull(noResponse.vehicleSpeedKmh)
        assertEquals(Obd2SpeedIssue.NO_VALID_RESPONSE, noResponse.speedIssue)
    }

    @Test
    fun telemetryContainsOnlyPositionRelevantSpeed() {
        val telemetry = Obd2Protocol.telemetry(
            speedResponse = "410D58",
            speedPidSupported = true,
            now = 1_234L
        )
        assertEquals(88, telemetry.vehicleSpeedKmh)
        assertEquals(1_234L, telemetry.updatedAt)
        assertNull(telemetry.speedIssue)
    }
}
