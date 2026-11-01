package org.cleargps

import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpoofZoneExportCodecTest {
    private fun zones() = listOf(
        SavedRiskZone(
            id = "zone-one",
            name = "Airport edge",
            points = listOf(
                ZonePoint(52.0, 5.0),
                ZonePoint(51.99, 5.03),
                ZonePoint(52.01, 5.02)
            ),
            enabled = false,
            createdAt = 1_700_000_000_000L
        ),
        SavedRiskZone(
            id = "zone-two",
            name = "Tunnel",
            points = listOf(
                ZonePoint(48.1, 11.5),
                ZonePoint(48.2, 11.6),
                ZonePoint(48.2, 11.5)
            )
        )
    )

    @Test
    fun jsonExportRoundTripsZonesAndState() {
        val original = zones()
        assertEquals(original, SpoofZoneExportCodec.decode(SpoofZoneExportCodec.encodeJson(original)))
    }

    @Test
    fun geoJsonExportRoundTripsPolygonsAndMetadata() {
        val original = zones()
        assertEquals(original, SpoofZoneExportCodec.decode(SpoofZoneExportCodec.encodeGeoJson(original)))
    }

    @Test
    fun rejectsInvalidCoordinatesAndDuplicateIds() {
        val invalid = zones().first().copy(points = listOf(
            ZonePoint(91.0, 5.0), ZonePoint(52.0, 5.1), ZonePoint(52.1, 5.2)
        ))
        assertTrue(runCatching { SpoofZoneExportCodec.encodeJson(listOf(invalid)) }.isFailure)
        val duplicate = zones().first().let { listOf(it, it.copy(name = "Second copy")) }
        assertTrue(runCatching { SpoofZoneExportCodec.encodeGeoJson(duplicate) }.isFailure)
    }

    @Test
    fun rejectsUnsupportedVersionsAndOversizedInput() {
        val encoded = SpoofZoneExportCodec.encodeJson(zones())
        assertTrue(runCatching {
            SpoofZoneExportCodec.decode(encoded.replace("\"version\":1", "\"version\":99"))
        }.isFailure)
        assertTrue(runCatching {
            SpoofZoneExportCodec.decode("x".repeat(SpoofZoneExportCodec.MAX_BYTES + 1))
        }.isFailure)
    }

    @Test
    fun geoJsonRejectsUnsupportedGeometry() {
        val encoded = SpoofZoneExportCodec.encodeGeoJson(zones())
            .replace("\"type\":\"Polygon\"", "\"type\":\"Point\"")
        assertTrue(runCatching { SpoofZoneExportCodec.decode(encoded) }.isFailure)
    }
}
