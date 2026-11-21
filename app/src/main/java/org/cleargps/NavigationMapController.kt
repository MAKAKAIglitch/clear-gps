package org.cleargps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
 * Native MapLibre presentation inspired by Ferrostar's navigation map:
 * a clean directional puck, a north-up browsing camera, and gesture-aware follow mode.
 *
 * ClearGPS remains the sole source of coordinates. This controller only renders the
 * already-filtered/fused position; it does not request Android GPS or calculate fixes.
 */
internal class NavigationMapController {
    private var attachedMapView: MapView? = null
    private var map: MapLibreMap? = null
    private var haloSource: GeoJsonSource? = null
    private var dotSource: GeoJsonSource? = null
    private var puckSource: GeoJsonSource? = null
    private var currentPosition: NavigationCoordinate? = null
    private var currentBearingDegrees: Double? = null
    private var styleReady = false
    private var cameraInitialized = false
    private var hasCenteredOnFirstPosition = false
    private var isFollowingUser = true
    private var onZoomChanged: ((Double) -> Unit)? = null
    private var cameraIdleListener: MapLibreMap.OnCameraIdleListener? = null
    private var cameraMoveStartedListener: MapLibreMap.OnCameraMoveStartedListener? = null

    fun attach(
        mapView: MapView,
        context: Context,
        initialDarkMode: Boolean,
        initialPosition: NavigationPositionSnapshot?
    ) {
        if (attachedMapView === mapView) return
        attachedMapView = mapView
        currentPosition = initialPosition?.coordinate?.takeIf { it.isValid() }
        currentBearingDegrees = initialPosition?.bearingDegrees
            ?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
        styleReady = false
        cameraInitialized = false
        hasCenteredOnFirstPosition = false
        isFollowingUser = true

        context.applicationContext
            .getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ClearGpsPreferences.KEY_NAVIGATION_MAP_ACTIVE, true)
            .apply()

