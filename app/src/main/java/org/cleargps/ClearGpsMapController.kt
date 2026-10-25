package org.cleargps

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.location.Location
import android.view.animation.DecelerateInterpolator
import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
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
import kotlin.math.abs

data class MapCoordinate(val latitude: Double, val longitude: Double)
enum class MapTapMode { NONE, PLACE_ANCHOR, DRAW_ZONE }

class ClearGpsMapController {
    private var map: MapLibreMap? = null
    private var locationSource: GeoJsonSource? = null
    private var calculatedLocationSource: GeoJsonSource? = null
    private var accuracySource: GeoJsonSource? = null
    private var anchorSource: GeoJsonSource? = null
    private var polygonSource: GeoJsonSource? = null
    private var lineSource: GeoJsonSource? = null
    private var zonePointsSource: GeoJsonSource? = null
    private var wifiPointsSource: GeoJsonSource? = null
    private var cellPointsSource: GeoJsonSource? = null
    private var blePointsSource: GeoJsonSource? = null
    private var primaryLocation: MapCoordinate? = null
    private var calculatedLocation: MapCoordinate? = null
    /** Latest Android-reported horizontal accuracy, in metres. */
    private var primaryAccuracyMeters: Double? = null
    /** Displayed radius is animated toward the latest measured accuracy. */
    private var displayedAccuracyMeters: Double? = null
    private var accuracyAnimator: ValueAnimator? = null
    private var manualAnchor: ManualAnchor? = null
    private var savedZones: List<SavedRiskZone> = emptyList()
    private var draftZone: List<ZonePoint> = emptyList()
    private var radioPoints: List<RadioPointRecord> = emptyList()
    private var tapMode: MapTapMode = MapTapMode.NONE
    private var mapReady = false
    private var cameraInitialized = false
    private var darkMode = false
    private var offlineMode = false
    private var showRadioPoints = true
    private var maxRadioPoints = MAX_MAP_RADIO_POINTS
    var onMapTap: ((Double, Double) -> Boolean)? = null
    var onRadioPointTap: ((Long) -> Unit)? = null

    fun attach(
        mapView: MapView, context: Context,
        initialDarkMode: Boolean = false, initialOfflineMode: Boolean = false
    ) {
        darkMode = initialDarkMode
        offlineMode = initialOfflineMode
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        maxRadioPoints = if (activityManager?.isLowRamDevice == true ||
            Runtime.getRuntime().maxMemory() < 256L * 1024L * 1024L) 400 else MAX_MAP_RADIO_POINTS
        mapView.getMapAsync { mapObject ->
            map = mapObject
            applyStyle()
            mapObject.addOnMapClickListener { point ->
                if (tapMode != MapTapMode.NONE) {
                    onMapTap?.invoke(point.latitude, point.longitude) ?: false
                } else {
                    val hit = try {
                        mapObject.queryRenderedFeatures(
                            mapObject.projection.toScreenLocation(point),
                            WIFI_POINTS_LAYER, CELL_POINTS_LAYER, BLE_POINTS_LAYER
                        ).firstNotNullOfOrNull { feature -> feature.getStringProperty("pointId")?.toLongOrNull() }
                    } catch (_: Exception) { null }
                    if (hit != null) {
                        onRadioPointTap?.invoke(hit)
                        true
                    } else false
                }
            }
        }
    }

    fun setDarkMode(dark: Boolean) {
        if (darkMode == dark) return
        darkMode = dark
        if (map != null) applyStyle()
    }

    fun setShowRadioPoints(show: Boolean) {
        if (showRadioPoints == show) return
        showRadioPoints = show
        renderRadioPoints()
    }

    fun setOfflineMode(offline: Boolean) {
        if (offlineMode == offline) return
        offlineMode = offline
        if (map != null) applyStyle()
    }

