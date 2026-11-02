package org.cleargps

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkGateTest {
    private fun overpassUrl(query: String): String =
        "https://overpass-api.de/api/interpreter?data=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    private val validOverpassQuery =
        "[out:json][timeout:20];(nwr(around:750,51.500000,-0.120000)[\"tower:type\"=\"communication\"];);out center tags;"

    @Test
    fun allowsOnlyTheReadOnlyNetworkOperations() {
        val beacon = NetworkGate.validateRequest(
            "https://api.beacondb.net/v1/geolocate",
            "POST",
            NetworkGate.Operation.BEACONDB_LOOKUP,
            consentGranted = true
        )
        assertEquals("api.beacondb.net", beacon.host)

        val cell = NetworkGate.validateRequest(
            "https://opencellid.org/cell/get?key=secret&mcc=310&mnc=260&lac=404&cellid=12345&radio=LTE&format=json",
            "GET",
            NetworkGate.Operation.OPENCELLID_LOOKUP,
            consentGranted = true
        )
        assertEquals("/cell/get", cell.path)

        val tile = NetworkGate.validateRequest(
            "https://tiles.openfreemap.org/styles/liberty",
            "GET",
            NetworkGate.Operation.MAP_RESOURCE,
            mapOnline = true
        )
        assertEquals("tiles.openfreemap.org", tile.host)

        val airport = NetworkGate.validateRequest(
            "https://davidmegginson.github.io/ourairports-data/airports.csv",
            "GET",
            NetworkGate.Operation.AIRPORT_CATALOG_READ,
            consentGranted = true
        )
        assertEquals("/ourairports-data/airports.csv", airport.path)

        val osm = NetworkGate.validateRequest(
            overpassUrl(validOverpassQuery),
            "GET",
            NetworkGate.Operation.OPENSTREETMAP_CELL_SITE_LOOKUP,
            consentGranted = true
        )
        assertEquals("overpass-api.de", osm.host)
        assertEquals("/api/interpreter", osm.path)
    }

    @Test
    fun openStreetMapRequiresSeparateExplicitConsent() {
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                overpassUrl(validOverpassQuery),
                "GET",
                NetworkGate.Operation.OPENSTREETMAP_CELL_SITE_LOOKUP,
                consentGranted = false
            )
        }
    }

    @Test
    fun openStreetMapAllowsOnlyOneReadOnlyDataQueryAtTheExactEndpoint() {
        val invalidRequests = listOf(
            "http://overpass-api.de/api/interpreter?data=${URLEncoder.encode(validOverpassQuery, StandardCharsets.UTF_8.name())}" to "GET",
            "https://other.overpass-api.de/api/interpreter?data=${URLEncoder.encode(validOverpassQuery, StandardCharsets.UTF_8.name())}" to "GET",
            "https://overpass-api.de/api/interpreter?data=x&extra=1" to "GET",
            overpassUrl("[out:json];node(1);out;") to "GET",
            overpassUrl("[out:json];nwr(around:100,51.5,-0.12);delete;out;") to "GET",
            overpassUrl(validOverpassQuery) to "POST",
            "https://overpass-api.de/api/interpreter" to "GET"
        )
        invalidRequests.forEach { (url, method) ->
            assertThrows(SecurityException::class.java) {
                NetworkGate.validateRequest(
                    url,
                    method,
                    NetworkGate.Operation.OPENSTREETMAP_CELL_SITE_LOOKUP,
                    consentGranted = true
                )
            }
        }
    }

    @Test
    fun airportCatalogueIsReadOnlyAndRequiresItsOwnConsent() {
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://davidmegginson.github.io/ourairports-data/airports.csv",
                "GET",
                NetworkGate.Operation.AIRPORT_CATALOG_READ,
                consentGranted = false
            )
        }
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://davidmegginson.github.io/ourairports-data/airports.csv",
                "POST",
                NetworkGate.Operation.AIRPORT_CATALOG_READ,
                consentGranted = true
            )
        }
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://davidmegginson.github.io/ourairports-data/airport-frequencies.csv",
                "GET",
                NetworkGate.Operation.AIRPORT_CATALOG_READ,
                consentGranted = true
            )
        }
    }

    @Test
    fun beaconDbPostRequiresExplicitConsentAndExactReadOnlyEndpoint() {
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://api.beacondb.net/v1/geolocate",
                "POST",
                NetworkGate.Operation.BEACONDB_LOOKUP,
                consentGranted = false
            )
        }
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://api.beacondb.net/v2/geosubmit",
                "POST",
                NetworkGate.Operation.BEACONDB_LOOKUP,
                consentGranted = true
            )
        }
    }

    @Test
    fun rejectsOtherMethodsAndWriteEndpoints() {
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://opencellid.org/cell/get?key=secret&mcc=310&mnc=260&lac=404&cellid=12345&radio=LTE&format=json",
                "POST",
                NetworkGate.Operation.OPENCELLID_LOOKUP,
                consentGranted = true
            )
        }
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://opencellid.org/measure/add",
                "GET",
                NetworkGate.Operation.OPENCELLID_LOOKUP,
                consentGranted = true
            )
        }
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://tiles.openfreemap.org/styles/liberty",
                "PUT",
                NetworkGate.Operation.MAP_RESOURCE,
                mapOnline = true
            )
        }
    }

    @Test
    fun rejectsCleartextUnknownHostsUnapprovedPortsAndUserInfo() {
        val invalidUrls = listOf(
            "http://api.beacondb.net/v1/geolocate",
            "https://attacker.example/v1/geolocate",
            "https://api.beacondb.net:8443/v1/geolocate",
            "https://user@api.beacondb.net/v1/geolocate"
        )
        invalidUrls.forEach { url ->
            assertThrows(SecurityException::class.java) {
                NetworkGate.validateRequest(
                    url,
                    "POST",
                    NetworkGate.Operation.BEACONDB_LOOKUP,
                    consentGranted = true
                )
            }
        }
    }

    @Test
    fun mapRequestsAreBlockedWhenOfflineOrRedirectedOutsideTheAllowlist() {
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://tiles.openfreemap.org/data/v3/1/1/1.pbf",
                "GET",
                NetworkGate.Operation.MAP_RESOURCE,
                mapOnline = false
            )
        }
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://other.openfreemap.org/data/v3/1/1/1.pbf",
                "GET",
                NetworkGate.Operation.MAP_RESOURCE,
                mapOnline = true
            )
        }
    }

    @Test
    fun openCellIdAcceptsOnlyItsExpectedQueryFields() {
        assertThrows(SecurityException::class.java) {
            NetworkGate.validateRequest(
                "https://opencellid.org/cell/get?key=secret&mcc=310&mnc=260&lac=404&cellid=12345&radio=LTE&format=json&extra=1",
                "GET",
                NetworkGate.Operation.OPENCELLID_LOOKUP,
                consentGranted = true
            )
        }
    }
}
