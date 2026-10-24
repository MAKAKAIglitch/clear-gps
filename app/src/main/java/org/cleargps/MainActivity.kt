package org.cleargps

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null
    private var mapStarted = false
    private var mapResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "ClearGPS/0.2 (https://github.com/MAKAKAIglitch/clear-gps)")
                .build())
        }.build())

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
