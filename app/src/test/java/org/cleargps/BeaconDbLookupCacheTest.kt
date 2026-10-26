package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconDbLookupCacheTest {
    private var monotonicNow = 1_000L

    private val cellA = BeaconDbCell("lte", 250, 1, 10, 101, -80)
    private val cellB = BeaconDbCell("gsm", 250, 1, 11, 202, -92)
    private val coordinate = BeaconDbCoordinate(52.123456, 40.654321, 450.0)

    private fun cache(ttlMillis: Long = 100L, maxEntries: Int = 8) =
        BeaconDbLookupCache(
            ttlMillis = ttlMillis,
            maxEntries = maxEntries,
            monotonicTimeMillis = { monotonicNow }
        )

    @Test
    fun cacheHitIsIndependentOfCellOrderAndReportsEntryAge() {
        val cache = cache()
        cache.put(listOf(cellA, cellB), coordinate, queriedAtMillis = 1_700L)
        monotonicNow += 12L

        val hit = cache.get(listOf(cellB, cellA))

        assertNotNull(hit)
        assertEquals(coordinate, hit!!.coordinate)
        assertEquals(1_700L, hit.queriedAtMillis)
        assertEquals(12L, hit.ageMillis)
    }

    @Test
    fun cacheExpiresAtTheTtlBoundary() {
        val cache = cache(ttlMillis = 100L)
        cache.put(listOf(cellA), coordinate, queriedAtMillis = 1_700L)

        monotonicNow = 1_099L
        assertNotNull(cache.get(listOf(cellA)))

        monotonicNow = 1_100L
        assertNull(cache.get(listOf(cellA)))
    }

    @Test
    fun changedSignalStrengthDoesNotReuseAStaleEstimate() {
        val cache = cache()
        cache.put(listOf(cellA), coordinate, queriedAtMillis = 1_700L)

        assertNull(cache.get(listOf(cellA.copy(signalStrength = -81))))
    }

    @Test
    fun backwardsMonotonicClockInvalidatesEntry() {
        val cache = cache()
        cache.put(listOf(cellA), coordinate, queriedAtMillis = 1_700L)

        monotonicNow = 999L

        assertNull(cache.get(listOf(cellA)))
    }

    @Test
    fun clearingCacheRemovesEntriesImmediately() {
        val cache = cache()
        cache.put(listOf(cellA), coordinate, queriedAtMillis = 1_700L)

        cache.clear()

        assertNull(cache.get(listOf(cellA)))
    }

    @Test
    fun inFlightResponseCannotRepublishEntryAfterCacheWasCleared() {
        val cache = cache()
        val requestGeneration = cache.generation()

        cache.clear()
        cache.put(
            listOf(cellA), coordinate, queriedAtMillis = 1_700L,
            expectedGeneration = requestGeneration
        )

        assertNull(cache.get(listOf(cellA)))
        assertTrue(!cache.isGenerationCurrent(requestGeneration))
    }

    @Test
    fun invalidCoordinatesAreNeverCached() {
        val cache = cache()
        cache.put(
            listOf(cellA),
            BeaconDbCoordinate(latitude = 91.0, longitude = 40.0, accuracyMeters = 50.0),
            queriedAtMillis = 1_700L
        )

        assertNull(cache.get(listOf(cellA)))
    }

    @Test
    fun cacheIsBoundedAndEvictsLeastRecentlyUsedEntry() {
        val cache = cache(ttlMillis = 10_000L, maxEntries = 2)
        cache.put(listOf(cellA), coordinate, queriedAtMillis = 1_700L)
        cache.put(listOf(cellB), coordinate, queriedAtMillis = 1_701L)
        assertNotNull(cache.get(listOf(cellA))) // Makes cellA most recently used.
        cache.put(
            listOf(BeaconDbCell("wcdma", 250, 1, 12, 303, -88)),
            coordinate,
            queriedAtMillis = 1_702L
        )

        assertNotNull(cache.get(listOf(cellA)))
        assertNull(cache.get(listOf(cellB)))
        assertTrue(cache.get(listOf(BeaconDbCell("wcdma", 250, 1, 12, 303, -88))) != null)
    }
}
