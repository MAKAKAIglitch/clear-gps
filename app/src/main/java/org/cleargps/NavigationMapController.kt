package org.cleargps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal data class NavigationCoordinate(
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0
}

/**
 * Native MapLibre browsing map, following Ferrostar's non-navigation LocationPuck:
 * a blue dot, a real geodesic accuracy footprint, and a small bearing indicator.
 *
 * ClearGPS remains the sole source of coordinates. This controller only renders the
 * already-filtered/fused position; it does not request Android GPS or calculate fixes.
 * The large Ferrostar navigation arrow is intentionally reserved for a future active-route mode.
 */
internal class NavigationMapController {
    private var attachedMapView: MapView? = null
    private var map: MapLibreMap? = null
    private var accuracySource: GeoJsonSource? = null
    private var dotSource: GeoJsonSource? = null
    private var bearingSource: GeoJsonSource? = null
    private var currentPosition: NavigationCoordinate? = null
    private var currentAccuracyMeters: Double? = null
    private var currentBearingDegrees: Double? = null
    private var styleReady = false
    private var cameraInitialized = false
    private var hasCenteredOnFirstPosition = false
    private var isFollowingUser = true
    private var isNavigationCameraMode = false
    private var cameraMoveStartedListener: MapLibreMap.OnCameraMoveStartedListener? = null
    private var mapTapListener: MapLibreMap.OnMapClickListener? = null
    private var onMapTap: ((NavigationCoordinate) -> Unit)? = null
    private var onLocationTap: (() -> Unit)? = null
    private var routeHeadingDegrees: Double? = null
    private var destinationSource: GeoJsonSource? = null
    private var selectedPointSource: GeoJsonSource? = null
    private var selectedMapPoint: NavigationCoordinate? = null
    private var routeSource: GeoJsonSource? = null
    private var routes: List<NavigationRouteAlternative> = emptyList()
    private var destination: NavigationCoordinate? = null
    private var poiSource: GeoJsonSource? = null
    private val poiRepository = NavigationPoiRepository()
    private val poiHandler = Handler(Looper.getMainLooper())
    private var poiRefreshRunnable: Runnable? = null
    private var pois: List<NavigationPoi> = emptyList()
    private var onPoiTap: ((NavigationPoi) -> Unit)? = null
    private var cameraIdleListener: MapLibreMap.OnCameraIdleListener? = null

