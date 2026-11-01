package org.cleargps

/**
 * Insets for the portion of MapLibre that remains visible between the map header
 * and the top edge of the radio-point details panel.
 */
internal data class MapViewportInsets(
    val topPx: Int,
    val bottomPx: Int
)

internal object MapViewportInsetsPolicy {
    fun calculate(
        mapTopInWindowPx: Int,
        mapBottomInWindowPx: Int,
        headerBottomInWindowPx: Int,
        sheetTopInWindowPx: Int,
        topGapPx: Int
    ): MapViewportInsets? {
        if (mapBottomInWindowPx <= mapTopInWindowPx ||
            headerBottomInWindowPx <= 0 ||
            sheetTopInWindowPx <= mapTopInWindowPx ||
            sheetTopInWindowPx >= mapBottomInWindowPx
        ) {
            return null
        }

        val mapHeight = mapBottomInWindowPx - mapTopInWindowPx
        val top = (headerBottomInWindowPx - mapTopInWindowPx + topGapPx.coerceAtLeast(0))
            .coerceIn(0, mapHeight)
        val bottom = (mapBottomInWindowPx - sheetTopInWindowPx)
            .coerceIn(0, mapHeight)

        // No camera viewport remains when the header and panel cover the whole MapView.
        if (top + bottom >= mapHeight) return null

        return MapViewportInsets(topPx = top, bottomPx = bottom)
    }
}
