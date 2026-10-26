package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Obd2ProtocolTest {
    @Test
    fun requestsOnlyCapabilityBitmapsAndPlannedTelemetryPids() {
        assertEquals("0100", Obd2Protocol.requestFor(0x00))
        assertEquals("0120", Obd2Protocol.requestFor(0x20))
        assertEquals("0140", Obd2Protocol.requestFor(0x40))
        assertEquals("0160", Obd2Protocol.requestFor(0x60))
        assertEquals("0180", Obd2Protocol.requestFor(0x80))
        assertEquals("01A0", Obd2Protocol.requestFor(0xA0))
        assertEquals("01C0", Obd2Protocol.requestFor(0xC0))
        for (pid in Obd2Protocol.telemetryPids) {
            assertEquals("01" + pid.toString(16).uppercase().padStart(2, '0'), Obd2Protocol.requestFor(pid))
        }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x09) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0x10) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.requestFor(0xE0) }
        assertThrows(IllegalArgumentException::class.java) { Obd2Protocol.decode(0x09, "4902") }
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
        assertNull(Obd2Protocol.supportedPids("4120800000", basePid = 0x20))
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
    fun decodesEveryPlannedReadOnlyTelemetryPid() {
        assertEquals(50, Obd2Protocol.decode(0x04, "410480>"))
        assertEquals(50, Obd2Protocol.decode(0x05, "41055A>"))
        assertEquals(60, Obd2Protocol.decode(0x0B, "410B3C>"))
        assertEquals(1_726, Obd2Protocol.decode(0x0C, "410C1AF8>"))
        assertEquals(88, Obd2Protocol.decode(0x0D, "410D58>"))
        assertEquals(50, Obd2Protocol.decode(0x11, "411180>"))
        assertEquals(100, Obd2Protocol.decode(0x33, "413364>"))
        assertEquals(0, Obd2Protocol.decode(0x04, "410400>"))
        assertEquals(-40, Obd2Protocol.decode(0x05, "410500>"))
        assertEquals(215, Obd2Protocol.decode(0x05, "4105FF>"))
        assertEquals(16_383, Obd2Protocol.decode(0x0C, "410CFFFF>"))
    }

    @Test
    fun rejectsShortResponsesAndOversizedParserInputs() {
        assertNull(Obd2Protocol.decode(0x0C, "410C1A>"))
        assertNull(Obd2Protocol.decode(0x0D, "410D"))
        val oversized = "410D58" + "A".repeat(3_000)
        assertNull(Obd2Protocol.decode(0x0D, oversized))
        assertNull(Obd2Protocol.supportedPids("4100" + "F".repeat(3_000)))
    }

    @Test
    fun batchTelemetryKeepsOnlyValidValuesAndReportsPerPidIssues() {
        val telemetry = Obd2Protocol.telemetry(
            responses = mapOf(
                0x0D to "410D58>",
                0x0C to "410C1AF8>",
                0x05 to "41055A>"
            ),
            pidIssues = mapOf(
                0x04 to Obd2PidIssue.PID_UNSUPPORTED,
                0x0B to Obd2PidIssue.NO_VALID_RESPONSE,
                0x11 to Obd2PidIssue.PID_DISABLED_FOR_SESSION
            ),
            now = 10_000L
        )

        assertEquals(88, telemetry.vehicleSpeedKmh)
        assertEquals(1_726, telemetry.engineRpm)
        assertEquals(50, telemetry.coolantTemperatureCelsius)
        assertEquals(10_000L, telemetry.updatedAt)
        assertNull(telemetry.speedIssue)
        assertEquals(Obd2PidIssue.PID_UNSUPPORTED, telemetry.pidIssues[0x04])
        assertEquals(Obd2PidIssue.NO_VALID_RESPONSE, telemetry.pidIssues[0x0B])
        assertEquals(Obd2PidIssue.PID_DISABLED_FOR_SESSION, telemetry.pidIssues[0x11])
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
    fun seededFuzzInputsNeverCrashParsersOrReturnOutOfRangeValues() {
        val random = Random(0x0B_D2_2026L)
        val alphabet = "0123456789abcdefABCDEF >\\r\\n:-?NO DATAUNAVAILABLE"

        repeat(5_000) {
            val response = buildString {
                repeat(random.nextInt(160)) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
            for (pid in Obd2Protocol.telemetryPids) {
                val value = Obd2Protocol.decode(pid, response) ?: continue
                when (pid) {
                    0x04, 0x11 -> assertTrue("Out-of-range percentage for PID $pid: $value", value in 0..100)
                    0x05 -> assertTrue("Out-of-range temperature: $value", value in -40..215)
                    0x0B, 0x33 -> assertTrue("Out-of-range pressure for PID $pid: $value", value in 0..255)
                    0x0C -> assertTrue("Out-of-range RPM: $value", value in 0..16_383)
                    0x0D -> assertTrue("Out-of-range speed: $value", value in 0..250)
                }
            }

            for (basePid in Obd2Protocol.supportBitmapBases) {
                val supported = Obd2Protocol.supportedPids(response, basePid)
                if (supported != null) {
                    assertTrue(supported.all { it in (basePid + 1)..(basePid + 32) })
                }
            }
        }
    }

    @Test
    fun disabledForSessionTelemetryNeverExposesMalformedResponseAsSpeed() {
        val telemetry = Obd2Protocol.telemetry(
            speedResponse = "410D58",
            speedPidSupported = true,
            now = 2_026L,
            pidDisabledForSession = true
        )
        assertNull(telemetry.vehicleSpeedKmh)
        assertEquals(Obd2SpeedIssue.PID_DISABLED_FOR_SESSION, telemetry.speedIssue)
        assertEquals(2_026L, telemetry.updatedAt)
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
