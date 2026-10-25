package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

class LocalSignalPositionTest {
    @Test fun strongerSignalMustBeatStoredPeakByThreeDb() {
        assertTrue(isBetterSignalSample(-55, -59, 8.0))
        assertFalse(isBetterSignalSample(-57, -59, 8.0))
    }

    @Test fun weakOrInvalidPositionFixNeverPromotesPoint() {
        assertFalse(isBetterSignalSample(-45, -60, 51.0))
        assertFalse(isBetterSignalSample(-45, -60, 0.0))
        assertFalse(isBetterSignalSample(-45, -60, Double.NaN))
    }

    @Test fun observationCentreUsesLocatedSamplesAndIgnoresMissingCoordinates() {
        val centre = estimateObservationCenter(listOf(
            RadioPointObservation(1, 1000L, 55.000, 40.000, 10.0, -50, 2412, "gps"),
            RadioPointObservation(2, 2000L, 55.002, 40.002, 10.0, -60, 2412, "gps"),
            RadioPointObservation(3, 3000L, null, null, null, -70, 2412, "unlocated")
        ))
        requireNotNull(centre)
        assertEquals(2, centre.sampleCount)
        assertTrue(abs(centre.latitude - 55.001) < 0.001)
        assertTrue(abs(centre.longitude - 40.001) < 0.001)
        assertTrue(centre.accuracyMeters > 0.0)
    }

    @Test fun observationCentreHandlesTheInternationalDateLine() {
        val centre = estimateObservationCenter(listOf(
            RadioPointObservation(1, 1000L, 0.0, 179.99, 20.0, -50, 0, "gps"),
            RadioPointObservation(2, 2000L, 0.0, -179.99, 20.0, -55, 0, "gps")
        ))
        requireNotNull(centre)
        assertTrue(abs(centre.longitude) > 179.9)
    }

    @Test fun observationCentreIsNullWhenNoCoordinatesWereRecorded() {
        assertNull(estimateObservationCenter(listOf(
            RadioPointObservation(1, 1000L, null, null, null, -50, 0, "unlocated")
        )))
    }
}
