package org.cleargps

import android.content.Context
import android.location.Location
import org.cleargps.core.ZonePoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
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

data class MapCoordinate(val latitude: Double, val longitude: Double)
enum class MapTapMode { NONE, PLACE_ANCHOR, DRAW_ZONE }

class ClearGpsMapController {
    private var map: MapLibreMap? = null
    private var locationSource: GeoJsonSource? = null
    private var accuracySource: GeoJsonSource? = null
    private var anchorSource: GeoJsonSource? = null
    private var polygonSource: GeoJsonSource? = null
    private var lineSource: GeoJsonSource? = null
    private var zonePointsSource: GeoJsonSource? = null

    private var primaryLocation: MapCoordinate? = null
    private var primaryAccuracyMeters: Double? = null
    private var manualAnchor: ManualAnchor? = null
    private var savedZone: List<ZonePoint> = emptyList()
    private var draftZone: List<ZonePoint> = emptyList()
    private var tapMode: MapTapMode = MapTapMode.NONE
    private var mapReady = false
    private var cameraInitialized = false
    var onMapTap: ((Double, Double) -> Boolean)? = null

    fun attach(mapView: MapView, context: Context) {
        mapView.getMapAsync { mapObject ->
            map = mapObject
            mapObject.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                installLayers(style)
                mapReady = true
                renderAll()
                if (!cameraInitialized) {
                    val startingPoint = primaryLocation ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
                    mapObject.cameraPosition = if (startingPoint != null) {
                        CameraPosition.Builder().target(LatLng(startingPoint.latitude, startingPoint.longitude)).zoom(14.0).build()
                    } else {
                        CameraPosition.Builder().target(LatLng(20.0, 0.0)).zoom(2.2).build()
                    }
                    cameraInitialized = true
                }
                mapObject.addOnMapClickListener { point ->
                    val handler = onMapTap
                    if (tapMode == MapTapMode.NONE || handler == null) false
                    else handler(point.latitude, point.longitude)
                }
            }
        }
    }

    fun setTapMode(mode: MapTapMode) {
        tapMode = mode
    }

    fun updatePrimary(location: Location?) {
        if (location == null) {
            if (primaryLocation == null) return
            primaryLocation = null
            primaryAccuracyMeters = null
        } else {
            primaryLocation = MapCoordinate(location.latitude, location.longitude)
            primaryAccuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        }
        renderAll()
    }

    fun updatePrimary(coordinate: MapCoordinate?, accuracyMeters: Double?) {
        primaryLocation = coordinate
        primaryAccuracyMeters = accuracyMeters
        renderAll()
    }

    fun updateManualAnchor(anchor: ManualAnchor?) {
        manualAnchor = anchor
        renderAll()
    }

    fun updateZone(saved: List<ZonePoint>, draft: List<ZonePoint>) {
        savedZone = saved.toList()
        draftZone = draft.toList()
        renderAll()
    }

    fun focusPrimary() {
        val point = primaryLocation ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
        if (point != null) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 16.0))
        }
    }

    fun focus(coordinate: MapCoordinate, zoom: Double = 15.5) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coordinate.latitude, coordinate.longitude), zoom))
    }

    private fun installLayers(style: Style) {
        accuracySource = GeoJsonSource(ACCURACY_SOURCE, emptyCollection())
        locationSource = GeoJsonSource(LOCATION_SOURCE, emptyCollection())
        anchorSource = GeoJsonSource(ANCHOR_SOURCE, emptyCollection())
        polygonSource = GeoJsonSource(POLYGON_SOURCE, emptyCollection())
        lineSource = GeoJsonSource(LINE_SOURCE, emptyCollection())
        zonePointsSource = GeoJsonSource(ZONE_POINTS_SOURCE, emptyCollection())
        style.addSource(accuracySource!!)
        style.addSource(locationSource!!)
        style.addSource(anchorSource!!)
        style.addSource(polygonSource!!)
        style.addSource(lineSource!!)
        style.addSource(zonePointsSource!!)

        style.addLayer(FillLayer(POLYGON_LAYER, POLYGON_SOURCE).withProperties(
            PropertyFactory.fillColor("#E85D75"),
            PropertyFactory.fillOpacity(0.18f)
        ))
        style.addLayer(LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
            PropertyFactory.lineColor("#E85D75"),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineOpacity(0.95f)
        ))
        style.addLayer(CircleLayer(ZONE_POINTS_LAYER, ZONE_POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor("#D63B56"),
            PropertyFactory.circleStrokeWidth(2f)
        ))
        style.addLayer(CircleLayer(ANCHOR_HALO_LAYER, ANCHOR_SOURCE).withProperties(
            PropertyFactory.circleRadius(14f),
            PropertyFactory.circleColor("#F9AB00"),
            PropertyFactory.circleOpacity(0.22f)
        ))
        style.addLayer(CircleLayer(ANCHOR_DOT_LAYER, ANCHOR_SOURCE).withProperties(
            PropertyFactory.circleRadius(6.5f),
            PropertyFactory.circleColor("#F9AB00"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f)
        ))
        style.addLayer(CircleLayer(ACCURACY_LAYER, ACCURACY_SOURCE).withProperties(
            PropertyFactory.circleRadius(26f),
            PropertyFactory.circleColor("#4285F4"),
            PropertyFactory.circleOpacity(0.14f)
        ))
        style.addLayer(CircleLayer(LOCATION_HALO_LAYER, LOCATION_SOURCE).withProperties(
            PropertyFactory.circleRadius(12f),
            PropertyFactory.circleColor("#4285F4"),
            PropertyFactory.circleOpacity(0.36f)
        ))
        style.addLayer(CircleLayer(LOCATION_DOT_LAYER, LOCATION_SOURCE).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f)
        ))
    }

    private fun renderAll() {
        if (!mapReady) return
        val position = primaryLocation
        locationSource?.setGeoJson(position?.let { pointCollection(it) } ?: emptyCollection())
        accuracySource?.setGeoJson(position?.let { pointCollection(it) } ?: emptyCollection())
        anchorSource?.setGeoJson(manualAnchor?.let { pointCollection(MapCoordinate(it.latitude, it.longitude)) } ?: emptyCollection())

        val points = savedZone.map { Point.fromLngLat(it.longitude, it.latitude) }
        val savedPolygon = if (points.size >= 3) {
            val ring = points.toMutableList()
            if (ring.first() != ring.last()) ring.add(ring.first())
            FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))))
        } else emptyCollection()
        polygonSource?.setGeoJson(savedPolygon)

        val combinedLine = savedZone + draftZone
        val linePoints = combinedLine.map { Point.fromLngLat(it.longitude, it.latitude) }
        val lineCollection = if (linePoints.size >= 2) {
            FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(LineString.fromLngLats(linePoints))))
        } else emptyCollection()
        lineSource?.setGeoJson(lineCollection)
        val pointFeatures = draftZone.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }.toTypedArray()
        zonePointsSource?.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
    }

    private fun pointCollection(point: MapCoordinate) =
        FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))))

    private fun emptyCollection() = FeatureCollection.fromFeatures(emptyArray<Feature>())

    companion object {
        private const val LOCATION_SOURCE = "cleargps-location"
        private const val ACCURACY_SOURCE = "cleargps-accuracy"
        private const val ANCHOR_SOURCE = "cleargps-anchor"
        private const val POLYGON_SOURCE = "cleargps-risk-polygon"
        private const val LINE_SOURCE = "cleargps-risk-line"
        private const val ZONE_POINTS_SOURCE = "cleargps-zone-points"
        private const val LOCATION_HALO_LAYER = "cleargps-location-halo"
        private const val LOCATION_DOT_LAYER = "cleargps-location-dot"
        private const val ACCURACY_LAYER = "cleargps-accuracy-ring"
        private const val ANCHOR_HALO_LAYER = "cleargps-anchor-halo"
        private const val ANCHOR_DOT_LAYER = "cleargps-anchor-dot"
        private const val POLYGON_LAYER = "cleargps-risk-polygon-layer"
        private const val LINE_LAYER = "cleargps-risk-line-layer"
        private const val ZONE_POINTS_LAYER = "cleargps-zone-points-layer"

        private val OSM_STYLE_JSON = """
            {
              "version": 8,
              "sources": {
                "osm": {
                  "type": "raster",
                  "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                  "tileSize": 256,
                  "attribution": "© OpenStreetMap contributors"
                }
              },
              "layers": [
                {"id": "background", "type": "background", "paint": {"background-color": "#E8EEF7"}},
                {"id": "osm-raster", "type": "raster", "source": "osm", "minzoom": 0, "maxzoom": 19}
              ]
            }
        """.trimIndent()
    }
}
