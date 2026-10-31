package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayerVisibilityTest {
    @Test
    fun defaultShowsAllLayers() {
        val state = MapLayerVisibility()
        assertTrue(MapLayerVisibilityPolicy.isRadioTypeVisible("wifi", state))
        assertTrue(MapLayerVisibilityPolicy.isRadioTypeVisible("cell", state))
        assertTrue(MapLayerVisibilityPolicy.isRadioTypeVisible("ble", state))
        assertTrue(state.showRiskZones)
        assertTrue(state.showPositionCenters)
        assertTrue(state.showManualAnchor)
        assertTrue(state.showBeaconDbEstimate)
        assertTrue(state.showOpenCellIdCells)
    }

    @Test
    fun radioLayerVisibilityIsIndependent() {
        val state = MapLayerVisibility().copy(showWifiPoints = false)
        assertFalse(MapLayerVisibilityPolicy.isRadioTypeVisible("wifi", state))
        assertTrue(MapLayerVisibilityPolicy.isRadioTypeVisible("cell", state))
        assertTrue(MapLayerVisibilityPolicy.isRadioTypeVisible("ble", state))
    }

    @Test
    fun unknownRadioLayerIsHidden() {
        assertFalse(MapLayerVisibilityPolicy.isRadioTypeVisible("unknown", MapLayerVisibility()))
    }

    @Test
    fun referenceLayersCanBeToggledIndependently() {
        val state = MapLayerVisibility(
            showRiskZones = false,
            showPositionCenters = true,
            showManualAnchor = false,
            showBeaconDbEstimate = true,
            showOpenCellIdCells = false
        )
        assertFalse(state.showRiskZones)
        assertTrue(state.showPositionCenters)
        assertFalse(state.showManualAnchor)
        assertTrue(state.showBeaconDbEstimate)
        assertFalse(state.showOpenCellIdCells)
    }
}