    private fun applyStyle() {
        val mapObject = map ?: return
        mapReady = false
        val background = if (darkMode) "#101318" else "#F5F3EE"
        val offlineStyle = """{"version":8,"name":"Clear GPS private offline canvas","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$background"}}]}"""
        val builder = if (offlineMode) Style.Builder().fromJson(offlineStyle) else
            Style.Builder().fromUri(if (darkMode) DARK_STYLE_URI else LIGHT_STYLE_URI)
        mapObject.setStyle(builder) { style ->
            installLayers(style)
            mapReady = true
            renderAll()
            if (!cameraInitialized) {
                val start = primaryLocation
                    ?: calculatedLocation
                    ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
                    ?: radioPoints.firstOrNull()?.let { MapCoordinate(it.latitude!!, it.longitude!!) }
                if (start != null) {
                    mapObject.cameraPosition = CameraPosition.Builder().target(LatLng(start.latitude, start.longitude)).zoom(14.0).build()
                    cameraInitialized = true
                } else {
                    mapObject.cameraPosition = CameraPosition.Builder().target(LatLng(20.0, 0.0)).zoom(2.2).build()
                }
            }
        }
    }

    fun setTapMode(mode: MapTapMode) { tapMode = mode }

    fun updatePrimary(location: Location?) {
        val coordinate = location?.let { MapCoordinate(it.latitude, it.longitude) }
        val accuracy = location?.takeIf {
            it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy > 0f
        }?.accuracy?.toDouble()
        updatePrimary(coordinate, accuracy)
    }

