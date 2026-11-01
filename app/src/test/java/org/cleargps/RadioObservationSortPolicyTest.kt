package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioObservationSortPolicyTest {
    private fun observation(id: Long, rssi: Int, observedAt: Long) =
        RadioPointObservation(
            id = id,
            observedAt = observedAt,
            latitude = 52.0,
            longitude = 5.0,
            accuracyMeters = 10.0,
            rssi = rssi,
            frequency = 2412,
            positionSource = "gps"
        )

    @Test
    fun strongestSignalAppearsFirstAndWeakestLast() {
        val result = RadioObservationSortPolicy.strongestFirst(
            listOf(
                observation(1L, -91, 300L),
                observation(2L, -42, 100L),
                observation(3L, -68, 200L)
            )
        )

        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }

    @Test
    fun equalSignalsAreOrderedByNewestObservationFirst() {
        val result = RadioObservationSortPolicy.strongestFirst(
            listOf(
                observation(1L, -55, 100L),
                observation(2L, -55, 300L),
                observation(3L, -55, 200L)
            )
        )

        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }

    @Test
    fun invalidSignalStrengthsArePlacedAfterValidMeasurements() {
        val result = RadioObservationSortPolicy.strongestFirst(
            listOf(
                observation(1L, 0, 500L),
                observation(2L, -60, 100L),
                observation(3L, -128, 400L),
                observation(4L, -45, 50L)
            )
        )

        assertEquals(listOf(4L, 2L, 1L, 3L), result.map { it.id })
    }
}
