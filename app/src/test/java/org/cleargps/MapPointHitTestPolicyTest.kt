package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapPointHitTestPolicyTest {
    @Test
    fun acceptsTapAcrossTheFull48DpTarget() {
        val selected = MapPointHitTestPolicy.selectNearest(
            candidates = listOf(MapPointHitCandidate(1L, 69f, 0f)),
            tapX = 0f,
            tapY = 0f,
            density = 3f
        )

        assertEquals(listOf(1L), selected?.pointIds)
        assertEquals(69f, selected?.nearestDistancePx)
    }

    @Test
    fun hitTargetIncludesTheFull48DpSquare() {
        val selected = MapPointHitTestPolicy.selectNearest(
            candidates = listOf(MapPointHitCandidate(1L, 69f, 69f)),
            tapX = 0f,
            tapY = 0f,
            density = 3f
        )

        assertEquals(listOf(1L), selected?.pointIds)
    }

    @Test
    fun nearestMarkerWinsWhenTouchTargetsOverlap() {
        val selected = MapPointHitTestPolicy.selectNearest(
            candidates = listOf(
                MapPointHitCandidate(10L, 20f, 0f),
                MapPointHitCandidate(20L, 12f, 0f)
            ),
            tapX = 0f,
            tapY = 0f,
            density = 1f
        )

        assertEquals(listOf(20L), selected?.pointIds)
        assertEquals(12f, selected?.nearestDistancePx)
    }

    @Test
    fun colocatedMarkersAreReturnedTogetherInNearestFirstOrder() {
        val selected = MapPointHitTestPolicy.selectNearest(
            candidates = listOf(
                MapPointHitCandidate(3L, 10f, 0f),
                MapPointHitCandidate(2L, 3f, 0f),
                MapPointHitCandidate(1L, 0f, 0f),
                MapPointHitCandidate(1L, 1f, 0f)
            ),
            tapX = 2f,
            tapY = 0f,
            density = 1f
        )

        assertEquals(listOf(1L, 2L), selected?.pointIds)
    }

    @Test
    fun rejectsMarkersOutsideThe48DpTarget() {
        val selected = MapPointHitTestPolicy.selectNearest(
            candidates = listOf(MapPointHitCandidate(1L, 25f, 0f)),
            tapX = 0f,
            tapY = 0f,
            density = 1f
        )

        assertNull(selected)
    }

    @Test
    fun ignoresInvalidCandidatesAndInvalidDensity() {
        val selected = MapPointHitTestPolicy.selectNearest(
            candidates = listOf(
                MapPointHitCandidate(0L, 1f, 0f),
                MapPointHitCandidate(2L, Float.NaN, 0f),
                MapPointHitCandidate(3L, 1f, 0f)
            ),
            tapX = 0f,
            tapY = 0f,
            density = 1f
        )

        assertEquals(listOf(3L), selected?.pointIds)
        assertNull(
            MapPointHitTestPolicy.selectNearest(
                candidates = listOf(MapPointHitCandidate(3L, 1f, 0f)),
                tapX = 0f,
                tapY = 0f,
                density = Float.NaN
            )
        )
    }
}
