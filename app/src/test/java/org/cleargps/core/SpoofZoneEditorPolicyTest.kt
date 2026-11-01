package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SpoofZoneEditorPolicyTest {
    @Test
    fun circleHasRequestedGeodesicRadius() {
        val center = ZonePoint(52.0, 5.0)
        val radius = 850.0
        val points = SpoofZoneEditorPolicy.circlePoints(center.latitude, center.longitude, radius)

        assertEquals(SpoofZoneEditorPolicy.DEFAULT_VERTEX_COUNT, points.size)
        points.forEach { point ->
            assertEquals(radius, distanceMeters(center, point), 0.2)
        }
    }

    @Test
    fun circleAndCenterRemainValidAcrossAntimeridian() {
        val center = ZonePoint(12.0, 179.999)
        val points = SpoofZoneEditorPolicy.circlePoints(center.latitude, center.longitude, 1_500.0)
        assertTrue(points.any { it.longitude < -179.0 })
        assertTrue(points.any { it.longitude > 179.0 })
        assertTrue(points.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
    }

    @Test
    fun polygonCanBeOpenedAndSavedAsEditableCircle() {
        val original = SavedRiskZone(
            id = "zone-a",
            name = "Test",
            points = SpoofZoneEditorPolicy.circlePoints(50.0, 8.0, 300.0),
            enabled = false,
            createdAt = 123L
        )
        val parameters = SpoofZoneEditorPolicy.parameters(original)

        assertTrue(parameters != null)
        assertEquals(50.0, parameters!!.latitude, 0.00001)
        assertEquals(8.0, parameters.longitude, 0.00001)
        assertEquals(300.0, parameters.radiusMeters, 0.2)
        assertEquals(false, original.enabled)
        assertEquals(123L, original.createdAt)
    }

    @Test
    fun invalidCenterAndRadiusAreRejected() {
        assertTrue(runCatching { SpoofZoneEditorPolicy.circlePoints(91.0, 0.0, 100.0) }.isFailure)
        assertTrue(runCatching { SpoofZoneEditorPolicy.circlePoints(0.0, 0.0, 1.0) }.isFailure)
        assertTrue(runCatching { SpoofZoneEditorPolicy.circlePoints(0.0, 0.0, Double.NaN) }.isFailure)
    }

    private fun distanceMeters(first: ZonePoint, second: ZonePoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = (sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)).coerceIn(0.0, 1.0)
        return 2.0 * 6_371_008.8 * kotlin.math.asin(sqrt(a))
    }
}
