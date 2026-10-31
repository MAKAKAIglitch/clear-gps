package org.cleargps

import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofZonePolicyTest {
    private val airport = AirportRecord(
        ident = "LSGG",
        icao = "LSGG",
        iata = "GVA",
        name = "Geneva Airport",
        latitude = 46.2381,
        longitude = 6.1089
    )

    @Test
    fun pointInsideManualZoneBlocksRadioData() {
        val assessment = SpoofZonePolicy.assess(
            point = ZonePoint(46.0, 6.0),
            accuracyMeters = 10.0,
            enabledZones = listOf(zone()),
            airports = emptyList()
        )
        assertTrue(assessment.insideManualZone)
        assertTrue(assessment.blocksRadioData)
        assertEquals(0, assessment.airportRiskScore)
    }

    @Test
    fun airportRadiusBlocksRadioButProducesOnlySoftRiskScore() {
        val assessment = SpoofZonePolicy.assess(
            point = ZonePoint(46.2381, 6.1089),
            accuracyMeters = 25.0,
            enabledZones = emptyList(),
            airports = listOf(airport)
        )
        assertFalse(assessment.insideManualZone)
        assertTrue(assessment.nearAirport)
        assertTrue(assessment.blocksRadioData)
        assertEquals(10, assessment.airportRiskScore)
        assertEquals("LSGG", assessment.nearestAirportCode)
        assertTrue((assessment.nearestAirportDistanceMeters ?: Double.MAX_VALUE) < 1.0)
    }

    @Test
    fun airportRadiusCanBeChangedAndCodesCanBeExcluded() {
        val insideSmallRadius = SpoofZonePolicy.assess(
            point = ZonePoint(46.2381, 6.1089 + 0.01),
            accuracyMeters = null,
            enabledZones = emptyList(),
            airports = listOf(airport),
            airportRadiusMeters = 500
        )
        assertFalse(insideSmallRadius.nearAirport)

        val excluded = SpoofZonePolicy.assess(
            point = ZonePoint(46.2381, 6.1089),
            accuracyMeters = null,
            enabledZones = emptyList(),
            airports = listOf(airport),
            airportExclusions = setOf("GVA")
        )
        assertFalse(excluded.nearAirport)
    }

    @Test
    fun missingAirportCatalogueFailsClosedWhenAvoidanceIsEnabled() {
        val assessment = SpoofZonePolicy.assess(
            point = ZonePoint(46.2381, 6.1089),
            accuracyMeters = 10.0,
            enabledZones = emptyList(),
            airports = emptyList(),
            airportAvoidanceEnabled = true
        )
        assertFalse(assessment.airportCheckAvailable)
        assertTrue(assessment.blocksRadioData)
        assertFalse(assessment.nearAirport)
        assertEquals(0, assessment.airportRiskScore)
    }

    @Test
    fun disablingAirportAvoidanceDoesNotRequireAnAirportCatalogue() {
        val assessment = SpoofZonePolicy.assess(
            point = ZonePoint(46.2381, 6.1089),
            accuracyMeters = 10.0,
            enabledZones = emptyList(),
            airports = emptyList(),
            airportAvoidanceEnabled = false
        )
        assertTrue(assessment.airportCheckAvailable)
        assertFalse(assessment.blocksRadioData)
    }

    @Test
    fun inaccurateFixCannotExpandAirportRadiusWithoutBound() {
        val assessment = SpoofZonePolicy.assess(
            point = ZonePoint(46.2381, 6.1089 + 0.03),
            accuracyMeters = 10_000.0,
            enabledZones = emptyList(),
            airports = listOf(airport)
        )
        assertFalse(assessment.nearAirport)
    }

    private fun zone() = SavedRiskZone(
        name = "Test spoofing zone",
        points = listOf(
            ZonePoint(45.9, 5.9), ZonePoint(45.9, 6.1),
            ZonePoint(46.1, 6.1), ZonePoint(46.1, 5.9)
        )
    )
}
