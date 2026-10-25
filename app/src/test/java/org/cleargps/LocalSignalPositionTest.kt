package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
