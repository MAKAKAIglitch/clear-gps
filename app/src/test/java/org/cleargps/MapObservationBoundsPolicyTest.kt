package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapObservationBoundsPolicyTest {
    @Test
    fun boundsContainEveryObservationIncludingDistantOutliers() {
        val bounds = requireNotNull(
            MapObservationBoundsPolicy.calculate(
                listOf(
                    MapCoordinate(52.0, 4.0),
                    MapCoordinate(52.001, 4.001),
                    MapCoordinate(54.0, 7.0)
                )
            )
        )

        assertEquals(54.0, bounds.north, 1e-9)
        assertEquals(52.0, bounds.south, 1e-9)
        assertEquals(7.0, bounds.east, 1e-9)
        assertEquals(4.0, bounds.west, 1e-9)
    }

    @Test
    fun boundsUseTheShortArcAcrossTheInternationalDateLine() {
        val bounds = requireNotNull(
            MapObservationBoundsPolicy.calculate(
                listOf(
                    MapCoordinate(0.0, 179.9),
                    MapCoordinate(0.0, -179.9)
                )
            )
        )

        assertEquals(179.9, bounds.west, 1e-6)
        assertEquals(180.1, bounds.east, 1e-6)
        assertTrue(bounds.east - bounds.west < 1.0)
    }

    @Test
    fun invalidCoordinatesAreIgnoredAndEmptyValidInputReturnsNull() {
        val bounds = requireNotNull(
            MapObservationBoundsPolicy.calculate(
                listOf(
                    MapCoordinate(51.0, 4.0),
                    MapCoordinate(Double.NaN, 5.0),
                    MapCoordinate(52.0, Double.POSITIVE_INFINITY),
                    MapCoordinate(91.0, 6.0),
                    MapCoordinate(53.0, -181.0)
                )
            )
        )

        val expectedForOnlyValidCoordinate = requireNotNull(
            MapObservationBoundsPolicy.calculate(listOf(MapCoordinate(51.0, 4.0)))
        )
        assertEquals(expectedForOnlyValidCoordinate, bounds)
        assertNull(
            MapObservationBoundsPolicy.calculate(
                listOf(
                    MapCoordinate(Double.NaN, 0.0),
                    MapCoordinate(0.0, 181.0)
                )
            )
        )
    }

    @Test
    fun oneObservationGetsNonZeroBoundsForStableCameraFit() {
        val bounds = requireNotNull(
            MapObservationBoundsPolicy.calculate(listOf(MapCoordinate(52.0, 5.0)))
        )

        assertTrue(bounds.north > bounds.south)
        assertTrue(bounds.east > bounds.west)
        assertTrue(bounds.north - bounds.south > 0.0005)
    }
}
