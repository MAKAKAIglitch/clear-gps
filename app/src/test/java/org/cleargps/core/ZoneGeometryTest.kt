package org.cleargps.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneGeometryTest {
    private val square = listOf(
        ZonePoint(10.0, 10.0), ZonePoint(10.0, 11.0),
        ZonePoint(11.0, 11.0), ZonePoint(11.0, 10.0)
    )

    @Test fun insidePointIsDetected() =
        assertTrue(ZoneGeometry.contains(ZonePoint(10.5, 10.5), square))

    @Test fun outsidePointIsRejected() =
        assertFalse(ZoneGeometry.contains(ZonePoint(12.0, 10.5), square))

    @Test fun boundaryIsIncluded() =
        assertTrue(ZoneGeometry.contains(ZonePoint(10.0, 10.5), square))

    @Test fun shortPolygonIsRejected() =
        assertFalse(ZoneGeometry.contains(ZonePoint(10.5, 10.5), square.take(2)))

    @Test fun invalidCoordinatesAreRejected() =
        assertFalse(ZoneGeometry.contains(ZonePoint(91.0, 0.0), square))

    @Test fun antimeridianPolygonRemainsSmallAndContainsPointsOnBothSides() {
        val antimeridianZone = listOf(
            ZonePoint(-1.0, 179.5),
            ZonePoint(-1.0, -179.5),
            ZonePoint(1.0, -179.5),
            ZonePoint(1.0, 179.5)
        )

        assertTrue(ZoneGeometry.contains(ZonePoint(0.0, 179.8), antimeridianZone))
        assertTrue(ZoneGeometry.contains(ZonePoint(0.0, -179.8), antimeridianZone))
        assertFalse(ZoneGeometry.contains(ZonePoint(0.0, 170.0), antimeridianZone))
    }

    @Test fun accuracyCircleCanIntersectZoneWithoutItsCenterBeingInside() {
        val nearbyZone = listOf(
            ZonePoint(0.0009, -0.001),
            ZonePoint(0.0009, 0.001),
            ZonePoint(0.002, 0.001),
            ZonePoint(0.002, -0.001)
        )
        val fix = ZonePoint(0.0, 0.0)

        assertFalse(ZoneGeometry.contains(fix, nearbyZone))
        assertTrue(ZoneGeometry.contains(fix, nearbyZone, accuracyMeters = 150.0))
        assertFalse(ZoneGeometry.contains(fix, nearbyZone, accuracyMeters = 50.0))
    }

    @Test fun unusableOrExcessiveAccuracyDoesNotExpandZone() {
        val nearbyZone = listOf(
            ZonePoint(0.0009, -0.001),
            ZonePoint(0.0009, 0.001),
            ZonePoint(0.002, 0.001),
            ZonePoint(0.002, -0.001)
        )
        val fix = ZonePoint(0.0, 0.0)

        assertFalse(ZoneGeometry.contains(fix, nearbyZone, accuracyMeters = -1.0))
        assertFalse(ZoneGeometry.contains(fix, nearbyZone, accuracyMeters = Double.NaN))
        assertFalse(ZoneGeometry.contains(fix, nearbyZone, accuracyMeters = 1_000.0))
    }

    @Test fun selfIntersectingPolygonUsesEvenOddRuleAndIncludesCrossing() {
        val bowTie = listOf(
            ZonePoint(0.0, 0.0),
            ZonePoint(2.0, 2.0),
            ZonePoint(0.0, 2.0),
            ZonePoint(2.0, 0.0)
        )

        assertTrue(ZoneGeometry.contains(ZonePoint(1.0, 1.0), bowTie)) // Crossing point is on the boundary.
        assertTrue(ZoneGeometry.contains(ZonePoint(0.25, 0.1), bowTie)) // Lower-left lobe.
        assertTrue(ZoneGeometry.contains(ZonePoint(1.75, 1.9), bowTie)) // Upper-right lobe.
        assertFalse(ZoneGeometry.contains(ZonePoint(0.25, 0.75), bowTie)) // Outside, between the lobes.
    }

    @Test fun nestedPolygonsCanBeCheckedIndependently() {
        val outer = listOf(
            ZonePoint(0.0, 0.0), ZonePoint(0.0, 10.0),
            ZonePoint(10.0, 10.0), ZonePoint(10.0, 0.0)
        )
        val inner = listOf(
            ZonePoint(2.0, 2.0), ZonePoint(2.0, 4.0),
            ZonePoint(4.0, 4.0), ZonePoint(4.0, 2.0)
        )

        assertTrue(ZoneGeometry.contains(ZonePoint(3.0, 3.0), outer))
        assertTrue(ZoneGeometry.contains(ZonePoint(3.0, 3.0), inner))
        assertTrue(ZoneGeometry.contains(ZonePoint(1.0, 1.0), outer))
        assertFalse(ZoneGeometry.contains(ZonePoint(1.0, 1.0), inner))
    }

    @Test fun activeZoneUsesExitHysteresisButNewEntryDoesNot() {
        val zone = listOf(
            ZonePoint(-0.001, -0.001), ZonePoint(-0.001, 0.001),
            ZonePoint(0.001, 0.001), ZonePoint(0.001, -0.001)
        )
        val tracker = RiskZoneMembershipTracker(exitHysteresisMeters = 25.0)

        assertTrue(tracker.update(ZonePoint(0.0, 0.0), listOf(zone)))
        // About 11 m outside the edge: hold the active risk flag to absorb GNSS noise.
        assertTrue(tracker.update(ZonePoint(0.0, 0.0011), listOf(zone)))
        // More than the 25 m exit margin: leave the zone.
        assertFalse(tracker.update(ZonePoint(0.0, 0.0015), listOf(zone)))
        // With the tracker now outside, the same near-edge point cannot trigger entry.
        assertFalse(tracker.update(ZonePoint(0.0, 0.0011), listOf(zone)))
    }

    @Test fun removingAllEnabledZonesClearsMembership() {
        val tracker = RiskZoneMembershipTracker()
        val zone = listOf(
            ZonePoint(-0.001, -0.001), ZonePoint(-0.001, 0.001),
            ZonePoint(0.001, 0.001), ZonePoint(0.001, -0.001)
        )

        assertTrue(tracker.update(ZonePoint(0.0, 0.0), listOf(zone)))
        assertFalse(tracker.update(ZonePoint(0.0, 0.0), emptyList()))
    }
}
