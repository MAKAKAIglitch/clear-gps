package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2ProtocolTest {
    @Test
    fun requestsOnlyCapabilityBitmapsAndVehicleSpeed() {
        assertEquals("0100", Obd2Protocol.requestFor(0x00))
        assertEquals("0120", Obd2Protocol.requestFor(0x20))
        assertEquals("0140", Obd2Protocol.requestFor(0x40))
        assertEquals("0160", Obd2Protocol.requestFor(0x60))
        assertEquals("0180", Obd2Protocol.requestFor(0x80))
        assertEquals("01A0", Obd2Protocol.requestFor(0xA0))
        assertEquals("01C0", Obd2Protocol.requestFor(0xC0))
        assertEquals("010D", Obd2Protocol.requestFor(0x0D))
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x04) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x05) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x0C) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0xE0) }
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
    fun parsesLaterBitmapRelativeToItsRequestedBasePid() {
        // PID 21 is supported and the final bit advertises the next bitmap (PID 40).
        val supported = Obd2Protocol.supportedPids("7E806412080000001>", basePid = 0x20)
        assertTrue(supported!!.contains(0x21))
        assertTrue(supported.contains(0x40))
        assertFalse(supported.contains(0x22))
    }

    @Test
    fun doesNotInventContinuationWhenTheLastBitmapBitIsClear() {
        val supported = Obd2Protocol.supportedPids("412080000000>", basePid = 0x20)
        assertTrue(supported!!.contains(0x21))
        assertFalse(supported.contains(0x40))
    }

    @Test
    fun parsesHighestAllowedBitmapWithoutAllowingAnotherPageRequest() {
        val supported = Obd2Protocol.supportedPids("41C000000001>", basePid = 0xC0)
        assertTrue(supported!!.contains(0xE0)) // Continuation is visible to the walker.
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0xE0) }
    }

    @Test
    fun rejectsUnknownBitmapBasesAndMalformedContinuationPages() {
        assertNull(Obd2Protocol.supportedPids("41E000000000>", basePid = 0xE0))
        assertNull(Obd2Protocol.supportedPids("NO DATA>", basePid = 0x20))
        assertNull(Obd2Protocol.supportedPids("412080000000", basePid = 0x20))
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
