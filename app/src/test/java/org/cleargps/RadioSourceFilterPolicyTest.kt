package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSourceFilterPolicyTest {
    private val wifiToday = segment(
        dayKey = "2026-10-27", radioType = "wifi",
        observationCount = 12, radioCount = 4, minRssi = -92, maxRssi = -48
    )
    private val cellToday = segment(
        dayKey = "2026-10-27", radioType = "cell",
        observationCount = 3, radioCount = 2, minRssi = -101, maxRssi = -82
    )
    private val wifiYesterday = segment(
        dayKey = "2026-10-26", radioType = "wifi",
        observationCount = 2, radioCount = 2, minRssi = -88, maxRssi = -74
    )
    private val source = RadioSourceRecord(
        id = 7, kind = "wigle", name = "WiGLE Import",
        createdAt = 1L, lastSeen = 2L, enabled = true,
        trustWeight = 1.0, observationCount = 17, radioCount = 8
    )

    @Test
    fun defaultPolicyDoesNotHideSourcesWithoutDayRows() {
        val policy = RadioSourceFilterPolicy()

        assertFalse(policy.hasDataFilters)
        assertTrue(policy.matchesSegment(wifiToday))
        assertTrue(policy.matchesSegment(cellToday))
        assertTrue(policy.matchesDay("2026-10-27", emptyList()))
        assertTrue(policy.matchesSource(source, hasMatchingDay = false))
    }

    @Test
    fun sourceNameSearchIsTrimmedAndCaseInsensitive() {
        val policy = RadioSourceFilterPolicy(sourceNameQuery = "  wigle  ")

        assertTrue(policy.matchesSource(source, hasMatchingDay = false))
        assertFalse(policy.matchesSource(source.copy(name = "BeaconDB"), hasMatchingDay = false))
    }

    @Test
    fun filtersSegmentsByDateAndRadioType() {
        val policy = RadioSourceFilterPolicy(
            dateQuery = "2026-10-27",
            radioType = "wifi"
        )

        assertTrue(policy.matchesSegment(wifiToday))
        assertFalse(policy.matchesSegment(cellToday))
        assertFalse(policy.matchesSegment(wifiYesterday))
    }

    @Test
    fun minimumRssiUsesStrongestSampleAndMinimumObservationCount() {
        val policy = RadioSourceFilterPolicy(
            minimumRssi = -70,
            minimumObservations = 5
        )

        assertTrue(policy.matchesSegment(wifiToday))
        assertFalse(policy.matchesSegment(cellToday))
        assertFalse(policy.matchesSegment(wifiYesterday))
    }

    @Test
    fun dayAndSourceDisappearWhenNoSegmentMatchesActiveDataFilters() {
        val policy = RadioSourceFilterPolicy(
            dateQuery = "2026-10-27",
            radioType = "ble"
        )
        val rows = listOf(wifiToday, cellToday, wifiYesterday)
        val matching = rows.filter(policy::matchesSegment)

        assertTrue(matching.isEmpty())
        assertFalse(policy.matchesDay("2026-10-27", matching))
        assertFalse(policy.matchesDay("2026-10-26", matching))
        assertFalse(policy.matchesSource(source, hasMatchingDay = false))
    }

    @Test
    fun matchingDayKeepsSourceVisibleAndOnlyMatchingSegmentsRemain() {
        val policy = RadioSourceFilterPolicy(
            dateQuery = "2026-10",
            radioType = "wifi",
            minimumRssi = -80,
            minimumObservations = 3
        )
        val matching = listOf(wifiToday, cellToday, wifiYesterday).filter(policy::matchesSegment)

        assertEquals(listOf(wifiToday), matching)
        assertTrue(policy.matchesDay("2026-10-27", matching))
        assertFalse(policy.matchesDay("2026-10-26", matching))
        assertTrue(policy.matchesSource(source, hasMatchingDay = true))
        assertFalse(policy.matchesSource(source, hasMatchingDay = false))
    }

    private fun segment(
        dayKey: String,
        radioType: String,
        observationCount: Int,
        radioCount: Int,
        minRssi: Int,
        maxRssi: Int
    ) = RadioSourceSegmentRecord(
        sourceId = 7,
        dayKey = dayKey,
        radioType = radioType,
        firstObservedAt = 1L,
        lastObservedAt = 2L,
        observationCount = observationCount,
        radioCount = radioCount,
        minRssi = minRssi,
        maxRssi = maxRssi,
        enabled = true,
        trustWeight = 1.0
    )
}
