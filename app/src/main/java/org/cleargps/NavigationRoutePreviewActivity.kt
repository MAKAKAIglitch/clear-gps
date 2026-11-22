package org.cleargps

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * A dedicated, dismissible route-preview window. The previous map screen remains underneath
 * in the activity back stack, preserving its Compose state and map instance.
 */
class NavigationRoutePreviewActivity : ComponentActivity() {
    private var mapView: MapView? = null
    private var mapStarted = false
    private var mapResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        val destination = NavigationCoordinate(latitude, longitude)
        if (!destination.isValid()) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        MapLibre.getInstance(this)
        NetworkGate.installMapLibre(applicationContext)

        val destinationName = intent.getStringExtra(EXTRA_DESTINATION_NAME) ?: "Точка на карте"
        setContent {
            NavigationPrototypeScreen(
                onMapViewCreated = ::attachMapView,
                onMapViewDisposed = ::detachMapView,
                onOpenLocationSettings = {},
                initialRouteDestination = destination,
                initialDestinationName = destinationName,
                onClosePreview = ::finish
            )
        }
    }

    private fun attachMapView(view: MapView) {
        if (mapView === view) return
        mapView = view
        view.onCreate(null)
        if (mapStarted) view.onStart()
        if (mapResumed) view.onResume()
    }

    private fun detachMapView(view: MapView) {
        if (mapView !== view) return
        if (mapResumed) view.onPause()
        if (mapStarted) view.onStop()
        view.onDestroy()
        mapView = null
    }

    override fun onStart() {
        super.onStart()
        mapStarted = true
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapResumed = true
        mapView?.onResume()
    }

    override fun onPause() {
        mapResumed = false
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapStarted = false
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        mapView = null
        // The original map activity is still underneath this preview and remains active.
        getSharedPreferences(ClearGpsPreferences.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(ClearGpsPreferences.KEY_NAVIGATION_MAP_ACTIVE, true)
            .apply()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LATITUDE = "org.cleargps.extra.ROUTE_LATITUDE"
        const val EXTRA_LONGITUDE = "org.cleargps.extra.ROUTE_LONGITUDE"
        const val EXTRA_DESTINATION_NAME = "org.cleargps.extra.ROUTE_DESTINATION_NAME"
    }
}
