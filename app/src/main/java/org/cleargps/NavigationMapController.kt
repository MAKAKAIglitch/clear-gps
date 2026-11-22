package org.cleargps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.util.Log
import android.os.Looper
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
 * Active guidance switches to a larger, beveled direction arrow inspired by CoMaps' 3D position marker.
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
    private var onRouteTap: ((Int) -> Unit)? = null
    private var onLocationTap: (() -> Unit)? = null
    private var routeHeadingDegrees: Double? = null
    private var activeNavigationRoute: NavigationRouteAlternative? = null
    private var destinationSource: GeoJsonSource? = null
    private var selectedPointSource: GeoJsonSource? = null
    private var selectedMapPoint: NavigationCoordinate? = null
    private var routeSource: GeoJsonSource? = null
    private var routes: List<NavigationRouteAlternative> = emptyList()
    private var selectedRouteIndex = 0
    private var destination: NavigationCoordinate? = null
    private var poiSource: GeoJsonSource? = null
    private val poiRepository = NavigationPoiRepository()
    private val laneRepository = NavigationLaneRepository()
    private val poiHandler = Handler(Looper.getMainLooper())
    private val laneHandler = Handler(Looper.getMainLooper())
    private var poiRefreshRunnable: Runnable? = null
    private var laneRefreshRunnable: Runnable? = null
    private var laneLineSource: GeoJsonSource? = null
    private var laneArrowSource: GeoJsonSource? = null
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
                val routeHitRadiusPx = ROUTE_HIT_RADIUS_DP * mapView.context.resources.displayMetrics.density
                val routeHitBox = RectF(
                    screenPoint.x - routeHitRadiusPx,
                    screenPoint.y - routeHitRadiusPx,
                    screenPoint.x + routeHitRadiusPx,
                    screenPoint.y + routeHitRadiusPx
                )
                val routeFeature = mapObject.queryRenderedFeatures(routeHitBox, *ROUTE_LAYER_IDS).firstOrNull()
                val tappedRouteIndex = routeFeature?.getProperty(ROUTE_INDEX_PROPERTY)
                    ?.let { runCatching { it.asInt }.getOrNull() }
                if (tappedRouteIndex != null && tappedRouteIndex in routes.indices) {
                    onRouteTap?.invoke(tappedRouteIndex)
                    return@OnMapClickListener true
                }
                val properties = poiFeature?.properties()
                val categoryName = properties?.get(POI_CATEGORY_PROPERTY)?.takeIf { !it.isJsonNull }?.asString
                val geometry = poiFeature?.geometry() as? Point
                val category = categoryName
                    ?.let { runCatching { NavigationPoiCategory.valueOf(it) }.getOrNull() }
                    ?: properties?.let(::categoryFromVectorTile)
                if (properties != null && category != null && geometry != null) {
                    val label = properties.get(POI_NAME_PROPERTY)
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.takeIf { it.isNotBlank() }
                        ?: properties.get("name:en")?.takeIf { !it.isJsonNull }?.asString
                        ?: properties.get("name_en")?.takeIf { !it.isJsonNull }?.asString
                    val address = properties.get(POI_ADDRESS_PROPERTY)?.takeIf { !it.isJsonNull }?.asString
                    val id = properties.get(POI_ID_PROPERTY)?.takeIf { !it.isJsonNull }?.asString
                        ?: "tile:${category.name}:${geometry.longitude()}:${geometry.latitude()}"
                    // Prefer the full Overpass record for our own POI markers. For
                    // vector-tile hits, match a nearby OSM record when one was loaded.
                    val loadedPoi = pois.firstOrNull { it.id == id }
                        ?: pois.minByOrNull { poi ->
                            val latMeters = (poi.latitude - geometry.latitude()) * 111_000.0
                            val lonMeters = (poi.longitude - geometry.longitude()) *
                                111_000.0 * kotlin.math.cos(Math.toRadians(geometry.latitude()))
                            kotlin.math.hypot(latMeters, lonMeters)
                        }?.takeIf { poi ->
                            val latMeters = (poi.latitude - geometry.latitude()) * 111_000.0
                            val lonMeters = (poi.longitude - geometry.longitude()) *
                                111_000.0 * kotlin.math.cos(Math.toRadians(geometry.latitude()))
                            kotlin.math.hypot(latMeters, lonMeters) <= 80.0
                        }
                    onPoiTap?.invoke(
                        loadedPoi ?: NavigationPoi(
                            id,
                            label?.takeIf { it.isNotBlank() } ?: category.title,
                            category,
                            geometry.latitude(),
                            geometry.longitude(),
                            address,
                            label?.takeIf { it.isNotBlank() }
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
            val idleListener = MapLibreMap.OnCameraIdleListener { schedulePoiRefresh(); scheduleLaneRefresh() }
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
                val newLaneLineSource = GeoJsonSource(LANE_LINE_SOURCE, emptyFeatureCollection())
                val newLaneArrowSource = GeoJsonSource(LANE_ARROW_SOURCE, emptyFeatureCollection())
                style.addSource(newAccuracySource)
                style.addSource(newDotSource)
                style.addSource(newBearingSource)
                style.addSource(newDestinationSource)
                style.addSource(newSelectedPointSource)
                style.addSource(newRouteSource)
                style.addSource(newPoiSource)
                style.addSource(newLaneLineSource)
                style.addSource(newLaneArrowSource)

                // 3D building footprints from OpenFreeMap's OpenMapTiles vector source.
                val buildings3d = FillExtrusionLayer("cleargps-buildings-3d", "openmaptiles")
                    .apply { setSourceLayer("building") }
                    .withProperties(
                        PropertyFactory.fillExtrusionColor("#14263D"),
                        PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                        PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                        PropertyFactory.fillExtrusionOpacity(0.70f)
                    )
                buildings3d.setMinZoom(14f)
                if (style.getLayer("building") != null) {
                    style.addLayerAbove(buildings3d, "building")
                } else {
                    style.addLayer(buildings3d)
                }

                // Distinct medical buildings use a deeper red than ordinary dark-blue buildings.
                val medicalBuildings = FillExtrusionLayer("cleargps-medical-buildings-3d", "openmaptiles")
                    .apply { setSourceLayer("building") }
                    .withFilter(
                        Expression.any(
                            Expression.eq(Expression.get("class"), Expression.literal("hospital")),
                            Expression.eq(Expression.get("class"), Expression.literal("clinic")),
                            Expression.eq(Expression.get("subclass"), Expression.literal("hospital")),
                            Expression.eq(Expression.get("subclass"), Expression.literal("clinic")),
                            Expression.eq(Expression.get("building"), Expression.literal("hospital")),
                            Expression.eq(Expression.get("building"), Expression.literal("clinic")),
                            Expression.eq(Expression.get("building"), Expression.literal("healthcare")),
                            Expression.eq(Expression.get("amenity"), Expression.literal("hospital")),
                            Expression.eq(Expression.get("amenity"), Expression.literal("clinic")),
                            Expression.eq(Expression.get("healthcare"), Expression.literal("hospital")),
                            Expression.eq(Expression.get("healthcare"), Expression.literal("clinic"))
                        )
                    )
                    .withProperties(
                        PropertyFactory.fillExtrusionColor("#651F32"),
                        PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                        PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                        PropertyFactory.fillExtrusionOpacity(0.88f)
                    )
                medicalBuildings.setMinZoom(14f)
                style.addLayerAbove(medicalBuildings, "cleargps-buildings-3d")

                // Thematic land areas: hospital grounds, sandy beaches and forest cover.
                val hospitalGrounds = FillLayer("cleargps-hospital-grounds", "openmaptiles")
                    .apply { setSourceLayer("landuse") }
                    .withFilter(
                        Expression.eq(Expression.get("class"), Expression.literal("hospital"))
                    )
                    .withProperties(
                        PropertyFactory.fillColor("#7B293B"),
                        PropertyFactory.fillOpacity(0.48f)
                    )
                val beaches = FillLayer("cleargps-beaches", "openmaptiles")
                    .apply { setSourceLayer("landcover") }
                    .withFilter(
                        Expression.any(
                            Expression.eq(Expression.get("class"), Expression.literal("sand")),
                            Expression.eq(Expression.get("class"), Expression.literal("beach"))
                        )
                    )
                    .withProperties(
                        PropertyFactory.fillColor("#C7A875"),
                        PropertyFactory.fillOpacity(0.78f)
                    )
                val forestCover = FillLayer("cleargps-forest-cover", "openmaptiles")
                    .apply { setSourceLayer("landcover") }
                    .withFilter(
                        Expression.any(
                            Expression.eq(Expression.get("class"), Expression.literal("wood")),
                            Expression.eq(Expression.get("class"), Expression.literal("forest"))
                        )
                    )
                    .withProperties(
                        PropertyFactory.fillColor("#183D32"),
                        PropertyFactory.fillOpacity(0.68f)
                    )
                val forestLanduse = FillLayer("cleargps-forest-landuse", "openmaptiles")
                    .apply { setSourceLayer("landuse") }
                    .withFilter(
                        Expression.any(
                            Expression.eq(Expression.get("class"), Expression.literal("wood")),
                            Expression.eq(Expression.get("class"), Expression.literal("forest"))
                        )
                    )
                    .withProperties(
                        PropertyFactory.fillColor("#183D32"),
                        PropertyFactory.fillOpacity(0.58f)
                    )

                // Insert thematic polygons beneath buildings and labels so they never hide map details.
                listOf(hospitalGrounds, beaches, forestCover, forestLanduse).forEach { layer ->
                    if (style.getLayer("building") != null) {
                        style.addLayerBelow(layer, "building")
                    } else {
                        style.addLayer(layer)
                    }
                }

                // Keep water above land-cover overlays, with river/canal strokes above water polygons.
                if (style.getLayer("building") != null) {
                    style.addLayerBelow(waterBodies, "building")
                    style.addLayerBelow(waterways, "building")
                } else {
                    style.addLayer(waterBodies)
                    style.addLayer(waterways)
                }

                // OSM lane separators and turn arrows, below route and navigation overlays.
                val laneLines = LineLayer(LANE_LINES_LAYER, LANE_LINE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#E7EDF5"),
                    PropertyFactory.lineWidth(1.15f),
                    PropertyFactory.lineOpacity(0.66f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")
                ).apply { setMinZoom(LANE_MIN_ZOOM) }
                val laneArrows = SymbolLayer(LANE_ARROWS_LAYER, LANE_ARROW_SOURCE).withProperties(
                    PropertyFactory.textField(Expression.get("glyph")),
                    PropertyFactory.textSize(17f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#152333"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textRotate(Expression.get("bearing")),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textRotationAlignment("map"),
                    PropertyFactory.textPitchAlignment("map")
                ).apply { setMinZoom(LANE_ARROW_MIN_ZOOM) }
                if (style.getLayer("building") != null) {
                    style.addLayerBelow(laneLines, "building")
                    style.addLayerAbove(laneArrows, LANE_LINES_LAYER)
                } else {
                    style.addLayer(laneLines)
                    style.addLayer(laneArrows)
                }

                for (index in 0..2) {
                    style.addLayer(
                        LineLayer(routeLayerId(index), ROUTE_SOURCE)
                            .withFilter(Expression.eq(Expression.get(ROUTE_INDEX_PROPERTY), Expression.literal(index)))
                            .withProperties(
                                PropertyFactory.lineColor(if (index == selectedRouteIndex) "#35A9FF" else "#69788B"),
                                PropertyFactory.lineWidth(if (index == selectedRouteIndex) 7f else 4f),
                                PropertyFactory.lineOpacity(if (index == selectedRouteIndex) 1f else 0.78f),
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

                // Location and map-point circles lie on the map plane, so map pitch foreshortens them naturally.
                style.addLayer(
                    CircleLayer(DOT_SHADOW_LAYER, DOT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(12f),
                        PropertyFactory.circleColor("#000000"),
                        PropertyFactory.circleOpacity(0.20f),
                        PropertyFactory.circleBlur(1f),
                        PropertyFactory.circleTranslate(arrayOf(0f, 1f)),
                        PropertyFactory.circlePitchAlignment("map"),
                        PropertyFactory.circlePitchScale("map")
                    )
                )
                style.addLayer(
                    CircleLayer(DOT_LAYER, DOT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleColor("#3583DD"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circlePitchAlignment("map"),
                        PropertyFactory.circlePitchScale("map")
                    )
                )

                style.addImage(BEARING_IMAGE, createBearingBitmap())
                style.addLayer(
                    SymbolLayer(BEARING_LAYER, BEARING_SOURCE).withProperties(
                        PropertyFactory.iconImage(BEARING_IMAGE),
                        PropertyFactory.iconAnchor("center"),
                        PropertyFactory.iconSize(BEARING_ICON_SIZE),
                        PropertyFactory.iconRotate(Expression.get(BEARING_PROPERTY)),
                        PropertyFactory.iconOffset(arrayOf(0f, 0f)),
                        PropertyFactory.iconRotationAlignment("map"),
                        // Keep the beveled arrow standing toward the viewer while its heading follows the map.
                        PropertyFactory.iconPitchAlignment("viewport"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )

                style.addLayer(
                    CircleLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor("#D94B45"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circlePitchAlignment("map"),
                        PropertyFactory.circlePitchScale("map")
                    )
                )

                // A persistent pin marks the exact coordinate selected by a map tap.
                style.addLayer(
                    CircleLayer(SELECTED_POINT_LAYER, SELECTED_POINT_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor("#E34D59"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circlePitchAlignment("map"),
                        PropertyFactory.circlePitchScale("map")
                    )
                )

                // Category badges are drawn locally and stay readable over the tilted 3D basemap.
                NavigationPoiCategory.values().forEach { category ->
                    style.addImage(poiImageId(category), createPoiMarkerBitmap(category))
                }
                NavigationPoiCategory.values().forEach { category ->
                    style.addLayer(
                        SymbolLayer(poiLayerId(category), POI_SOURCE)
                            .apply { setMinZoom(if (category.isCamera) CAMERA_MIN_ZOOM.toFloat() else MIN_POI_ZOOM.toFloat()) }
                            .withFilter(
                                Expression.eq(
                                    Expression.get(POI_CATEGORY_PROPERTY),
                                    Expression.literal(category.name)
                                )
                            )
                            .withProperties(
                                PropertyFactory.iconImage(poiImageId(category)),
                                PropertyFactory.iconSize(POI_ICON_SIZE),
                                PropertyFactory.iconAnchor("center"),
                                PropertyFactory.iconPitchAlignment("viewport"),
                                PropertyFactory.iconRotationAlignment("viewport"),
                                PropertyFactory.iconAllowOverlap(true),
                                PropertyFactory.iconIgnorePlacement(true)
                            )
                    )
                }

                // OpenFreeMap dark includes the OpenMapTiles vector source but does not
                // render its POI source-layer. Use it as a fallback while Overpass is empty.
                NavigationPoiCategory.values().forEach { category ->
                    style.addLayer(
                        SymbolLayer(nativePoiLayerId(category), "openmaptiles")
                            .apply {
                                setSourceLayer("poi")
                                setMinZoom(NATIVE_POI_MIN_ZOOM)
                            }
                            .withFilter(nativePoiFilter(category))
                            .withProperties(
                                PropertyFactory.iconImage(poiImageId(category)),
                                PropertyFactory.iconSize(POI_ICON_SIZE),
                                PropertyFactory.iconAnchor("center"),
                                PropertyFactory.iconPitchAlignment("viewport"),
                                PropertyFactory.iconRotationAlignment("viewport"),
                                PropertyFactory.iconAllowOverlap(true),
                                PropertyFactory.iconIgnorePlacement(true)
                            )
                    )
                }

                accuracySource = newAccuracySource
                dotSource = newDotSource
                bearingSource = newBearingSource
                destinationSource = newDestinationSource
                selectedPointSource = newSelectedPointSource
                routeSource = newRouteSource
                poiSource = newPoiSource
                laneLineSource = newLaneLineSource
                laneArrowSource = newLaneArrowSource
                styleReady = true
                renderCurrentPosition()
                renderDestination()
                renderSelectedMapPoint()
                renderRoutes()
                renderPois()
                renderLaneData(NavigationLaneRepository.RenderData(emptyFeatureCollection(), emptyFeatureCollection()))

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
                // Routes may arrive before the map style is ready. Re-fit after the
                // initial camera has been installed and the view has completed layout.
                scheduleRouteFit()
                schedulePoiRefresh()
                scheduleLaneRefresh()
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
        laneLineSource = null
        laneArrowSource = null
        laneRefreshRunnable?.let(laneHandler::removeCallbacks)
        laneRefreshRunnable = null
        laneRepository.cancel()
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
        routeHeadingDegrees = if (isNavigationCameraMode && currentPosition != null) {
            currentPosition?.let(::nearestRouteBearing)
        } else null
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

    fun setOnRouteTapListener(listener: ((Int) -> Unit)?) {
        onRouteTap = listener
    }

    fun setOnLocationTapListener(listener: (() -> Unit)?) { onLocationTap = listener }

    fun setOnPoiTapListener(listener: ((NavigationPoi) -> Unit)?) {
        onPoiTap = listener
    }

    private fun schedulePoiRefresh() {
        poiRefreshRunnable?.let(poiHandler::removeCallbacks)
        val task = Runnable {
            val mapObject = map ?: return@Runnable
            if (!styleReady || mapObject.cameraPosition.zoom < CAMERA_MIN_ZOOM) {
                Log.d(POI_LOG_TAG, "POI refresh skipped: styleReady=$styleReady, zoom=${mapObject.cameraPosition.zoom}, cameraMinZoom=$CAMERA_MIN_ZOOM")
                poiRepository.cancel()
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
                if (map === mapObject && styleReady && mapObject.cameraPosition.zoom >= CAMERA_MIN_ZOOM) {
                    val currentBounds = mapObject.projection.visibleRegion.latLngBounds
                    // A slow Overpass response must not repaint markers from an old viewport.
                    pois = loaded.filter { poi ->
                        poi.latitude in currentBounds.latitudeSouth..currentBounds.latitudeNorth &&
                            poi.longitude in currentBounds.longitudeWest..currentBounds.longitudeEast
                    }
                    Log.i(POI_LOG_TAG, "POI viewport result: received=${loaded.size}, visible=${pois.size}, camerasReceived=${loaded.count { it.category.isCamera }}, camerasVisible=${pois.count { it.category.isCamera }}, zoom=${mapObject.cameraPosition.zoom}")
                    renderPois()
                }
            }
        }
        poiRefreshRunnable = task
        poiHandler.postDelayed(task, POI_REFRESH_DEBOUNCE_MS)
    }

    private fun scheduleLaneRefresh() {
        laneRefreshRunnable?.let(laneHandler::removeCallbacks)
        val task = Runnable {
            val mapObject = map ?: return@Runnable
            if (!styleReady || mapObject.cameraPosition.zoom < LANE_MIN_ZOOM) {
                laneRepository.cancel()
                renderLaneData(NavigationLaneRepository.RenderData(emptyFeatureCollection(), emptyFeatureCollection()))
                return@Runnable
            }
            val bounds = mapObject.projection.visibleRegion.latLngBounds
            val requestedViewport = listOf(
                bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast
            ).joinToString(":") { (kotlin.math.round(it * 1000.0) / 1000.0).toString() }
            laneRepository.load(
                bounds.latitudeSouth,
                bounds.longitudeWest,
                bounds.latitudeNorth,
                bounds.longitudeEast
            ) { data ->
                val currentBounds = mapObject.projection.visibleRegion.latLngBounds
                val currentViewport = listOf(
                    currentBounds.latitudeSouth, currentBounds.longitudeWest,
                    currentBounds.latitudeNorth, currentBounds.longitudeEast
                ).joinToString(":") { (kotlin.math.round(it * 1000.0) / 1000.0).toString() }
                if (map === mapObject && styleReady && mapObject.cameraPosition.zoom >= LANE_MIN_ZOOM &&
                    requestedViewport == currentViewport
                ) renderLaneData(data)
            }
        }
        laneRefreshRunnable = task
        laneHandler.postDelayed(task, LANE_REFRESH_DEBOUNCE_MS)
    }

    private fun renderLaneData(data: NavigationLaneRepository.RenderData) {
        if (!styleReady) return
        laneLineSource?.setGeoJson(data.laneLines)
        laneArrowSource?.setGeoJson(data.turnArrows)
        map?.triggerRepaint()
    }

    private fun categoryFromVectorTile(properties: JsonObject): NavigationPoiCategory? {
        val featureClass = properties.get("class")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val subclass = properties.get("subclass")?.takeIf { !it.isJsonNull }?.asString
        return when {
            featureClass == "fuel" -> NavigationPoiCategory.FUEL
            featureClass == "charging_station" -> NavigationPoiCategory.ELECTRIC
            featureClass == "pharmacy" || subclass == "chemist" -> NavigationPoiCategory.PHARMACY
            featureClass == "grocery" || subclass in setOf("supermarket", "convenience", "greengrocer", "deli") ->
                NavigationPoiCategory.GROCERY
            featureClass in setOf("restaurant", "cafe", "fast_food", "bar", "ice_cream") ->
                NavigationPoiCategory.FOOD
            featureClass == "parking" -> NavigationPoiCategory.PARKING
            featureClass == "atm" -> NavigationPoiCategory.ATM
            featureClass == "shop" -> NavigationPoiCategory.SHOP
            else -> null
        }
    }

    private fun renderPois() {
        if (!styleReady) return
        val features = pois.asSequence()
            .map { poi ->
                Feature.fromGeometry(Point.fromLngLat(poi.longitude, poi.latitude)).apply {
                    addStringProperty(POI_ID_PROPERTY, poi.id)
                    poi.label?.takeIf { it.isNotBlank() }?.let {
                        addStringProperty(POI_NAME_PROPERTY, it)
                    }
                    addStringProperty(POI_CATEGORY_PROPERTY, poi.category.name)
                    poi.address?.let { addStringProperty(POI_ADDRESS_PROPERTY, it) }
                }
            }
            .toList()
        poiSource?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        val nativeVisibility = if (pois.none { !it.category.isCamera }) "visible" else "none"
        NavigationPoiCategory.values().forEach { category ->
            map?.style?.getLayer(nativePoiLayerId(category))?.setProperties(
                PropertyFactory.visibility(nativeVisibility)
            )
        }
        Log.d(POI_LOG_TAG, "POI render: features=${features.size}, cameras=${pois.count { it.category.isCamera }}, sourceReady=${poiSource != null}")
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
        activeNavigationRoute = if (active) route else null
        routeHeadingDegrees = if (active) {
            currentPosition?.let(::nearestRouteBearing)
                ?: route?.coordinates?.takeIf { it.size >= 2 }?.let { points ->
                    bearingBetween(points[0], points[1])
                }
        } else null
        map?.style?.getLayer(BEARING_LAYER)?.setProperties(
            PropertyFactory.iconSize(if (active) NAVIGATION_ARROW_ICON_SIZE else BEARING_ICON_SIZE),
            PropertyFactory.iconOffset(arrayOf(0f, 0f))
        )
        renderCurrentPosition()
    }

    /** Focus the next turn and pause automatic recentering until the user recenters manually. */
    fun focusOnManeuver(point: NavigationCoordinate) {
        val mapObject = map ?: return
        if (!styleReady || !point.isValid()) return
        isFollowingUser = false
        val current = mapObject.cameraPosition
        val camera = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(maxOf(current.zoom, 16.5).coerceAtMost(MAX_ZOOM))
            .bearing(current.bearing)
            .tilt(if (isNavigationCameraMode) maxOf(current.tilt, 45.0) else current.tilt)
            .build()
        mapObject.easeCamera(
            CameraUpdateFactory.newCameraPosition(camera),
            CAMERA_ANIMATION_MS
        )
    }

    fun updateDestination(coordinate: NavigationCoordinate?) {
        destination = coordinate?.takeIf { it.isValid() }
        renderDestination()
    }
    fun updateRoutes(items: List<NavigationRouteAlternative>, selectedIndex: Int = selectedRouteIndex) {
        routes = items.take(3)
        selectedRouteIndex = selectedIndex.coerceIn(0, (routes.size - 1).coerceAtLeast(0))
        renderRoutes()
        if (!isNavigationCameraMode) scheduleRouteFit()
    }

    private fun scheduleRouteFit() {
        val mapView = attachedMapView ?: return
        mapView.post {
            if (!isNavigationCameraMode && styleReady) {
                fitRouteInView(routes.getOrNull(selectedRouteIndex))
            }
        }
    }

    /**
     * Frame the complete preview route before guidance starts. Keep the camera
     * north-up and flat here so the route endpoints remain visible together.
     */
    private fun fitRouteInView(route: NavigationRouteAlternative?) {
        val mapObject = map ?: return
        val mapView = attachedMapView ?: return
        if (!styleReady || route == null || route.coordinates.size < 2 ||
            mapView.width <= 0 || mapView.height <= 0
        ) return

        val boundsBuilder = LatLngBounds.Builder()
        route.coordinates.forEach { coordinate ->
            if (coordinate.isValid()) {
                boundsBuilder.include(LatLng(coordinate.latitude, coordinate.longitude))
            }
        }
        val bounds = try {
            boundsBuilder.build()
        } catch (_: IllegalStateException) {
            return
        }
        val density = mapView.resources.displayMetrics.density
        val sidePadding = (32f * density).toInt()
        val topPadding = (112f * density).toInt()
        val bottomPadding = (250f * density).toInt()
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(
            bounds,
            sidePadding,
            topPadding,
            sidePadding,
            bottomPadding
        )
        mapObject.easeCamera(cameraUpdate, ROUTE_FIT_ANIMATION_MS)
    }

    private fun renderRoutes() {
        if (!styleReady) return
        val features = routes.mapIndexed { index, route ->
            Feature.fromGeometry(LineString.fromLngLats(route.coordinates.map {
                Point.fromLngLat(it.longitude, it.latitude)
            })).apply { addNumberProperty(ROUTE_INDEX_PROPERTY, index) }
        }
        routeSource?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        (0..2).forEach { index ->
            map?.style?.getLayer(routeLayerId(index))?.setProperties(
                PropertyFactory.lineColor(if (index == selectedRouteIndex) "#35A9FF" else "#69788B"),
                PropertyFactory.lineWidth(if (index == selectedRouteIndex) 7f else 4f),
                PropertyFactory.lineOpacity(if (index == selectedRouteIndex) 1f else 0.78f)
            )
        }
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
        val currentCamera = mapObject.cameraPosition
        val movementBearing = bearingDegrees?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
        val bearing = if (isNavigationCameraMode) {
            routeHeadingDegrees ?: movementBearing ?: currentCamera.bearing
        } else {
            movementBearing ?: currentCamera.bearing
        }
        val previousTarget = currentCamera.target
        if (previousTarget != null) {
            val previous = NavigationCoordinate(previousTarget.latitude, previousTarget.longitude)
            val samePosition = NavigationGuidanceEngine.distanceMeters(previous, point) < 4.0
            val alreadyIn3d = currentCamera.tilt >= 54.0 &&
                kotlin.math.abs(((currentCamera.bearing - bearing + 540.0) % 360.0) - 180.0) < 2.0
            if (samePosition && alreadyIn3d) return
        }
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

    /**
     * Snap the navigation arrow to the tangent of the nearest route segment.
     * If the user is clearly off-route, use the live GPS course instead.
     */
    private fun nearestRouteBearing(position: NavigationCoordinate): Double? {
        val points = activeNavigationRoute?.coordinates ?: return null
        if (points.size < 2) return null

        val latitudeScale = 111_320.0
        val longitudeScale = latitudeScale * kotlin.math.cos(Math.toRadians(position.latitude))
            .coerceAtLeast(0.01)
        var nearestDistanceMeters = Double.POSITIVE_INFINITY
        var nearestBearing: Double? = null

        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            if (!start.isValid() || !end.isValid()) continue

            val ax = (start.longitude - position.longitude) * longitudeScale
            val ay = (start.latitude - position.latitude) * latitudeScale
            val bx = (end.longitude - position.longitude) * longitudeScale
            val by = (end.latitude - position.latitude) * latitudeScale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared < 0.01) continue

            val fraction = ((-(ax * dx + ay * dy)) / lengthSquared).coerceIn(0.0, 1.0)
            val nearestX = ax + fraction * dx
            val nearestY = ay + fraction * dy
            val distance = kotlin.math.hypot(nearestX, nearestY)
            if (distance < nearestDistanceMeters) {
                nearestDistanceMeters = distance
                nearestBearing = bearingBetween(start, end)
            }
        }

        return nearestBearing?.takeIf { nearestDistanceMeters <= ROUTE_HEADING_SNAP_METERS }
    }

    private fun bearingBetween(start: NavigationCoordinate, end: NavigationCoordinate): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val deltaLongitude = Math.toRadians(end.longitude - start.longitude)
        val y = kotlin.math.sin(deltaLongitude) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(deltaLongitude)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
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

        if (isNavigationCameraMode) {
            // The large directional arrow replaces the browsing-mode blue dot.
            dotSource?.setGeoJson(emptyFeatureCollection())
        } else {
            val dotFeature = Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
            dotSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(dotFeature)))
        }

        val accuracy = currentAccuracyMeters
        accuracySource?.setGeoJson(
            if (accuracy != null && accuracy > ACCURACY_THRESHOLD_METERS) {
                accuracyCollection(point, accuracy)
            } else {
                emptyFeatureCollection()
            }
        )

        val bearing = if (isNavigationCameraMode) {
            routeHeadingDegrees ?: currentBearingDegrees
        } else {
            currentBearingDegrees
        }
        if (bearing == null) {
            bearingSource?.setGeoJson(emptyFeatureCollection())
        } else {
            val properties = JsonObject().apply {
                // The navigation arrow artwork points north; rotate it to the fused course/heading.
                addProperty(BEARING_PROPERTY, (bearing + 360.0) % 360.0)
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

    /**
     * A compact, beveled 3D-style navigation pointer based on the faceted arrow
     * geometry used by CoMaps. It is drawn locally so the marker works offline.
     */
    private fun createBearingBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outline = Path().apply {
            moveTo(32f, 4f)
            lineTo(4f, 65f)
            lineTo(32f, 54f)
            lineTo(60f, 65f)
            close()
        }

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(105, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.translate(0f, 3f)
        canvas.drawPath(outline, shadowPaint)
        canvas.restore()

        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(8, 43, 83)
            style = Paint.Style.FILL
        }
        canvas.drawPath(outline, edgePaint)

        val leftFace = Path().apply {
            moveTo(32f, 7f)
            lineTo(8f, 62f)
            lineTo(32f, 52f)
            close()
        }
        val rightFace = Path().apply {
            moveTo(32f, 7f)
            lineTo(32f, 52f)
            lineTo(56f, 62f)
            close()
        }
        val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                8f, 10f, 32f, 62f,
                android.graphics.Color.rgb(16, 91, 174),
                android.graphics.Color.rgb(5, 54, 116),
                Shader.TileMode.CLAMP
            )
        }
        val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                32f, 8f, 56f, 62f,
                android.graphics.Color.rgb(91, 190, 255),
                android.graphics.Color.rgb(24, 119, 210),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(leftFace, leftPaint)
        canvas.drawPath(rightFace, rightPaint)

        val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(170, 224, 244, 255)
            strokeWidth = 1.3f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(32f, 10f, 32f, 51f, ridgePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(226, 244, 255)
            strokeWidth = 1.8f
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(outline, borderPaint)
        return bitmap
    }

    /**
     * Draw a premium round badge with a category-specific glyph.
     * Generated locally so POI icons need neither network access nor extra runtime libraries.
     */
    private fun createPoiMarkerBitmap(category: NavigationPoiCategory): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val color = android.graphics.Color.parseColor(poiColor(category))

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            setShadowLayer(7f, 0f, 4f, android.graphics.Color.argb(115, 0, 0, 0))
        }
        canvas.drawCircle(48f, 46f, 36f, shadow)

        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                20f, 12f, 76f, 82f,
                lightenPoiColor(color, 0.30f),
                color,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(48f, 45f, 35f, badge)

        val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            this.color = android.graphics.Color.argb(245, 255, 255, 255)
        }
        canvas.drawCircle(48f, 45f, 33.1f, outerRing)

        val innerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f
            this.color = android.graphics.Color.argb(75, 255, 255, 255)
        }
        canvas.drawCircle(48f, 45f, 28.7f, innerRing)

        drawPoiGlyph(canvas, category)
        return bitmap
    }

    private fun lightenPoiColor(color: Int, amount: Float): Int {
        fun channel(value: Int) = (value + (255 - value) * amount).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            android.graphics.Color.alpha(color),
            channel(android.graphics.Color.red(color)),
            channel(android.graphics.Color.green(color)),
            channel(android.graphics.Color.blue(color))
        )
    }

    private fun drawPoiGlyph(canvas: Canvas, category: NavigationPoiCategory) {
        val white = android.graphics.Color.rgb(255, 255, 255)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            style = Paint.Style.FILL
        }

        when (category) {
            NavigationPoiCategory.FUEL -> {
                canvas.drawRoundRect(RectF(32f, 27f, 55f, 68f), 4f, 4f, stroke)
                canvas.drawRoundRect(RectF(37f, 33f, 50f, 44f), 2f, 2f, fineStroke)
                canvas.drawLine(36f, 53f, 51f, 53f, fineStroke)
                val hose = Path().apply {
                    moveTo(55f, 34f)
                    lineTo(62f, 39f)
                    lineTo(62f, 54f)
                    quadTo(62f, 60f, 57f, 60f)
                    lineTo(54f, 60f)
                }
                canvas.drawPath(hose, fineStroke)
                canvas.drawLine(31f, 69f, 57f, 69f, fineStroke)
            }
            NavigationPoiCategory.ELECTRIC -> {
                val bolt = Path().apply {
                    moveTo(52f, 23f)
                    lineTo(35f, 49f)
                    lineTo(46f, 49f)
                    lineTo(42f, 69f)
                    lineTo(62f, 41f)
                    lineTo(50f, 41f)
                    close()
                }
                canvas.drawPath(bolt, fill)
            }
            NavigationPoiCategory.PHARMACY -> {
                val cross = Path().apply {
                    moveTo(43f, 26f)
                    lineTo(53f, 26f)
                    lineTo(53f, 39f)
                    lineTo(66f, 39f)
                    lineTo(66f, 50f)
                    lineTo(53f, 50f)
                    lineTo(53f, 64f)
                    lineTo(43f, 64f)
                    lineTo(43f, 50f)
                    lineTo(30f, 50f)
                    lineTo(30f, 39f)
                    lineTo(43f, 39f)
                    close()
                }
                canvas.drawPath(cross, fill)
            }
            NavigationPoiCategory.GROCERY -> {
                val basket = Path().apply {
                    moveTo(31f, 38f)
                    lineTo(65f, 38f)
                    lineTo(60f, 63f)
                    lineTo(37f, 63f)
                    close()
                }
                canvas.drawPath(basket, stroke)
                canvas.drawLine(36f, 45f, 62f, 45f, fineStroke)
                canvas.drawLine(42f, 42f, 44f, 59f, fineStroke)
                canvas.drawLine(54f, 42f, 52f, 59f, fineStroke)
                canvas.drawLine(36f, 34f, 42f, 38f, fineStroke)
                canvas.drawCircle(41f, 69f, 3f, fill)
                canvas.drawCircle(57f, 69f, 3f, fill)
            }
            NavigationPoiCategory.SHOP -> {
                canvas.drawRoundRect(RectF(31f, 37f, 65f, 68f), 4f, 4f, stroke)
                val handle = Path().apply {
                    moveTo(39f, 39f)
                    lineTo(39f, 34f)
                    quadTo(39f, 25f, 48f, 25f)
                    quadTo(57f, 25f, 57f, 34f)
                    lineTo(57f, 39f)
                }
                canvas.drawPath(handle, fineStroke)
                canvas.drawLine(40f, 48f, 56f, 48f, fineStroke)
            }
            NavigationPoiCategory.FOOD -> {
                canvas.drawLine(36f, 27f, 36f, 43f, fineStroke)
                canvas.drawLine(30f, 27f, 30f, 38f, fineStroke)
                canvas.drawLine(42f, 27f, 42f, 38f, fineStroke)
                canvas.drawLine(30f, 38f, 42f, 38f, fineStroke)
                canvas.drawLine(36f, 43f, 36f, 69f, stroke)
                canvas.drawOval(RectF(51f, 26f, 66f, 47f), fineStroke)
                canvas.drawLine(58.5f, 47f, 58.5f, 69f, stroke)
            }
            NavigationPoiCategory.PARKING -> {
                val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = white
                    textAlign = Paint.Align.CENTER
                    textSize = 52f
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD
                    )
                }
                canvas.drawText("P", 48f, 64f, letter)
            }
            NavigationPoiCategory.ATM -> {
                canvas.drawRoundRect(RectF(30f, 25f, 66f, 68f), 5f, 5f, stroke)
                canvas.drawRoundRect(RectF(37f, 32f, 59f, 48f), 2f, 2f, fineStroke)
                canvas.drawLine(38f, 40f, 58f, 40f, fineStroke)
                canvas.drawCircle(39f, 57f, 2f, fill)
                canvas.drawCircle(48f, 57f, 2f, fill)
                canvas.drawCircle(57f, 57f, 2f, fill)
                canvas.drawLine(40f, 63f, 56f, 63f, fineStroke)
            }
            NavigationPoiCategory.SPEED_CAMERA,
            NavigationPoiCategory.RED_LIGHT_CAMERA,
            NavigationPoiCategory.TRAFFIC_CAMERA -> {
                canvas.drawRoundRect(RectF(25f, 34f, 70f, 62f), 7f, 7f, stroke)
                canvas.drawCircle(48f, 48f, 9f, fineStroke)
                canvas.drawCircle(48f, 48f, 3f, fill)
                canvas.drawRoundRect(RectF(34f, 27f, 46f, 35f), 2f, 2f, fill)
                canvas.drawCircle(63f, 39f, 2.5f, fill)
            }
        }
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
        val ROUTE_LAYER_IDS = (0..2).map { routeLayerId(it) }.toTypedArray()
        const val POI_SOURCE = "cleargps-navigation-pois"
        const val LANE_LINE_SOURCE = "cleargps-osm-lane-lines"
        const val LANE_ARROW_SOURCE = "cleargps-osm-lane-arrows"
        const val LANE_LINES_LAYER = "cleargps-osm-lane-lines-layer"
        const val LANE_ARROWS_LAYER = "cleargps-osm-lane-arrows-layer"
        const val POI_LOG_TAG = "ClearGPS_POI_MAP"
        const val POI_NAME_LAYER_ID = "cleargps-poi-names"
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
        val POI_LAYER_IDS = (
            NavigationPoiCategory.values().flatMap { listOf(poiLayerId(it), nativePoiLayerId(it)) }
            ).toTypedArray()

        fun poiLayerId(category: NavigationPoiCategory) = "cleargps-poi-${category.name.lowercase()}"
        fun nativePoiLayerId(category: NavigationPoiCategory) = "cleargps-poi-native-${category.name.lowercase()}"

        fun nativePoiFilter(category: NavigationPoiCategory): Expression {
            fun classIs(value: String) = Expression.eq(Expression.get("class"), Expression.literal(value))
            fun subclassIs(value: String) = Expression.eq(Expression.get("subclass"), Expression.literal(value))
            val grocerySubclasses = arrayOf(
                subclassIs("supermarket"), subclassIs("convenience"),
                subclassIs("greengrocer"), subclassIs("deli")
            )
            return when (category) {
                NavigationPoiCategory.FUEL -> classIs("fuel")
                NavigationPoiCategory.ELECTRIC -> classIs("charging_station")
                NavigationPoiCategory.PHARMACY -> Expression.any(
                    classIs("pharmacy"), subclassIs("chemist")
                )
                NavigationPoiCategory.GROCERY -> Expression.any(
                    classIs("grocery"), *grocerySubclasses
                )
                NavigationPoiCategory.SHOP -> Expression.all(
                    classIs("shop"),
                    Expression.not(Expression.any(*(grocerySubclasses + subclassIs("chemist"))))
                )
                NavigationPoiCategory.FOOD -> Expression.any(
                    classIs("restaurant"), classIs("cafe"), classIs("fast_food"),
                    classIs("bar"), classIs("ice_cream")
                )
                NavigationPoiCategory.PARKING -> classIs("parking")
                NavigationPoiCategory.ATM -> classIs("atm")
            NavigationPoiCategory.SPEED_CAMERA,
            NavigationPoiCategory.RED_LIGHT_CAMERA,
            NavigationPoiCategory.TRAFFIC_CAMERA ->
                Expression.eq(Expression.get("class"), Expression.literal("__cleargps_camera_not_in_vector_tiles__"))
            }
        }
        fun poiImageId(category: NavigationPoiCategory) = "cleargps-poi-icon-${category.name.lowercase()}"
        fun poiColor(category: NavigationPoiCategory) = when (category) {
            NavigationPoiCategory.FUEL -> "#F59E0B"
            NavigationPoiCategory.ELECTRIC -> "#10B981"
            NavigationPoiCategory.PHARMACY -> "#EF476F"
            NavigationPoiCategory.GROCERY -> "#3B82F6"
            NavigationPoiCategory.SHOP -> "#8B5CF6"
            NavigationPoiCategory.FOOD -> "#F97316"
            NavigationPoiCategory.PARKING -> "#64748B"
            NavigationPoiCategory.ATM -> "#06B6D4"
            NavigationPoiCategory.SPEED_CAMERA -> "#2563EB"
            NavigationPoiCategory.RED_LIGHT_CAMERA -> "#2563EB"
            NavigationPoiCategory.TRAFFIC_CAMERA -> "#2563EB"
        }
        const val POI_ICON_SIZE = 0.60f
        const val NATIVE_POI_MIN_ZOOM = 14f
        const val BEARING_IMAGE = "cleargps-navigation-bearing-image"
        const val BEARING_PROPERTY = "bearing"
        const val BEARING_ICON_SIZE = 0.25f
        const val NAVIGATION_ARROW_ICON_SIZE = 0.9f
        const val BEARING_ICON_OFFSET = -25.5f

        const val DEFAULT_LATITUDE = 60.1699
        const val DEFAULT_LONGITUDE = 24.9384
        const val DEFAULT_ZOOM = 13.2
        const val FOLLOW_ZOOM = 16.0
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 20.0
        const val CAMERA_ANIMATION_MS = 680
        const val FOLLOW_CAMERA_ANIMATION_MS = 520
        const val ROUTE_FIT_ANIMATION_MS = 720
        const val MIN_POI_ZOOM = 12.0
        const val CAMERA_MIN_ZOOM = 9.5
        const val POI_LABEL_MIN_ZOOM = 15.0f
        const val POI_HIT_RADIUS_PX = 30f
        const val ROUTE_HIT_RADIUS_DP = 18f
        const val LOCATION_HIT_RADIUS_DP = 36f
        const val POI_REFRESH_DEBOUNCE_MS = 850L
        const val LANE_MIN_ZOOM = 15.2f
        const val LANE_ARROW_MIN_ZOOM = 16.0f
        const val LANE_REFRESH_DEBOUNCE_MS = 1100L
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MAX_ACCURACY_RADIUS_METERS = 100_000.0
        const val ACCURACY_RING_SEGMENTS = 64
        const val ACCURACY_THRESHOLD_METERS = 50.0
        const val ROUTE_HEADING_SNAP_METERS = 85.0
    }
}
