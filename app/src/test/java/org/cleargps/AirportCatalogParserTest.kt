package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportCatalogParserTest {
    @Test
    fun parsesAirportCodesAndQuotedNamesAndSkipsClosedFacilities() {
        val csv = listOf(
            "id,ident,type,name,latitude_deg,longitude_deg,gps_code,iata_code",
            "1,LSGG,large_airport,\"Geneva, International\",46.2381,6.1089,LSGG,GVA",
            "2,LFLU,small_airport,\"Valence \"\"Chabeuil\"\"\",44.9216,4.9699,LFLU,",
            "3,OLD1,closed,\"Closed field\",46.0,6.0,OLD1,",
            "4,HEL1,heliport,\"Medical helipad\",46.1,6.1,HEL1,"
        ).joinToString("\n")

        val airports = AirportCatalogStore.parseAirports(csv)
        assertEquals(3, airports.size)
        assertEquals("Geneva, International", airports.first().name)
        assertEquals("LSGG", airports.first().icao)
        assertEquals("GVA", airports.first().iata)
        assertTrue(airports.any { it.name == "Valence \"Chabeuil\"" })
        assertTrue(airports.any { it.ident == "HEL1" })
    }
}
