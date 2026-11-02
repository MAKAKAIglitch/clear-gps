package org.cleargps.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellTimingAdvanceRangePolicyTest {
    @Test
    fun convertsLteCommandIndexToConservativeOneWayRange() {
        val range = CellTimingAdvanceRangePolicy.from(1, "lte-command-index")!!

        assertEquals(78.125, range.nominalDistanceMeters, 0.000001)
        assertEquals(39.0625, range.minimumDistanceMeters, 0.000001)
        assertEquals(117.1875, range.maximumDistanceMeters, 0.000001)
        assertEquals(39.0625, range.quantizationHalfWidthMeters, 0.000001)
    }

    @Test
    fun zeroAndMaximumIndicesRemainInsideAndroidRange() {
        val zero = CellTimingAdvanceRangePolicy.from(0, "lte-command-index")!!
        val maximum = CellTimingAdvanceRangePolicy.from(1_282, "lte-command-index")!!

        assertEquals(0.0, zero.minimumDistanceMeters, 0.0)
        assertEquals(39.0625, zero.maximumDistanceMeters, 0.000001)
        assertEquals(100_156.25, maximum.nominalDistanceMeters, 0.000001)
    }

    @Test
    fun refusesUnknownTechnologyUnitsAndOutOfRangeValues() {
        assertNull(CellTimingAdvanceRangePolicy.from(10, "gsm-symbols"))
        assertNull(CellTimingAdvanceRangePolicy.from(10, "nr-command-index"))
        assertNull(CellTimingAdvanceRangePolicy.from(1_283, "lte-command-index"))
        assertNull(CellTimingAdvanceRangePolicy.from(-1, "lte-command-index"))
        assertNull(CellTimingAdvanceRangePolicy.from(null, "lte-command-index"))
    }
}
