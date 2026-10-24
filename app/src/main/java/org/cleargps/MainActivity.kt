package org.cleargps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.cleargps.core.FilterState
import org.cleargps.core.ZonePoint
import org.cleargps.core.ZoneStore
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import okhttp3.OkHttpClient

class MainActivity : Activity() {
    private lateinit var mapView: MapView
    private lateinit var statusView: TextView
    private lateinit var drawButton: Button
    private lateinit var monitorButton: Button
    private lateinit var mockButton: Button
    private var map: MapLibreMap? = null
    private var polygonSource: GeoJsonSource? = null
    private var lineSource: GeoJsonSource? = null
    private var pointsSource: GeoJsonSource? = null
    private var drawMode = false
    private var pendingMonitor = false
    private var pendingMockEnable = false
    private val zonePoints = mutableListOf<ZonePoint>()
    private val prefs by lazy { getSharedPreferences(LocationMonitorService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        zonePoints.addAll(ZoneStore.load(this))
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "ClearGPS/0.1 (https://github.com/MAKAKAIglitch/clear-gps)")
                .build())
        }.build())
        buildUi(savedInstanceState)
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 12f
            setTextColor(Color.rgb(160, 174, 192))
        })

        val mapFrame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(21, 28, 42)) }
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapFrame.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        val attribution = TextView(this).apply {
            text = getString(R.string.attribution)
            textSize = 10f
            setTextColor(Color.rgb(30, 41, 59))
            setBackgroundColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(5), dp(3), dp(5), dp(3))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
        }
        mapFrame.addView(attribution, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        root.addView(mapFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val zoneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        drawButton = button(R.string.draw_zone) { beginDrawing() }
        zoneRow.addView(drawButton, weighted())
        zoneRow.addView(button(R.string.finish_zone) { finishDrawing() }, weighted())
        zoneRow.addView(button(R.string.clear_zone) { clearZone() }, weighted())
        root.addView(zoneRow)

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        root.addView(statusView)
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        monitorButton = button(R.string.start_filter) { toggleMonitoring() }
        mockButton = button(R.string.enable_mock) { toggleMockOutput() }
        actionRow.addView(monitorButton, weighted())
        actionRow.addView(mockButton, weighted())
        root.addView(actionRow)
        root.addView(button(R.string.center_location) { centerOnCurrentLocation() })
        setContentView(root)

        mapView.getMapAsync { mapObject ->
            map = mapObject
            mapObject.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                installZoneLayers(style)
                mapObject.cameraPosition = CameraPosition.Builder().target(LatLng(55.0, 40.0)).zoom(4.0).build()
                mapObject.addOnMapClickListener { point ->
                    if (!drawMode) return@addOnMapClickListener false
                    zonePoints.add(ZonePoint(point.latitude, point.longitude))
                    redrawZone()
                    true
                }
                redrawZone()
            }
        }
        refreshStatus()
    }

    private fun installZoneLayers(style: Style) {
        polygonSource = GeoJsonSource(POLYGON_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>()))
        lineSource = GeoJsonSource(LINE_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>()))
        pointsSource = GeoJsonSource(POINTS_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>()))
        style.addSource(polygonSource!!); style.addSource(lineSource!!); style.addSource(pointsSource!!)
        style.addLayer(FillLayer(POLYGON_LAYER, POLYGON_SOURCE).withProperties(
            PropertyFactory.fillColor("#EF4444"), PropertyFactory.fillOpacity(0.22f)))
        style.addLayer(LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
            PropertyFactory.lineColor("#F87171"), PropertyFactory.lineWidth(3f)))
        style.addLayer(CircleLayer(POINTS_LAYER, POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(4.5f), PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor("#EF4444"), PropertyFactory.circleStrokeWidth(2f)))
    }

    private fun redrawZone() {
        val points = zonePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
        val polygon = if (points.size >= 3) {
            val ring = points.toMutableList()
            if (ring.first() != ring.last()) ring.add(ring.first())
            FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))))
        } else FeatureCollection.fromFeatures(emptyArray<Feature>())
        val linePoints = if (points.size >= 3) points + points.first() else points
        val line = if (linePoints.size >= 2) FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(LineString.fromLngLats(linePoints)))) else FeatureCollection.fromFeatures(emptyArray<Feature>())
        polygonSource?.setGeoJson(polygon)
        lineSource?.setGeoJson(line)
        pointsSource?.setGeoJson(FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(it) }.toTypedArray()))
    }

    private fun beginDrawing() {
        drawMode = true
        zonePoints.clear()
        redrawZone()
        drawButton.text = getString(R.string.draw_zone) + " •"
    }

    private fun finishDrawing() {
        drawMode = false
        if (zonePoints.size < 3) {
            Toast.makeText(this, R.string.zone_too_small, Toast.LENGTH_SHORT).show()
            redrawZone()
            return
        }
        ZoneStore.save(this, zonePoints)
        redrawZone()
        drawButton.text = getString(R.string.draw_zone)
        Toast.makeText(this, R.string.zone_saved, Toast.LENGTH_SHORT).show()
    }

    private fun clearZone() {
        drawMode = false
        zonePoints.clear()
        ZoneStore.save(this, emptyList())
        redrawZone()
        drawButton.text = getString(R.string.draw_zone)
    }

    private fun toggleMonitoring() {
        if (prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) {
            prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, false).apply()
            startService(Intent(this, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_STOP))
            refreshStatus()
            return
        }
        if (!hasLocationPermission()) {
            pendingMonitor = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
        } else startMonitoring()
    }

    private fun toggleMockOutput() {
        if (prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)) {
            prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false).apply()
            startService(Intent(this, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_DISABLE_MOCK))
            refreshStatus()
            return
        }
        if (!hasLocationPermission()) {
            pendingMockEnable = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
        } else enableMockOutput()
    }

    private fun enableMockOutput() {
        prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, true).apply()
        startServiceCompat(Intent(this, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_START))
        Toast.makeText(this, R.string.mock_warning, Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun startMonitoring() {
        startServiceCompat(Intent(this, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_START))
        refreshStatus()
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Deprecated("Deprecated by Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_REQUEST) return
        if (!grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            pendingMonitor = false; pendingMockEnable = false
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingMonitor) { pendingMonitor = false; startMonitoring() }
        if (pendingMockEnable) { pendingMockEnable = false; enableMockOutput() }
    }

    private fun centerOnCurrentLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
            return
        }
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude), 15.0))
            else Toast.makeText(this, R.string.no_location, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.no_location, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val state = prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name
        val score = prefs.getInt(LocationMonitorService.KEY_SCORE, 0)
        val source = prefs.getString(LocationMonitorService.KEY_SOURCE, "NONE") ?: "NONE"
        val zone = prefs.getBoolean(LocationMonitorService.KEY_IN_ZONE, false)
        val status = prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "")
        statusView.text = "State: " + state + " · risk " + score + "%" +
            (if (zone) "\nInside marked risk zone" else "\nOutside marked risk zone") +
            " · source " + source + (if (status.isNullOrBlank()) "" else "\n" + status)
        monitorButton.text = getString(if (prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false))
            R.string.stop_filter else R.string.start_filter)
        mockButton.text = getString(if (prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false))
            R.string.disable_mock else R.string.enable_mock)
    }

    private fun button(id: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(id)
        isAllCaps = false
        setOnClickListener { onClick() }
    }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        setMargins(dp(2), dp(1), dp(2), dp(1))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume(); refreshStatus() }
    override fun onPause() { if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onStop() { if (::mapView.isInitialized) mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }
    override fun onDestroy() { if (::mapView.isInitialized) mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val LOCATION_REQUEST = 4001
        private const val POLYGON_SOURCE = "risk-polygon"
        private const val LINE_SOURCE = "risk-line"
        private const val POINTS_SOURCE = "risk-points"
        private const val POLYGON_LAYER = "risk-polygon-layer"
        private const val LINE_LAYER = "risk-line-layer"
        private const val POINTS_LAYER = "risk-points-layer"
        private val OSM_STYLE_JSON = """
            {
              "version": 8,
              "sources": {"osm": {"type": "raster",
                "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                "tileSize": 256, "attribution": "© OpenStreetMap contributors"}},
              "layers": [
                {"id": "background", "type": "background", "paint": {"background-color": "#0B1020"}},
                {"id": "osm-raster", "type": "raster", "source": "osm", "minzoom": 0, "maxzoom": 19}
              ]
            }
        """.trimIndent()
    }
}
