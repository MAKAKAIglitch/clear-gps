package org.cleargps.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneGeometryTest {
    private val square = listOf(
        ZonePoint(10.0, 10.0), ZonePoint(10.0, 11.0),
        ZonePoint(11.0, 11.0), ZonePoint(11.0, 10.0)
    )
    @Test fun insidePointIsDetected() = assertTrue(ZoneGeometry.contains(ZonePoint(10.5, 10.5), square))
    @Test fun outsidePointIsRejected() = assertFalse(ZoneGeometry.contains(ZonePoint(12.0, 10.5), square))
    @Test fun boundaryIsIncluded() = assertTrue(ZoneGeometry.contains(ZonePoint(10.0, 10.5), square))
    @Test fun shortPolygonIsRejected() = assertFalse(ZoneGeometry.contains(ZonePoint(10.5, 10.5), square.take(2)))
    @Test fun invalidCoordinatesAreRejected() = assertFalse(ZoneGeometry.contains(ZonePoint(91.0, 0.0), square))
}
