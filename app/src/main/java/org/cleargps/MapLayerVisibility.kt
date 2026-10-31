package org.cleargps

import android.content.SharedPreferences

/** Presentation-only visibility: does not change recording, positioning or network consent. */
data class MapLayerVisibility(
    val showWifiPoints: Boolean = true,
    val showCellPoints: Boolean = true,
    val showBlePoints: Boolean = true,
    val showRiskZones: Boolean = true,
    val showPositionCenters: Boolean = true,
    val showManualAnchor: Boolean = true,
    val showBeaconDbEstimate: Boolean = true,
    val showOpenCellIdCells: Boolean = true
)

internal object MapLayerVisibilityPolicy {
    fun isRadioTypeVisible(type: String, visibility: MapLayerVisibility): Boolean = when (type) {
        "wifi" -> visibility.showWifiPoints
        "cell" -> visibility.showCellPoints
        "ble" -> visibility.showBlePoints
        else -> false
    }
}

internal object MapLayerPreferences {
    private const val KEY_WIFI = "map_layer_wifi"
    private const val KEY_CELLS = "map_layer_cells"
    private const val KEY_BLE = "map_layer_ble"
    private const val KEY_ZONES = "map_layer_zones"
    private const val KEY_CENTERS = "map_layer_centers"
    private const val KEY_ANCHOR = "map_layer_manual_anchor"
    private const val KEY_BEACONDB = "map_layer_beacondb"
    private const val KEY_OPENCELLID = "map_layer_opencellid"

    fun load(prefs: SharedPreferences): MapLayerVisibility = MapLayerVisibility(
        showWifiPoints = prefs.getBoolean(KEY_WIFI, true),
        showCellPoints = prefs.getBoolean(KEY_CELLS, true),
        showBlePoints = prefs.getBoolean(KEY_BLE, true),
        showRiskZones = prefs.getBoolean(KEY_ZONES, true),
        showPositionCenters = prefs.getBoolean(KEY_CENTERS, true),
        showManualAnchor = prefs.getBoolean(KEY_ANCHOR, true),
        showBeaconDbEstimate = prefs.getBoolean(KEY_BEACONDB, true),
        showOpenCellIdCells = prefs.getBoolean(KEY_OPENCELLID, true)
    )

    fun save(prefs: SharedPreferences, value: MapLayerVisibility) {
        prefs.edit()
            .putBoolean(KEY_WIFI, value.showWifiPoints)
            .putBoolean(KEY_CELLS, value.showCellPoints)
            .putBoolean(KEY_BLE, value.showBlePoints)
            .putBoolean(KEY_ZONES, value.showRiskZones)
            .putBoolean(KEY_CENTERS, value.showPositionCenters)
            .putBoolean(KEY_ANCHOR, value.showManualAnchor)
            .putBoolean(KEY_BEACONDB, value.showBeaconDbEstimate)
            .putBoolean(KEY_OPENCELLID, value.showOpenCellIdCells)
            .apply()
    }
}