        mapView.getMapAsync { mapObject ->
            if (attachedMapView !== mapView) return@getMapAsync
            map = mapObject

            cameraIdleListener?.let(mapObject::removeOnCameraIdleListener)
            val idleListener = MapLibreMap.OnCameraIdleListener {
                if (map === mapObject) onZoomChanged?.invoke(mapObject.cameraPosition.zoom)
            }
            cameraIdleListener = idleListener
            mapObject.addOnCameraIdleListener(idleListener)

            cameraMoveStartedListener?.let(mapObject::removeOnCameraMoveStartedListener)
            val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    // Match Ferrostar: a user gesture switches the map to free-camera mode.
                    isFollowingUser = false
                }
            }
            cameraMoveStartedListener = moveStartedListener
            mapObject.addOnCameraMoveStartedListener(moveStartedListener)

            mapObject.setStyle(
                Style.Builder().fromUri(if (initialDarkMode) DARK_STYLE_URI else LIGHT_STYLE_URI)
            ) { style ->
                if (attachedMapView !== mapView || map !== mapObject) return@setStyle

                val newHaloSource = GeoJsonSource(HALO_SOURCE, emptyFeatureCollection())
                val newDotSource = GeoJsonSource(DOT_SOURCE, emptyFeatureCollection())
                val newPuckSource = GeoJsonSource(PUCK_SOURCE, emptyFeatureCollection())
                style.addSource(newHaloSource)
                style.addSource(newDotSource)
                style.addSource(newPuckSource)

                // A soft location halo mirrors the accuracy emphasis in navigation map UIs.
                style.addLayer(
                    CircleLayer(HALO_LAYER, HALO_SOURCE).withProperties(
                        PropertyFactory.circleRadius(22f),
                        PropertyFactory.circleColor("#3583DD"),
                        PropertyFactory.circleOpacity(0.14f)
                    )
                )
                // When no reliable course is available (for example Radio-only), show a dot
                // rather than a fabricated north-facing arrow.
                style.addLayer(
                    CircleLayer(DOT_LAYER, DOT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#3583DD"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2.5f)
                    )
                )

                style.addImage(PUCK_IMAGE, createNavigationPuckBitmap())
                style.addLayer(
                    SymbolLayer(PUCK_LAYER, PUCK_SOURCE).withProperties(
                        PropertyFactory.iconImage(PUCK_IMAGE),
                        PropertyFactory.iconRotate(Expression.get(BEARING_PROPERTY)),
                        PropertyFactory.iconRotationAlignment("map"),
                        PropertyFactory.iconPitchAlignment("map"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )

                haloSource = newHaloSource
                dotSource = newDotSource
                puckSource = newPuckSource
                styleReady = true
                renderCurrentPosition()

                if (!cameraInitialized) {
                    val initialFix = currentPosition?.takeIf { it.isValid() }
                    val target = initialFix
                        ?.let { LatLng(it.latitude, it.longitude) }
                        ?: LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
                    mapObject.cameraPosition = CameraPosition.Builder()
                        .target(target)
                        .zoom(if (initialFix != null) FOLLOW_ZOOM else DEFAULT_ZOOM)
                        .bearing(0.0)
                        .tilt(0.0)
                        .build()
                    hasCenteredOnFirstPosition = initialFix != null
                    cameraInitialized = true
                }
                onZoomChanged?.invoke(mapObject.cameraPosition.zoom)
            }
        }
    }

    fun detach(mapView: MapView) {
        if (attachedMapView !== mapView) return
        styleReady = false
        haloSource = null
        dotSource = null
        puckSource = null
        val oldMap = map
        cameraIdleListener?.let { listener -> oldMap?.removeOnCameraIdleListener(listener) }
        cameraMoveStartedListener?.let { listener -> oldMap?.removeOnCameraMoveStartedListener(listener) }
        cameraIdleListener = null
        cameraMoveStartedListener = null
        map = null
        attachedMapView = null
        contextPreferences(mapView).edit()
            .putBoolean(ClearGpsPreferences.KEY_NAVIGATION_MAP_ACTIVE, false)
            .apply()
    }

    fun updatePosition(position: NavigationPositionSnapshot?) {
        val previousPosition = currentPosition
        currentPosition = position?.coordinate?.takeIf { it.isValid() }
        currentBearingDegrees = position?.bearingDegrees
            ?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
        renderCurrentPosition()

        val point = currentPosition ?: return
        val mapObject = map ?: return
        if (!styleReady) return

        if (!hasCenteredOnFirstPosition) {
            hasCenteredOnFirstPosition = true
            isFollowingUser = true
            moveCameraToUser(point, FOLLOW_ZOOM, animated = true)
        } else if (isFollowingUser && point != previousPosition) {
            // Keep the map centered only while follow mode is active. Manual pan/zoom gestures
            // release the camera, and the user can re-enable following with the location action.
            moveCameraToUser(point, mapObject.cameraPosition.zoom, animated = true)
        }
    }

    fun zoomBy(delta: Double) {
        val mapObject = map ?: return
        if (!styleReady || !delta.isFinite()) return
        val nextZoom = (mapObject.cameraPosition.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        animateZoom(mapObject, nextZoom)
    }

    fun setZoom(zoom: Double) {
        val mapObject = map ?: return
        if (!styleReady || !zoom.isFinite()) return
        val camera = mapObject.cameraPosition
        val target = camera.target
            ?: currentPosition?.takeIf { it.isValid() }?.let {
                LatLng(it.latitude, it.longitude)
            }
            ?: LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
        // Slider drag is continuous: move immediately rather than queueing overlapping animations.
        mapObject.moveCamera(
            CameraUpdateFactory.newLatLngZoom(target, zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        )
    }

    fun currentZoom(): Double =
        map?.cameraPosition?.zoom?.takeIf { it.isFinite() } ?: DEFAULT_ZOOM

    fun setOnZoomChangedListener(listener: ((Double) -> Unit)?) {
        onZoomChanged = listener
        map?.let { listener?.invoke(it.cameraPosition.zoom) }
    }

    private fun animateZoom(mapObject: MapLibreMap, zoom: Double) {
        val camera = mapObject.cameraPosition
        val target = camera.target
            ?: currentPosition?.takeIf { it.isValid() }?.let {
                LatLng(it.latitude, it.longitude)
            }
            ?: LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
        mapObject.easeCamera(
            CameraUpdateFactory.newLatLngZoom(target, zoom),
            CAMERA_ANIMATION_MS
        )
    }

    private fun moveCameraToUser(
        point: NavigationCoordinate,
        zoom: Double,
        animated: Boolean
    ) {
        val mapObject = map ?: return
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
            .bearing(0.0)
            .tilt(0.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (animated) {
            mapObject.easeCamera(update, FOLLOW_CAMERA_ANIMATION_MS)
        } else {
            mapObject.moveCamera(update)
        }
    }

    fun centerOnCurrentPosition(): Boolean {
        val point = currentPosition?.takeIf { it.isValid() } ?: return false
        val mapObject = map ?: return false
        if (!styleReady) return false
        isFollowingUser = true
        hasCenteredOnFirstPosition = true
        moveCameraToUser(point, FOLLOW_ZOOM, animated = true)
        return true
    }

    private fun renderCurrentPosition() {
        if (!styleReady) return
        val point = currentPosition?.takeIf { it.isValid() }
        val feature = point?.let {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
        }
        val haloFeatures = feature?.let { arrayOf(it) } ?: emptyArray()
        haloSource?.setGeoJson(FeatureCollection.fromFeatures(haloFeatures))

        if (feature == null) {
            dotSource?.setGeoJson(emptyFeatureCollection())
            puckSource?.setGeoJson(emptyFeatureCollection())
        } else {
            val bearing = currentBearingDegrees
            if (bearing == null) {
                dotSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
                puckSource?.setGeoJson(emptyFeatureCollection())
            } else {
                dotSource?.setGeoJson(emptyFeatureCollection())
                val properties = JsonObject().apply {
                    addProperty(BEARING_PROPERTY, bearing)
                }
                val puckFeature = Feature.fromGeometry(
                    Point.fromLngLat(point.longitude, point.latitude),
                    properties
                )
                puckSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(puckFeature)))
            }
        }
        map?.triggerRepaint()
    }

    private fun contextPreferences(mapView: MapView) =
        mapView.context.applicationContext.getSharedPreferences(
            ClearGpsPreferences.PREFS,
            Context.MODE_PRIVATE
        )

    private fun emptyFeatureCollection(): FeatureCollection =
        FeatureCollection.fromFeatures(emptyArray())

    private fun createNavigationPuckBitmap(): Bitmap {
        // Draw a north-facing arrow. MapLibre rotates it from the trusted ClearGPS course property.
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(38, 0, 0, 0)
        }
        canvas.drawCircle(center, center, 43f, shadowPaint)

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center - 4f, 35f, whitePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(229, 229, 234)
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
        }
        canvas.drawCircle(center, center - 4f, 34.2f, borderPaint)

        val arrowPath = Path().apply {
            moveTo(center, 17f)
            lineTo(67f, 69f)
            lineTo(center, 57f)
            lineTo(29f, 69f)
            close()
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(53, 131, 221)
            style = Paint.Style.FILL
        }
        canvas.drawPath(arrowPath, arrowPaint)
        return bitmap
    }

    private companion object {
        const val LIGHT_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"
        const val DARK_STYLE_URI = "https://tiles.openfreemap.org/styles/dark"
        const val HALO_SOURCE = "cleargps-navigation-location-halo"
        const val DOT_SOURCE = "cleargps-navigation-location-dot"
        const val PUCK_SOURCE = "cleargps-navigation-location-puck"
        const val HALO_LAYER = "cleargps-navigation-location-halo"
        const val DOT_LAYER = "cleargps-navigation-location-dot"
        const val PUCK_LAYER = "cleargps-navigation-location-puck-symbol"
        const val PUCK_IMAGE = "cleargps-navigation-puck"
        const val BEARING_PROPERTY = "bearing"

        const val DEFAULT_LATITUDE = 60.1699
        const val DEFAULT_LONGITUDE = 24.9384
        const val DEFAULT_ZOOM = 13.2
        const val FOLLOW_ZOOM = 16.0
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 20.0
        const val CAMERA_ANIMATION_MS = 240
        const val FOLLOW_CAMERA_ANIMATION_MS = 360
    }
}
