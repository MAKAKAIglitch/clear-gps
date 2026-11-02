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
class OpenStreetMapCellSiteProtocolTest {
    @Test
    fun queryIsBoundedAndRequestsOnlyCommunicationStructures() {
        val query = OpenStreetMapCellSiteProtocol.buildQuery(51.50012345, -0.12098765, 750)

        assertTrue(query.contains("[out:json][timeout:20]"))
        assertTrue(query.contains("around:750,51.500123,-0.120988"))
        assertTrue(query.contains("[\"communication:mobile_phone\"=\"yes\"]"))
        assertTrue(query.contains("[\"tower:type\"=\"communication\"]"))
        assertTrue(query.contains("[\"man_made\"=\"communications_tower\"]"))
        assertTrue(query.contains("[\"telecom\"=\"antenna\"]"))
        assertTrue(query.contains("out center tags"))
    }

    @Test
    fun queryRejectsInvalidCoordinatesAndUnboundedRadii() {
        assertFalse(runCatching {
            OpenStreetMapCellSiteProtocol.buildQuery(91.0, -0.12, 500)
        }.isSuccess)
        assertFalse(runCatching {
            OpenStreetMapCellSiteProtocol.buildQuery(51.5, 181.0, 500)
        }.isSuccess)
        assertFalse(runCatching {
            OpenStreetMapCellSiteProtocol.buildQuery(51.5, -0.12, 50_000)
        }.isSuccess)
    }

    @Test
    fun parsesMobileMastAndCommunicationsTowerButFiltersBareMast() {
        val body = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 101,
                  "lat": 51.5,
                  "lon": -0.12,
                  "tags": {
                    "man_made": "mast",
                    "tower:type": "communication",
                    "communication:mobile_phone": "yes",
                    "operator": "Example Operator",
                    "height": "32 m"
                  }
                },
                {
                  "type": "way",
                  "id": 202,
                  "center": {"lat": 51.5001, "lon": -0.1201},
                  "tags": {
                    "man_made": "communications_tower",
                    "name": "Broadcast tower"
                  }
                },
                {
                  "type": "node",
                  "id": 303,
                  "lat": 51.501,
                  "lon": -0.121,
                  "tags": {"man_made": "mast"}
                }
              ]
            }
        """.trimIndent()

        val response = OpenStreetMapCellSiteProtocol.parseResponse(200, body)

        assertEquals(OpenStreetMapCellSiteLookupStatus.FOUND, response.status)
        assertEquals(2, response.objects.size)
        val mobileMast = response.objects.first { it.osmId == 101L }
        assertEquals(OpenStreetMapElementType.NODE, mobileMast.elementType)
        assertEquals(true, mobileMast.mobilePhoneCapable)
        assertEquals("Example Operator", mobileMast.operator)
        assertEquals(32.0, mobileMast.heightMeters!!, 0.0)
        assertEquals(30.0, mobileMast.coordinateUncertaintyMeters, 0.0)

        val communicationsTower = response.objects.first { it.osmId == 202L }
        assertEquals(OpenStreetMapElementType.WAY, communicationsTower.elementType)
        assertEquals(100.0, communicationsTower.coordinateUncertaintyMeters, 0.0)
        assertNull(communicationsTower.mobilePhoneCapable)
    }

    @Test
    fun relationCenterIsUsedAndDuplicateOsmElementsAreDeduplicated() {
        val body = """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": 401,
                  "center": {"lat": 52.0, "lon": 13.0},
                  "tags": {"telecom": "antenna"}
                },
                {
                  "type": "relation",
                  "id": 401,
                  "center": {"lat": 52.0, "lon": 13.0},
                  "tags": {"telecom": "antenna"}
                }
              ]
            }
        """.trimIndent()

        val response = OpenStreetMapCellSiteProtocol.parseResponse(200, body)

        assertEquals(1, response.objects.size)
        assertEquals(OpenStreetMapElementType.RELATION, response.objects.single().elementType)
        assertEquals(52.0, response.objects.single().latitude, 0.0)
    }

    @Test
    fun rateLimitServerErrorsAndMalformedJsonAreClassified() {
        assertEquals(
            OpenStreetMapCellSiteLookupStatus.RATE_LIMITED,
            OpenStreetMapCellSiteProtocol.parseResponse(429, "").status
        )
        assertEquals(
            OpenStreetMapCellSiteLookupStatus.RATE_LIMITED,
            OpenStreetMapCellSiteProtocol.parseResponse(406, "").status
        )
        assertEquals(
            OpenStreetMapCellSiteLookupStatus.TEMPORARY_ERROR,
            OpenStreetMapCellSiteProtocol.parseResponse(503, "").status
        )
        assertEquals(
            OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE,
            OpenStreetMapCellSiteProtocol.parseResponse(200, "{bad json").status
        )
    }
}
