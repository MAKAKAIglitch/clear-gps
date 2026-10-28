package org.cleargps

import org.cleargps.core.ZonePoint
import org.cleargps.fusion.PositionMeasurement
import org.cleargps.fusion.PositionMeasurementSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioFusionZonePolicyTest {
    @Test
    fun rejectsRadioFixInsideRiskZone() {
        assertFalse(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 52.0005, longitude = 5.0005, accuracy = 20.0),
            listOf(riskZone())
        ))
    }

    @Test
    fun rejectsAccuracyCircleThatOverlapsRiskZoneBoundary() {
        assertFalse(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 52.0005, longitude = 5.0015, accuracy = 100.0),
            listOf(riskZone())
        ))
    }

    @Test
    fun rejectsFixTooCoarseToRuleOutZoneIntersection() {
        assertFalse(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 52.01, longitude = 5.01, accuracy = 501.0),
            listOf(riskZone())
        ))
    }

    @Test
    fun allowsReliableFixOutsideZonesAndDoesNotInventZonesWhenNoneAreConfigured() {
        assertTrue(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 52.01, longitude = 5.01, accuracy = 100.0),
            listOf(riskZone())
        ))
        assertTrue(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 52.01, longitude = 5.01, accuracy = 5_000.0),
            emptyList()
        ))
    }

    @Test
    fun rejectsInvalidCoordinatesOrUncertainty() {
        assertFalse(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 91.0, longitude = 5.0, accuracy = 20.0),
            emptyList()
        ))
        assertFalse(RadioFusionZonePolicy.allowsFusion(
            measurement(latitude = 52.0, longitude = 5.0, accuracy = Double.NaN),
            emptyList()
        ))
    }

    private fun riskZone() = listOf(
        ZonePoint(52.0, 5.0),
        ZonePoint(52.0, 5.001),
        ZonePoint(52.001, 5.001),
        ZonePoint(52.001, 5.0)
    )

    private fun measurement(
        latitude: Double,
        longitude: Double,
        accuracy: Double
    ) = PositionMeasurement(
        source = PositionMeasurementSource.RADIO,
        latitude = latitude,
        longitude = longitude,
        horizontalUncertaintyMeters = accuracy,
        elapsedRealtimeNanos = 10_000_000_000L
    )
}