    fun updatePrimary(coordinate: MapCoordinate?, accuracyMeters: Double?) {
        val previousCoordinate = primaryLocation
        val previousTargetAccuracy = primaryAccuracyMeters
        primaryLocation = coordinate
        val targetAccuracy = if (coordinate != null) {
            accuracyMeters?.takeIf { it.isFinite() && it > 0.0 }
                ?.coerceIn(1.0, MAX_ACCURACY_RADIUS_METERS)
        } else null
        primaryAccuracyMeters = targetAccuracy

        if (!cameraInitialized && mapReady && coordinate != null) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coordinate.latitude, coordinate.longitude), 14.0))
            cameraInitialized = true
        }

        when {
            coordinate == null || targetAccuracy == null -> {
                cancelAccuracyAnimation()
                displayedAccuracyMeters = null
                updateAccuracySource()
            }
            displayedAccuracyMeters == null || previousTargetAccuracy == null || previousCoordinate == null -> {
                // First good fix: show its actual accuracy immediately, without a misleading grow-in.
                cancelAccuracyAnimation()
                displayedAccuracyMeters = targetAccuracy
                updateAccuracySource()
            }
            abs(targetAccuracy - previousTargetAccuracy) >= ACCURACY_ANIMATION_THRESHOLD_METERS -> {
                animateAccuracyRadius(targetAccuracy)
            }
            accuracyAnimator?.isRunning != true -> {
                // Track small changes too, so the ring never gets stuck on a stale radius.
                val changed = displayedAccuracyMeters != targetAccuracy
                displayedAccuracyMeters = targetAccuracy
                if (changed || previousCoordinate != coordinate) updateAccuracySource()
            }
            previousCoordinate != coordinate -> updateAccuracySource()
        }
        renderPrimaryLocation()
    }

    private fun cancelAccuracyAnimation() {
        val animator = accuracyAnimator
        accuracyAnimator = null
        animator?.cancel()
    }

    private fun animateAccuracyRadius(targetMeters: Double) {
        cancelAccuracyAnimation()
        val startMeters = displayedAccuracyMeters ?: targetMeters
        if (!startMeters.isFinite() || abs(targetMeters - startMeters) < ACCURACY_ANIMATION_THRESHOLD_METERS) {
            displayedAccuracyMeters = targetMeters
            updateAccuracySource()
            return
        }

        val animator = ValueAnimator.ofFloat(startMeters.toFloat(), targetMeters.toFloat()).apply {
            duration = ACCURACY_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (accuracyAnimator === this) {
                    displayedAccuracyMeters = (animation.animatedValue as Float).toDouble()
                        .coerceIn(1.0, MAX_ACCURACY_RADIUS_METERS)
                    updateAccuracySource()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (accuracyAnimator === this@apply) {
                        // Use the most recent target in case a tiny change arrived during animation.
                        displayedAccuracyMeters = primaryAccuracyMeters ?: targetMeters
                        accuracyAnimator = null
                        updateAccuracySource()
                    }
                }
            })
        }
        accuracyAnimator = animator
        animator.start()
    }

    private fun updateAccuracySource() {
        val position = primaryLocation
        val radius = displayedAccuracyMeters
        accuracySource?.setGeoJson(
            if (position != null && radius != null) accuracyCollection(position, radius) else emptyCollection()
        )
        map?.triggerRepaint()
    }

    fun updateManualAnchor(anchor: ManualAnchor?) {
        if (manualAnchor == anchor) return
        manualAnchor = anchor
        renderAnchor()
    }

    fun updateCalculatedLocation(location: MapCoordinate?) {
        if (calculatedLocation == location) return
        calculatedLocation = location
        if (!cameraInitialized && mapReady && location != null) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 14.0))
            cameraInitialized = true
        }
        renderCalculatedLocation()
    }

    fun updateZones(zones: List<SavedRiskZone>, draft: List<ZonePoint>) {
        if (savedZones == zones && draftZone == draft) return
        savedZones = zones.toList()
        draftZone = draft.toList()
        renderZones()
    }

    fun updateRadioPoints(points: List<RadioPointRecord>) {
        radioPoints = points.asSequence().filter {
            it.latitude != null && it.longitude != null &&
                it.latitude!!.isFinite() && it.longitude!!.isFinite() &&
                it.latitude!! in -90.0..90.0 && it.longitude!! in -180.0..180.0
        }.take(maxRadioPoints).toList()
        if (!cameraInitialized && mapReady) {
            val first = radioPoints.firstOrNull()
            if (first != null) {
                map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.latitude!!, first.longitude!!), 14.0))
                cameraInitialized = true
            }
        }
        renderRadioPoints()
    }

    fun focusPrimary() {
        val point = primaryLocation ?: calculatedLocation ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
        if (point != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 16.0))
    }

    fun focus(coordinate: MapCoordinate, zoom: Double = 15.5) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coordinate.latitude, coordinate.longitude), zoom))
    }

    private fun installLayers(style: Style) {
        accuracySource = GeoJsonSource(ACCURACY_SOURCE, emptyCollection())
        locationSource = GeoJsonSource(LOCATION_SOURCE, emptyCollection())
        anchorSource = GeoJsonSource(ANCHOR_SOURCE, emptyCollection())
        calculatedLocationSource = GeoJsonSource(CALCULATED_SOURCE, emptyCollection())
        polygonSource = GeoJsonSource(POLYGON_SOURCE, emptyCollection())
        lineSource = GeoJsonSource(LINE_SOURCE, emptyCollection())
        zonePointsSource = GeoJsonSource(ZONE_POINTS_SOURCE, emptyCollection())
        wifiPointsSource = GeoJsonSource(WIFI_POINTS_SOURCE, emptyCollection())
        cellPointsSource = GeoJsonSource(CELL_POINTS_SOURCE, emptyCollection())
        blePointsSource = GeoJsonSource(BLE_POINTS_SOURCE, emptyCollection())
        listOf(locationSource, calculatedLocationSource, accuracySource, anchorSource, polygonSource, lineSource, zonePointsSource,
            wifiPointsSource, cellPointsSource, blePointsSource).forEach { style.addSource(it!!) }

        style.addLayer(FillLayer(POLYGON_LAYER, POLYGON_SOURCE).withProperties(
            PropertyFactory.fillColor("#E85D75"), PropertyFactory.fillOpacity(0.17f)
        ))
        style.addLayer(LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
            PropertyFactory.lineColor("#D94B68"), PropertyFactory.lineWidth(3.0f), PropertyFactory.lineOpacity(0.95f)
        ))
        style.addLayer(CircleLayer(ZONE_POINTS_LAYER, ZONE_POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(4.5f), PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor("#D63B56"), PropertyFactory.circleStrokeWidth(2f)
        ))
        style.addLayer(CircleLayer(WIFI_POINTS_LAYER, WIFI_POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(6.5f), PropertyFactory.circleColor("#16A34A"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f), PropertyFactory.circleOpacity(0.95f)
        ))
        style.addLayer(CircleLayer(CELL_POINTS_LAYER, CELL_POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#A855F7"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f), PropertyFactory.circleOpacity(0.95f)
        ))
        style.addLayer(CircleLayer(BLE_POINTS_LAYER, BLE_POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#F59E0B"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f), PropertyFactory.circleOpacity(0.95f)
        ))
        style.addLayer(CircleLayer(ANCHOR_HALO_LAYER, ANCHOR_SOURCE).withProperties(
            PropertyFactory.circleRadius(14f), PropertyFactory.circleColor("#F9AB00"), PropertyFactory.circleOpacity(0.22f)
        ))
        style.addLayer(CircleLayer(ANCHOR_DOT_LAYER, ANCHOR_SOURCE).withProperties(
            PropertyFactory.circleRadius(6.5f), PropertyFactory.circleColor("#F9AB00"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)
        ))
        // A translucent geodesic footprint represents horizontal accuracy in metres.
        // The radius is animated as reported accuracy changes; it is not a fixed pixel halo.
        style.addLayer(FillLayer(ACCURACY_LAYER, ACCURACY_SOURCE).withProperties(
            PropertyFactory.fillColor("#4285F4"), PropertyFactory.fillOpacity(0.18f)
        ))
        style.addLayer(LineLayer(ACCURACY_OUTLINE_LAYER, ACCURACY_SOURCE).withProperties(
            PropertyFactory.lineColor("#4285F4"), PropertyFactory.lineWidth(1.75f), PropertyFactory.lineOpacity(0.82f)
        ))
        style.addLayer(CircleLayer(LOCATION_HALO_LAYER, LOCATION_SOURCE).withProperties(
            PropertyFactory.circleRadius(12f), PropertyFactory.circleColor("#4285F4"), PropertyFactory.circleOpacity(0.36f)
        ))
        style.addLayer(CircleLayer(LOCATION_DOT_LAYER, LOCATION_SOURCE).withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)
        ))
        style.addLayer(CircleLayer(CALCULATED_DOT_LAYER, CALCULATED_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#DC2626"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleOpacity(0.98f)
        ))
    }

    private fun renderAll() {
        if (!mapReady) return
        renderPrimaryLayers()
        renderCalculatedLocation()
        renderAnchor()
        renderZones()
        renderRadioPoints()
    }

    private fun renderPrimaryLayers() {
        if (!mapReady) return
        val position = primaryLocation
        locationSource?.setGeoJson(position?.let { pointCollection(it) } ?: emptyCollection())
        accuracySource?.setGeoJson(
            if (position != null) accuracyCollection(position, displayedAccuracyMeters) else emptyCollection()
        )
        map?.triggerRepaint()
    }

    private fun renderPrimaryLocation() {
        if (!mapReady) return
        locationSource?.setGeoJson(primaryLocation?.let { pointCollection(it) } ?: emptyCollection())
        map?.triggerRepaint()
    }

    private fun renderCalculatedLocation() {
        if (!mapReady) return
        calculatedLocationSource?.setGeoJson(calculatedLocation?.let { pointCollection(it) } ?: emptyCollection())
        map?.triggerRepaint()
    }

    private fun renderAnchor() {
        if (!mapReady) return
        anchorSource?.setGeoJson(
            manualAnchor?.let { pointCollection(MapCoordinate(it.latitude, it.longitude)) } ?: emptyCollection()
        )
        map?.triggerRepaint()
    }

    private fun renderZones() {
        if (!mapReady) return
        val polygons = ArrayList<Feature>()
        val zoneLines = ArrayList<Feature>()
        savedZones.filter { it.enabled }.forEach { zone ->
            val points = zone.points.map { Point.fromLngLat(it.longitude, it.latitude) }
            if (points.size >= 3) {
                val ring = points.toMutableList()
                if (ring.first() != ring.last()) ring.add(ring.first())
                polygons.add(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
                zoneLines.add(Feature.fromGeometry(LineString.fromLngLats(ring)))
            } else if (points.size >= 2) {
                zoneLines.add(Feature.fromGeometry(LineString.fromLngLats(points)))
            }
        }
        polygonSource?.setGeoJson(FeatureCollection.fromFeatures(polygons.toTypedArray()))
        val draftPoints = draftZone.map { Point.fromLngLat(it.longitude, it.latitude) }
        if (draftPoints.size >= 2) zoneLines.add(Feature.fromGeometry(LineString.fromLngLats(draftPoints)))
        lineSource?.setGeoJson(FeatureCollection.fromFeatures(zoneLines.toTypedArray()))
        zonePointsSource?.setGeoJson(
            FeatureCollection.fromFeatures(draftPoints.map { Feature.fromGeometry(it) }.toTypedArray())
        )
        map?.triggerRepaint()
    }

    private fun renderRadioPoints() {
        if (!mapReady) return
        fun pointsFor(type: String): FeatureCollection {
            if (!showRadioPoints) return emptyCollection()
            val features = radioPoints.asSequence().filter { it.type == type }
                .map { point ->
                    Feature.fromGeometry(Point.fromLngLat(point.longitude!!, point.latitude!!)).apply {
                        addStringProperty("pointId", point.id.toString())
                    }
                }
                .toList()
            return FeatureCollection.fromFeatures(features.toTypedArray())
        }
        wifiPointsSource?.setGeoJson(pointsFor("wifi"))
        cellPointsSource?.setGeoJson(pointsFor("cell"))
        blePointsSource?.setGeoJson(pointsFor("ble"))
        // GeoJSON updates are asynchronous in MapLibre; request a frame after marker changes.
        map?.triggerRepaint()
    }

    private fun pointCollection(point: MapCoordinate) =
        FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))))

    private fun accuracyCollection(center: MapCoordinate, accuracyMeters: Double?): FeatureCollection {
        val radius = (accuracyMeters ?: return emptyCollection())
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(1.0, MAX_ACCURACY_RADIUS_METERS)
            ?: return emptyCollection()
        val lat0 = Math.toRadians(center.latitude)
        val lon0 = Math.toRadians(center.longitude)
        val angular = radius / EARTH_RADIUS_METERS
        val ring = ArrayList<Point>(ACCURACY_RING_SEGMENTS + 1)
        for (step in 0..ACCURACY_RING_SEGMENTS) {
            val bearing = 2.0 * Math.PI * step / ACCURACY_RING_SEGMENTS
            val lat = kotlin.math.asin(kotlin.math.sin(lat0) * kotlin.math.cos(angular) +
                kotlin.math.cos(lat0) * kotlin.math.sin(angular) * kotlin.math.cos(bearing))
            val lon = lon0 + kotlin.math.atan2(
                kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat0),
                kotlin.math.cos(angular) - kotlin.math.sin(lat0) * kotlin.math.sin(lat)
            )
            ring.add(Point.fromLngLat(((Math.toDegrees(lon) + 540.0) % 360.0) - 180.0, Math.toDegrees(lat)))
        }
        return FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))))
    }

    private fun emptyCollection() = FeatureCollection.fromFeatures(emptyArray<Feature>())

    companion object {
        private const val MAX_MAP_RADIO_POINTS = 1_500
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MAX_ACCURACY_RADIUS_METERS = 100_000.0
        private const val ACCURACY_RING_SEGMENTS = 64
        private const val ACCURACY_ANIMATION_DURATION_MS = 320L
        private const val ACCURACY_ANIMATION_THRESHOLD_METERS = 0.5
        private const val LIGHT_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
        private const val DARK_STYLE_URI = "https://tiles.openfreemap.org/styles/dark"
        private const val LOCATION_SOURCE = "cleargps-location"
        private const val CALCULATED_SOURCE = "cleargps-calculated-location"
        private const val ACCURACY_SOURCE = "cleargps-accuracy"
        private const val ANCHOR_SOURCE = "cleargps-anchor"
        private const val POLYGON_SOURCE = "cleargps-risk-polygon"
        private const val LINE_SOURCE = "cleargps-risk-line"
        private const val ZONE_POINTS_SOURCE = "cleargps-zone-points"
        private const val WIFI_POINTS_SOURCE = "cleargps-wifi-points"
        private const val CELL_POINTS_SOURCE = "cleargps-cell-points"
        private const val BLE_POINTS_SOURCE = "cleargps-ble-points"
        private const val LOCATION_HALO_LAYER = "cleargps-location-halo"
        private const val LOCATION_DOT_LAYER = "cleargps-location-dot"
        private const val CALCULATED_DOT_LAYER = "cleargps-calculated-location-dot"
        private const val ACCURACY_LAYER = "cleargps-accuracy-area"
        private const val ACCURACY_OUTLINE_LAYER = "cleargps-accuracy-outline"
        private const val ANCHOR_HALO_LAYER = "cleargps-anchor-halo"
        private const val ANCHOR_DOT_LAYER = "cleargps-anchor-dot"
        private const val POLYGON_LAYER = "cleargps-risk-polygon-layer"
        private const val LINE_LAYER = "cleargps-risk-line-layer"
        private const val ZONE_POINTS_LAYER = "cleargps-zone-points-layer"
        private const val WIFI_POINTS_LAYER = "cleargps-wifi-points-layer"
        private const val CELL_POINTS_LAYER = "cleargps-cell-points-layer"
        private const val BLE_POINTS_LAYER = "cleargps-ble-points-layer"
    }
}
