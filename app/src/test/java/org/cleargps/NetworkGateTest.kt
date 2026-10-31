package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkGateTest {
    @Test
    fun allowsOnlyTheThreeReadOnlyNetworkOperations() {
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
