package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenCellIdProtocolTest {
    private val cell = OpenCellIdCellKey("LTE", 310, 260, 404, 12345L)

    @Test fun parsesCellCoordinatesAndMetadata() {
        val parsed = OpenCellIdProtocol.parseResponse(
            200,
            """{"mcc":310,"mnc":260,"lac":404,"cellid":12345,"radio":"LTE","lat":51.5012,"lon":-0.1419,"range":750,"samples":12,"changeable":1}""",
            cell, 1_000L
        )
        assertEquals(OpenCellIdLookupStatus.FOUND, parsed.status)
        assertNotNull(parsed.record)
        assertEquals(51.5012, parsed.record!!.latitude!!, 0.000001)
        assertEquals(-0.1419, parsed.record.longitude!!, 0.000001)
        assertEquals(750, parsed.record.rangeMeters)
        assertEquals(12, parsed.record.samples)
        assertEquals("found", parsed.record.status)
    }

    @Test fun http200WithCellNotFoundCodeIsNotASuccess() {
        val parsed = OpenCellIdProtocol.parseResponse(
            200, """{"error":"Cell not found","code":1}""", cell, 2_000L
        )
        assertEquals(OpenCellIdLookupStatus.NOT_FOUND, parsed.status)
        assertEquals("not_found", parsed.record!!.status)
        assertNull(parsed.record.latitude)
        assertNull(parsed.record.longitude)
    }

    @Test fun rejectsMismatchedCellIdentifiers() {
        val parsed = OpenCellIdProtocol.parseResponse(
            200, """{"mcc":310,"mnc":260,"lac":405,"cellid":12345,"lat":51.5,"lon":-0.1}""",
            cell, 3_000L
        )
        assertEquals(OpenCellIdLookupStatus.INVALID_RESPONSE, parsed.status)
        assertNull(parsed.record)
    }

    @Test fun rejectsCoordinatesOutsideWgs84() {
        val parsed = OpenCellIdProtocol.parseResponse(
            200, """{"mcc":310,"mnc":260,"lac":404,"cellid":12345,"lat":91,"lon":0}""",
            cell, 4_000L
        )
        assertEquals(OpenCellIdLookupStatus.INVALID_RESPONSE, parsed.status)
        assertNull(parsed.record)
    }

    @Test fun mapsOnlyKnownAndroidCellRadioTypes() {
        assertEquals(
            OpenCellIdCellKey("UMTS", 234, 15, 900, 88001L),
            OpenCellIdCellKey.fromRadioSignal(RadioSignal("cell", "wcdma:234:15:900:88001", -87))
        )
        assertEquals(
            OpenCellIdCellKey("NR", 234, 15, 900, 9_999_999_999L),
            OpenCellIdCellKey.fromRadioSignal(RadioSignal("cell", "nr:234:15:900:9999999999", -87))
        )
        assertNull(OpenCellIdCellKey.fromRadioSignal(RadioSignal("cell", "cdma:234:15:900:1", -87)))
        assertNull(OpenCellIdCellKey.fromRadioSignal(RadioSignal("wifi", "00:11:22:33:44:55", -40)))
    }
}
