package org.cleargps

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null
    private var mapStarted = false
    private var mapResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && CrashLoopGuard.isSafeModeActive(this)) {
            Toast.makeText(this, R.string.crash_loop_safe_mode_notice, Toast.LENGTH_LONG).show()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        MapLibre.getInstance(this)
        setContent {
            ClearGpsApp(onMapViewCreated = ::attachMapView)
        }
    }

    private fun attachMapView(view: MapView) {
        if (mapView === view) return
        mapView = view
        view.onCreate(null)
        if (mapStarted) view.onStart()
        if (mapResumed) view.onResume()
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
        super.onDestroy()
    }
}
