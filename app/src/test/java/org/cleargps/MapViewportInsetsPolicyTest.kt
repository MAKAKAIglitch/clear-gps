package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapViewportInsetsPolicyTest {
    @Test
    fun calculatesInsetsRelativeToActualMapViewWindowPosition() {
        val insets = MapViewportInsetsPolicy.calculate(
            mapTopInWindowPx = 120,
            mapBottomInWindowPx = 1_920,
            headerBottomInWindowPx = 245,
            sheetTopInWindowPx = 1_500,
            topGapPx = 12
        )

        assertEquals(MapViewportInsets(topPx = 137, bottomPx = 420), insets)
    }

    @Test
    fun doesNotReturnInsetsWhenHeaderAndSheetCoverTheWholeMap() {
        val insets = MapViewportInsetsPolicy.calculate(
            mapTopInWindowPx = 100,
            mapBottomInWindowPx = 1_000,
            headerBottomInWindowPx = 700,
            sheetTopInWindowPx = 300,
            topGapPx = 16
        )

        assertNull(insets)
    }

    @Test
    fun rejectsAnUnmeasuredOrInvalidViewport() {
        assertNull(
            MapViewportInsetsPolicy.calculate(
                mapTopInWindowPx = 500,
                mapBottomInWindowPx = 500,
                headerBottomInWindowPx = 600,
                sheetTopInWindowPx = 700,
                topGapPx = 12
            )
        )
        assertNull(
            MapViewportInsetsPolicy.calculate(
                mapTopInWindowPx = 100,
                mapBottomInWindowPx = 1_000,
                headerBottomInWindowPx = 250,
                sheetTopInWindowPx = 1_000,
                topGapPx = 12
            )
        )
    }
}