    fun attach(
        mapView: MapView,
        context: Context,
        initialDarkMode: Boolean,
        initialPosition: NavigationPositionSnapshot?
    ) {
        if (attachedMapView === mapView) return
        attachedMapView = mapView
        currentPosition = initialPosition?.coordinate?.takeIf { it.isValid() }
        currentAccuracyMeters = initialPosition?.accuracyMeters
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

            mapTapListener?.let(mapObject::removeOnMapClickListener)
            val tapListener = MapLibreMap.OnMapClickListener { coordinate ->
                val screenPoint = mapObject.projection.toScreenLocation(coordinate)
                val locationHit = currentPosition?.takeIf { it.isValid() }?.let { position ->
                    val locationScreenPoint = mapObject.projection.toScreenLocation(
                        LatLng(position.latitude, position.longitude)
                    )
                    val dx = screenPoint.x - locationScreenPoint.x
                    val dy = screenPoint.y - locationScreenPoint.y
                    val hitRadiusPx = LOCATION_HIT_RADIUS_DP * mapView.context.resources.displayMetrics.density
                    dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
                } == true
                val hitBox = RectF(screenPoint.x - POI_HIT_RADIUS_PX, screenPoint.y - POI_HIT_RADIUS_PX, screenPoint.x + POI_HIT_RADIUS_PX, screenPoint.y + POI_HIT_RADIUS_PX)
                val poiFeature = mapObject.queryRenderedFeatures(hitBox, *POI_LAYER_IDS).firstOrNull()
                if (locationHit) {
                    onLocationTap?.invoke()
                    return@OnMapClickListener true
                }
                val properties = poiFeature?.properties()
                val categoryName = properties?.get(POI_CATEGORY_PROPERTY)?.asString
                val category = categoryName?.let { runCatching { NavigationPoiCategory.valueOf(it) }.getOrNull() }
                val geometry = poiFeature?.geometry() as? Point
                if (properties != null && category != null && geometry != null) {
                    val address = properties.get(POI_ADDRESS_PROPERTY)?.takeIf { !it.isJsonNull }?.asString
                    onPoiTap?.invoke(
                        NavigationPoi(
                            properties.get(POI_ID_PROPERTY).asString,
                            properties.get(POI_NAME_PROPERTY).asString,
                            category,
                            geometry.latitude(),
                            geometry.longitude(),
                            address
                        )
                    )
                } else {
                    onMapTap?.invoke(NavigationCoordinate(coordinate.latitude, coordinate.longitude))
                }
                true
            }
            mapTapListener = tapListener
            mapObject.addOnMapClickListener(tapListener)

            cameraMoveStartedListener?.let(mapObject::removeOnCameraMoveStartedListener)
            val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    // Match Ferrostar: a user gesture switches the map to free-camera mode.
                    isFollowingUser = false
                }
            }
            cameraMoveStartedListener = moveStartedListener
            mapObject.addOnCameraMoveStartedListener(moveStartedListener)
            cameraIdleListener?.let(mapObject::removeOnCameraIdleListener)
            val idleListener = MapLibreMap.OnCameraIdleListener { schedulePoiRefresh() }
            cameraIdleListener = idleListener
            mapObject.addOnCameraIdleListener(idleListener)

            mapObject.setStyle(
                Style.Builder().fromUri(DARK_STYLE_URI)
            ) { style ->
                if (attachedMapView !== mapView || map !== mapObject) return@setStyle

                val newAccuracySource = GeoJsonSource(ACCURACY_SOURCE, emptyFeatureCollection())
                val newDotSource = GeoJsonSource(DOT_SOURCE, emptyFeatureCollection())
                val newBearingSource = GeoJsonSource(BEARING_SOURCE, emptyFeatureCollection())
                val newDestinationSource = GeoJsonSource(DESTINATION_SOURCE, emptyFeatureCollection())
                val newSelectedPointSource = GeoJsonSource(SELECTED_POINT_SOURCE, emptyFeatureCollection())
                val newRouteSource = GeoJsonSource(ROUTE_SOURCE, emptyFeatureCollection())
                val newPoiSource = GeoJsonSource(POI_SOURCE, emptyFeatureCollection())
                style.addSource(newAccuracySource)
                style.addSource(newDotSource)
                style.addSource(newBearingSource)
                style.addSource(newDestinationSource)
                style.addSource(newSelectedPointSource)
                style.addSource(newRouteSource)
                style.addSource(newPoiSource)

                // 3D building footprints from OpenFreeMap's OpenMapTiles vector source.
                val buildings3d = FillExtrusionLayer("cleargps-buildings-3d", "openmaptiles")
                    .apply { setSourceLayer("building") }
                    .withProperties(
                        PropertyFactory.fillExtrusionColor("#26364A"),
                        PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                        PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                        PropertyFactory.fillExtrusionOpacity(0.88f)
                    )
                buildings3d.setMinZoom(14f)
                if (style.getLayer("building") != null) {
                    style.addLayerAbove(buildings3d, "building")
                } else {
                    style.addLayer(buildings3d)
                }

                NavigationPoiCategory.values().forEach { category ->
                    style.addLayer(
                        CircleLayer(poiLayerId(category), POI_SOURCE)
                            .withFilter(Expression.eq(Expression.get(POI_CATEGORY_PROPERTY), Expression.literal(category.name)))
                            .withProperties(
                                PropertyFactory.circleRadius(7f),
                                PropertyFactory.circleColor(poiColor(category)),
                                PropertyFactory.circleStrokeColor("#FFFFFF"),
                                PropertyFactory.circleStrokeWidth(2f),
                                PropertyFactory.circleOpacity(0.96f)
                            )
                    )
                }
                for (index in 0..2) {
                    style.addLayer(
                        LineLayer(routeLayerId(index), ROUTE_SOURCE)
                            .withFilter(Expression.eq(Expression.get(ROUTE_INDEX_PROPERTY), Expression.literal(index)))
                            .withProperties(
                                PropertyFactory.lineColor(if (index == 0) "#35A9FF" else "#69788B"),
                                PropertyFactory.lineWidth(if (index == 0) 6f else 4f),
                                PropertyFactory.lineOpacity(if (index == 0) 0.96f else 0.78f),
                                PropertyFactory.lineCap("round"),
                                PropertyFactory.lineJoin("round")
                            )
                    )
                }

                // Ferrostar's browsing puck shows its true horizontal uncertainty in metres.
                style.addLayer(
                    FillLayer(ACCURACY_LAYER, ACCURACY_SOURCE).withProperties(
                        PropertyFactory.fillColor("#3583DD"),
                        PropertyFactory.fillOpacity(0.16f)
                    )
                )
                style.addLayer(
                    LineLayer(ACCURACY_OUTLINE_LAYER, ACCURACY_SOURCE).withProperties(
                        PropertyFactory.lineColor("#3583DD"),
                        PropertyFactory.lineWidth(1f),
                        PropertyFactory.lineOpacity(0.8f)
                    )
                )

                // Soft shadow, blue centre dot, white outline, and a small bearing wedge.
                style.addLayer(
                    CircleLayer(DOT_SHADOW_LAYER, DOT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(12f),
                        PropertyFactory.circleColor("#000000"),
                        PropertyFactory.circleOpacity(0.20f),
                        PropertyFactory.circleBlur(1f),
                        PropertyFactory.circleTranslate(arrayOf(0f, 1f))
                    )
                )
                style.addLayer(
                    CircleLayer(DOT_LAYER, DOT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleColor("#3583DD"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f)
                    )
                )

                style.addImage(BEARING_IMAGE, createBearingBitmap())
                style.addLayer(
                    SymbolLayer(BEARING_LAYER, BEARING_SOURCE).withProperties(
                        PropertyFactory.iconImage(BEARING_IMAGE),
                        PropertyFactory.iconAnchor("center"),
                        PropertyFactory.iconSize(BEARING_ICON_SIZE),
                        PropertyFactory.iconRotate(Expression.get(BEARING_PROPERTY)),
                        PropertyFactory.iconOffset(arrayOf(BEARING_ICON_OFFSET, BEARING_ICON_OFFSET)),
                        PropertyFactory.iconRotationAlignment("map"),
                        PropertyFactory.iconPitchAlignment("map"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )

                style.addLayer(
                    CircleLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor("#D94B45"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f)
                    )
                )

                // A persistent pin marks the exact coordinate selected by a map tap.
                style.addLayer(
                    CircleLayer(SELECTED_POINT_LAYER, SELECTED_POINT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor("#E34D59"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f)
                    )
                )

                accuracySource = newAccuracySource
                dotSource = newDotSource
                bearingSource = newBearingSource
                destinationSource = newDestinationSource
                selectedPointSource = newSelectedPointSource
                routeSource = newRouteSource
                poiSource = newPoiSource
                styleReady = true
                renderCurrentPosition()
                renderDestination()
                renderSelectedMapPoint()
                renderRoutes()
                renderPois()

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
                schedulePoiRefresh()
            }
        }
    }

    fun detach(mapView: MapView) {
        if (attachedMapView !== mapView) return
        styleReady = false
        accuracySource = null
        dotSource = null
        bearingSource = null
        destinationSource = null
        selectedPointSource = null
        routeSource = null
        poiSource = null
        poiRefreshRunnable?.let(poiHandler::removeCallbacks)
        poiRefreshRunnable = null
        poiRepository.cancel()
        pois = emptyList()
        onLocationTap = null
        val oldMap = map
        mapTapListener?.let { listener -> oldMap?.removeOnMapClickListener(listener) }
        mapTapListener = null
        cameraMoveStartedListener?.let { listener -> oldMap?.removeOnCameraMoveStartedListener(listener) }
        cameraMoveStartedListener = null
        cameraIdleListener?.let { listener -> oldMap?.removeOnCameraIdleListener(listener) }
        cameraIdleListener = null
        map = null
        attachedMapView = null
        contextPreferences(mapView).edit()
            .putBoolean(ClearGpsPreferences.KEY_NAVIGATION_MAP_ACTIVE, false)
            .apply()
    }

    fun updatePosition(position: NavigationPositionSnapshot?) {
        val previousPosition = currentPosition
        currentPosition = position?.coordinate?.takeIf { it.isValid() }
        currentAccuracyMeters = position?.accuracyMeters
            ?.takeIf { it.isFinite() && it > 0.0 }
        currentBearingDegrees = position?.bearingDegrees
            ?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
        renderCurrentPosition()

        val point = currentPosition ?: return
        if (map == null || !styleReady) return

        if (!hasCenteredOnFirstPosition) {
            hasCenteredOnFirstPosition = true
            isFollowingUser = true
            moveCameraToUser(point, FOLLOW_ZOOM, animated = true)
        } else if (isFollowingUser && point != previousPosition && !isNavigationCameraMode) {
            // Browsing mode remains north-up; active guidance owns the camera heading.
            moveCameraToUser(point, currentZoom(), animated = true)
        }
    }

    fun setOnMapTapListener(listener: ((NavigationCoordinate) -> Unit)?) {
        onMapTap = listener
    }

    fun setOnLocationTapListener(listener: (() -> Unit)?) { onLocationTap = listener }

    fun setOnPoiTapListener(listener: ((NavigationPoi) -> Unit)?) {
        onPoiTap = listener
    }

    private fun schedulePoiRefresh() {
        poiRefreshRunnable?.let(poiHandler::removeCallbacks)
        val task = Runnable {
            val mapObject = map ?: return@Runnable
            if (!styleReady || mapObject.cameraPosition.zoom < MIN_POI_ZOOM) {
                pois = emptyList()
                renderPois()
                return@Runnable
            }
            val bounds = mapObject.projection.visibleRegion.latLngBounds
            poiRepository.load(
                bounds.latitudeSouth,
                bounds.longitudeWest,
                bounds.latitudeNorth,
                bounds.longitudeEast
            ) { loaded ->
                if (map === mapObject && styleReady) {
                    pois = loaded
                    renderPois()
                }
            }
        }
        poiRefreshRunnable = task
        poiHandler.postDelayed(task, POI_REFRESH_DEBOUNCE_MS)
    }

    private fun renderPois() {
        if (!styleReady) return
        val features = pois.asSequence()
            .map { poi ->
                Feature.fromGeometry(Point.fromLngLat(poi.longitude, poi.latitude)).apply {
                    addStringProperty(POI_ID_PROPERTY, poi.id)
                    addStringProperty(POI_NAME_PROPERTY, poi.name)
                    addStringProperty(POI_CATEGORY_PROPERTY, poi.category.name)
                    poi.address?.let { addStringProperty(POI_ADDRESS_PROPERTY, it) }
                }
            }
            .toList()
        poiSource?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        map?.triggerRepaint()
    }

    fun updateSelectedMapPoint(coordinate: NavigationCoordinate?) {
        selectedMapPoint = coordinate?.takeIf { it.isValid() }
        renderSelectedMapPoint()
    }

    private fun renderSelectedMapPoint() {
        if (!styleReady) return
        val point = selectedMapPoint
        selectedPointSource?.setGeoJson(
            point?.let {
                FeatureCollection.fromFeatures(
                    arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
                )
            } ?: emptyFeatureCollection()
        )
        map?.triggerRepaint()
    }

    fun setNavigationActive(active: Boolean, route: NavigationRouteAlternative?) {
        isNavigationCameraMode = active
        routeHeadingDegrees = if (active) route?.coordinates?.takeIf { it.size >= 2 }?.let { points ->
            val start = points[0]; val next = points[1]
            val lat1 = Math.toRadians(start.latitude); val lat2 = Math.toRadians(next.latitude)
            val dLon = Math.toRadians(next.longitude - start.longitude)
            ((Math.toDegrees(kotlin.math.atan2(kotlin.math.sin(dLon) * kotlin.math.cos(lat2), kotlin.math.cos(lat1) * kotlin.math.sin(lat2) - kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon))) + 360.0) % 360.0)
        } else null
        map?.style?.getLayer(BEARING_LAYER)?.setProperties(
            PropertyFactory.iconSize(if (active) 0.9f else BEARING_ICON_SIZE),
            PropertyFactory.iconOffset(if (active) arrayOf(0f, -18f) else arrayOf(BEARING_ICON_OFFSET, BEARING_ICON_OFFSET))
        )
        renderCurrentPosition()
    }

    fun updateDestination(coordinate: NavigationCoordinate?) {
        destination = coordinate?.takeIf { it.isValid() }
        renderDestination()
    }
    fun updateRoutes(items: List<NavigationRouteAlternative>) {
        routes = items.take(3)
        renderRoutes()
    }

    private fun renderRoutes() {
        if (!styleReady) return
        val features = routes.mapIndexed { index, route ->
            Feature.fromGeometry(LineString.fromLngLats(route.coordinates.map {
                Point.fromLngLat(it.longitude, it.latitude)
            })).apply { addNumberProperty(ROUTE_INDEX_PROPERTY, index) }
        }
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        map?.triggerRepaint()
    }

    fun zoomBy(delta: Double) {
        val mapObject = map ?: return
        if (!styleReady || !delta.isFinite()) return
        val nextZoom = (mapObject.cameraPosition.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        animateZoom(mapObject, nextZoom)
    }

    fun currentZoom(): Double =
        map?.cameraPosition?.zoom?.takeIf { it.isFinite() } ?: DEFAULT_ZOOM

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
            .tilt(52.0)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (animated) {
            mapObject.easeCamera(update, FOLLOW_CAMERA_ANIMATION_MS)
        } else {
            mapObject.moveCamera(update)
        }
    }

    fun followNavigationPosition(point: NavigationCoordinate, bearingDegrees: Double?) {
        val mapObject = map ?: return
        if (!styleReady || !point.isValid() || !isFollowingUser) return
        isNavigationCameraMode = true
        val previousTarget = mapObject.cameraPosition.target
        if (previousTarget != null) {
            val previous = NavigationCoordinate(previousTarget.latitude, previousTarget.longitude)
            if (NavigationGuidanceEngine.distanceMeters(previous, point) < 4.0) return
        }
        val bearing = bearingDegrees?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
            ?: mapObject.cameraPosition.bearing
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(maxOf(currentZoom(), 16.5).coerceAtMost(MAX_ZOOM))
            .bearing(bearing)
            .tilt(55.0)
            .build()
        mapObject.easeCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            FOLLOW_CAMERA_ANIMATION_MS
        )
    }

    fun centerOnCurrentPosition(): Boolean {
        val point = currentPosition?.takeIf { it.isValid() } ?: return false
        if (map == null || !styleReady) return false
        isFollowingUser = true
        isNavigationCameraMode = false
        hasCenteredOnFirstPosition = true
        moveCameraToUser(point, FOLLOW_ZOOM, animated = true)
        return true
    }

    private fun renderCurrentPosition() {
        if (!styleReady) return
        val point = currentPosition?.takeIf { it.isValid() }
        if (point == null) {
            accuracySource?.setGeoJson(emptyFeatureCollection())
            dotSource?.setGeoJson(emptyFeatureCollection())
            bearingSource?.setGeoJson(emptyFeatureCollection())
            map?.triggerRepaint()
            return
        }

        val dotFeature = Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        dotSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(dotFeature)))

        val accuracy = currentAccuracyMeters
        accuracySource?.setGeoJson(
            if (accuracy != null && accuracy > ACCURACY_THRESHOLD_METERS) {
                accuracyCollection(point, accuracy)
            } else {
                emptyFeatureCollection()
            }
        )

        val bearing = currentBearingDegrees ?: routeHeadingDegrees
        if (bearing == null) {
            bearingSource?.setGeoJson(emptyFeatureCollection())
        } else {
            val properties = JsonObject().apply {
                // The small triangle artwork points north-east by default, matching Ferrostar.
                addProperty(BEARING_PROPERTY, (bearing + 45.0) % 360.0)
            }
            val feature = Feature.fromGeometry(
                Point.fromLngLat(point.longitude, point.latitude),
                properties
            )
            bearingSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
        }
        map?.triggerRepaint()
    }

    private fun renderDestination() {
        if (!styleReady) return
        val point = destination?.takeIf { it.isValid() }
        destinationSource?.setGeoJson(
            point?.let { FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))) }
                ?: emptyFeatureCollection()
        )
        map?.triggerRepaint()
    }

    private fun accuracyCollection(
        center: NavigationCoordinate,
        accuracyMeters: Double
    ): FeatureCollection {
        val radius = accuracyMeters
            .takeIf { it.isFinite() && it > ACCURACY_THRESHOLD_METERS }
            ?.coerceIn(1.0, MAX_ACCURACY_RADIUS_METERS)
            ?: return emptyFeatureCollection()
        val latitudeRadians = Math.toRadians(center.latitude)
        val longitudeRadians = Math.toRadians(center.longitude)
        val angularDistance = radius / EARTH_RADIUS_METERS
        val ring = ArrayList<Point>(ACCURACY_RING_SEGMENTS + 1)
        for (step in 0..ACCURACY_RING_SEGMENTS) {
            val bearingRadians = 2.0 * Math.PI * step / ACCURACY_RING_SEGMENTS
            val latitude = kotlin.math.asin(
                kotlin.math.sin(latitudeRadians) * kotlin.math.cos(angularDistance) +
                    kotlin.math.cos(latitudeRadians) * kotlin.math.sin(angularDistance) *
                    kotlin.math.cos(bearingRadians)
            )
            val longitude = longitudeRadians + kotlin.math.atan2(
                kotlin.math.sin(bearingRadians) * kotlin.math.sin(angularDistance) *
                    kotlin.math.cos(latitudeRadians),
                kotlin.math.cos(angularDistance) -
                    kotlin.math.sin(latitudeRadians) * kotlin.math.sin(latitude)
            )
            ring.add(
                Point.fromLngLat(
                    ((Math.toDegrees(longitude) + 540.0) % 360.0) - 180.0,
                    Math.toDegrees(latitude)
                )
            )
        }
        return FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
        )
    }

    private fun createBearingBitmap(): Bitmap {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, size.toFloat())
            lineTo(size.toFloat(), 0f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(15, 95, 184)
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, paint)
        return bitmap
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
        const val ACCURACY_SOURCE = "cleargps-navigation-accuracy"
        const val DOT_SOURCE = "cleargps-navigation-dot"
        const val BEARING_SOURCE = "cleargps-navigation-bearing"
        const val DESTINATION_SOURCE = "cleargps-navigation-destination"
        const val SELECTED_POINT_SOURCE = "cleargps-navigation-selected-point"
        const val ROUTE_SOURCE = "cleargps-navigation-routes"
        const val ROUTE_INDEX_PROPERTY = "route_index"
        const val POI_SOURCE = "cleargps-navigation-pois"
        const val ACCURACY_LAYER = "cleargps-navigation-accuracy-fill"
        const val ACCURACY_OUTLINE_LAYER = "cleargps-navigation-accuracy-outline"
        const val DOT_SHADOW_LAYER = "cleargps-navigation-dot-shadow"
        const val DOT_LAYER = "cleargps-navigation-dot"
        const val BEARING_LAYER = "cleargps-navigation-bearing-indicator"
        const val DESTINATION_LAYER = "cleargps-navigation-destination-marker"
        const val SELECTED_POINT_LAYER = "cleargps-navigation-selected-point-marker"
        fun routeLayerId(index: Int) = "cleargps-navigation-route-$index"
        const val POI_ID_PROPERTY = "poi_id"
        const val POI_NAME_PROPERTY = "poi_name"
        const val POI_CATEGORY_PROPERTY = "poi_category"
        const val POI_ADDRESS_PROPERTY = "poi_address"
        val POI_LAYER_IDS = NavigationPoiCategory.values().map { poiLayerId(it) }.toTypedArray()

        fun poiLayerId(category: NavigationPoiCategory) = "cleargps-poi-${category.name.lowercase()}"
        fun poiColor(category: NavigationPoiCategory) = when (category) {
            NavigationPoiCategory.FUEL -> "#E58A24"
            NavigationPoiCategory.ELECTRIC -> "#21A56B"
            NavigationPoiCategory.PHARMACY -> "#D84B58"
            NavigationPoiCategory.GROCERY -> "#4C8FD8"
            NavigationPoiCategory.SHOP -> "#8B68C9"
            NavigationPoiCategory.FOOD -> "#D77B42"
            NavigationPoiCategory.PARKING -> "#657B91"
            NavigationPoiCategory.ATM -> "#329A9A"
        }
        const val BEARING_IMAGE = "cleargps-navigation-bearing-image"
        const val BEARING_PROPERTY = "bearing"
        const val BEARING_ICON_SIZE = 0.25f
        const val BEARING_ICON_OFFSET = -25.5f

        const val DEFAULT_LATITUDE = 60.1699
        const val DEFAULT_LONGITUDE = 24.9384
        const val DEFAULT_ZOOM = 13.2
        const val FOLLOW_ZOOM = 16.0
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 20.0
        const val CAMERA_ANIMATION_MS = 680
        const val FOLLOW_CAMERA_ANIMATION_MS = 520
        const val MIN_POI_ZOOM = 12.0
        const val POI_HIT_RADIUS_PX = 24f
        const val LOCATION_HIT_RADIUS_DP = 36f
        const val POI_REFRESH_DEBOUNCE_MS = 850L
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MAX_ACCURACY_RADIUS_METERS = 100_000.0
        const val ACCURACY_RING_SEGMENTS = 64
        const val ACCURACY_THRESHOLD_METERS = 50.0
    }
}
