package org.cleargps

import android.content.Context
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal data class NavigationCoordinate(
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0
}

/**
 * MapLibre controller dedicated to the clean navigation surface.
 *
 * It intentionally does not reuse ClearGpsMapController: navigation has no radio-observation
 * overlays, zone editing or Cell Intelligence layers.
 */
internal class NavigationMapController {
    private var attachedMapView: MapView? = null
    private var map: MapLibreMap? = null
    private var locationSource: GeoJsonSource? = null
    private var currentPosition: NavigationCoordinate? = null
    private var styleReady = false
    private var cameraInitialized = false

    fun attach(
        mapView: MapView,
        context: Context,
        initialDarkMode: Boolean,
        initialPosition: NavigationCoordinate?
    ) {
        if (attachedMapView === mapView) return
        attachedMapView = mapView
        currentPosition = initialPosition?.takeIf { it.isValid() }
        styleReady = false
        cameraInitialized = false

        context.applicationContext
            .getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ClearGpsPreferences.KEY_NAVIGATION_MAP_ACTIVE, true)
            .apply()

        mapView.getMapAsync { mapObject ->
            if (attachedMapView !== mapView) return@getMapAsync
            map = mapObject
            mapObject.setStyle(
                Style.Builder().fromUri(if (initialDarkMode) DARK_STYLE_URI else LIGHT_STYLE_URI)
            ) { style ->
                if (attachedMapView !== mapView || map !== mapObject) return@setStyle

                val source = GeoJsonSource(LOCATION_SOURCE, emptyFeatureCollection())
                style.addSource(source)
                style.addLayer(
                    CircleLayer(LOCATION_HALO_LAYER, LOCATION_SOURCE).withProperties(
                        PropertyFactory.circleRadius(17f),
                        PropertyFactory.circleColor("#4285F4"),
                        PropertyFactory.circleOpacity(0.20f)
                    )
                )
                style.addLayer(
                    CircleLayer(LOCATION_DOT_LAYER, LOCATION_SOURCE).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#1A73E8"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2.5f)
                    )
                )
                locationSource = source
                styleReady = true
                renderCurrentPosition()

                if (!cameraInitialized) {
                    val target = currentPosition
                        ?.takeIf { it.isValid() }
                        ?.let { LatLng(it.latitude, it.longitude) }
                        ?: LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
                    mapObject.cameraPosition = CameraPosition.Builder()
                        .target(target)
                        .zoom(DEFAULT_ZOOM)
                        .build()
                    cameraInitialized = true
                }
            }
        }
    }

    fun detach(mapView: MapView) {
        if (attachedMapView !== mapView) return
        styleReady = false
        locationSource = null
        map = null
        attachedMapView = null
        contextPreferences(mapView).edit()
            .putBoolean(ClearGpsPreferences.KEY_NAVIGATION_MAP_ACTIVE, false)
            .apply()
    }

    fun updatePosition(position: NavigationCoordinate?) {
        currentPosition = position?.takeIf { it.isValid() }
        renderCurrentPosition()
    }

    fun zoomBy(delta: Double) {
        val mapObject = map ?: return
        if (!styleReady || !delta.isFinite()) return
        val camera = mapObject.cameraPosition
        val nextZoom = (camera.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val target = camera.target
            ?: currentPosition?.takeIf { it.isValid() }?.let {
                LatLng(it.latitude, it.longitude)
            }
            ?: LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
        mapObject.easeCamera(
            CameraUpdateFactory.newLatLngZoom(target, nextZoom),
            CAMERA_ANIMATION_MS
        )
    }

    fun centerOnCurrentPosition(): Boolean {
        val point = currentPosition?.takeIf { it.isValid() } ?: return false
        val mapObject = map ?: return false
        if (!styleReady) return false
        mapObject.easeCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(point.latitude, point.longitude),
                FOLLOW_ZOOM
            ),
            CAMERA_ANIMATION_MS
        )
        return true
    }

    private fun renderCurrentPosition() {
        if (!styleReady) return
        val point = currentPosition?.takeIf { it.isValid() }
        val features: Array<Feature> = if (point == null) {
            emptyArray()
        } else {
            arrayOf(
                Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
            )
        }
        locationSource?.setGeoJson(FeatureCollection.fromFeatures(features))
        map?.triggerRepaint()
    }

    private fun contextPreferences(mapView: MapView) =
        mapView.context.applicationContext.getSharedPreferences(
            ClearGpsPreferences.PREFS,
            Context.MODE_PRIVATE
        )

    private fun emptyFeatureCollection(): FeatureCollection =
        FeatureCollection.fromFeatures(emptyArray())

    private companion object {
        const val LIGHT_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
        const val DARK_STYLE_URI = "https://tiles.openfreemap.org/styles/dark"
        const val LOCATION_SOURCE = "cleargps-navigation-location"
        const val LOCATION_HALO_LAYER = "cleargps-navigation-location-halo"
        const val LOCATION_DOT_LAYER = "cleargps-navigation-location-dot"

        const val DEFAULT_LATITUDE = 60.1699
        const val DEFAULT_LONGITUDE = 24.9384
        const val DEFAULT_ZOOM = 13.2
        const val FOLLOW_ZOOM = 16.0
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 20.0
        const val CAMERA_ANIMATION_MS = 240
    }
}
