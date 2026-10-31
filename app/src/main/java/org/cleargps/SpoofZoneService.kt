package org.cleargps

import android.content.Context
import android.os.SystemClock
import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZoneStore
import org.cleargps.core.ZoneGeometry
import org.cleargps.core.ZonePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class SpoofZoneAssessment(
    val insideManualZone: Boolean,
    val nearAirport: Boolean,
    val nearestAirportCode: String? = null,
    val nearestAirportDistanceMeters: Double? = null,
    val airportRadiusMeters: Int = SpoofZoneService.DEFAULT_AIRPORT_RADIUS_METERS,
    val airportCheckAvailable: Boolean = true
) {
    /** Manual zones and configured airport buffers block radio recording/positioning. */
    val blocksRadioData: Boolean get() = insideManualZone || nearAirport || !airportCheckAvailable
    /** Airport proximity is a weak spoof-risk hint, never a hard ZONE_RISK state. */
    val airportRiskScore: Int get() = if (nearAirport) SpoofZoneService.AIRPORT_RISK_SCORE else 0
}

/**
 * Shared manual-zone and airport proximity policy for both radio write and read paths.
 * The airport check is deliberately separate from the hard manual spoof-zone state.
 */
internal class SpoofZoneService(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)

    private data class CachedPolicy(
        val zones: List<SavedRiskZone>,
        val airports: List<AirportRecord>,
        val airportAvoidanceEnabled: Boolean,
        val airportRadiusMeters: Int,
        val airportExclusions: Set<String>
    )

    @Volatile private var cachedPolicy: CachedPolicy? = null

    /** Call once at the start of a scan/fix, then reuse the immutable snapshot for all matches. */
    fun refreshPolicy() {
        val exclusions = prefs.getString(ClearGpsPreferences.KEY_AIRPORT_EXCLUSIONS, "")
            .orEmpty()
            .split(',', ';', '\n')
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()
        cachedPolicy = CachedPolicy(
            zones = ZoneStore.loadAll(appContext).filter { it.enabled },
            airports = AirportCatalogStore.load(appContext).airports,
            airportAvoidanceEnabled = prefs.getBoolean(
                ClearGpsPreferences.KEY_AIRPORT_AVOIDANCE_ENABLED, true
            ),
            airportRadiusMeters = prefs.getInt(
                ClearGpsPreferences.KEY_AIRPORT_RADIUS_METERS, DEFAULT_AIRPORT_RADIUS_METERS
            ).coerceIn(MIN_AIRPORT_RADIUS_METERS, MAX_AIRPORT_RADIUS_METERS),
            airportExclusions = exclusions
        )
    }

    private fun policy(): CachedPolicy = cachedPolicy ?: run {
        refreshPolicy()
        cachedPolicy!!
    }

    fun assess(latitude: Double, longitude: Double, accuracyMeters: Double? = null): SpoofZoneAssessment {
        val current = policy()
        return SpoofZonePolicy.assess(
            point = ZonePoint(latitude, longitude),
            accuracyMeters = accuracyMeters,
            enabledZones = current.zones,
            airports = current.airports,
            airportAvoidanceEnabled = current.airportAvoidanceEnabled,
            airportRadiusMeters = current.airportRadiusMeters,
            airportExclusions = current.airportExclusions
        )
    }

    /**
     * If exclusions are configured but no trustworthy current position is available,
     * background collection fails closed rather than learning an unlocated fingerprint
     * that could have been captured inside a restricted area.
     */
    fun requiresKnownPositionForRadioWrite(): Boolean {
        val current = policy()
        return current.zones.isNotEmpty() || current.airportAvoidanceEnabled
    }

    /**
     * Most recent physical GPS fix that the trust engine accepted as NORMAL.
     * Uses elapsed realtime only; wall-clock changes and mock locations cannot refresh it.
     */
    fun lastTrustedPhysicalFix(maxAgeMillis: Long = LAST_TRUSTED_FIX_MAX_AGE_MILLIS): TrustedPhysicalFix? {
        val measuredAt = prefs.getLong(LocationMonitorService.KEY_LAST_TRUSTED_GPS_ELAPSED_NANOS, 0L)
        val now = SystemClock.elapsedRealtimeNanos()
        if (measuredAt <= 0L || now < measuredAt ||
            now - measuredAt > maxAgeMillis.coerceAtLeast(0L) * 1_000_000L
        ) return null
        val latitude = prefs.getString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT, null)
            ?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LON, null)
            ?.toDoubleOrNull() ?: return null
        val accuracy = prefs.getFloat(LocationMonitorService.KEY_LAST_TRUSTED_GPS_ACCURACY, -1f)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracy.isFinite() || accuracy !in 0f..100_000f
        ) return null
        return TrustedPhysicalFix(latitude, longitude, accuracy.toDouble(), measuredAt)
    }

    fun airportCatalogSnapshot(): AirportCatalogSnapshot = AirportCatalogStore.load(appContext)

    fun refreshAirportCatalog(): AirportCatalogSnapshot = AirportCatalogStore.refresh(appContext)

    companion object {
        const val DEFAULT_AIRPORT_RADIUS_METERS = 2_000
        const val MIN_AIRPORT_RADIUS_METERS = 200
        const val MAX_AIRPORT_RADIUS_METERS = 20_000
        const val AIRPORT_RISK_SCORE = 10
        const val LAST_TRUSTED_FIX_MAX_AGE_MILLIS = 60_000L
    }
}

data class TrustedPhysicalFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val elapsedRealtimeNanos: Long
)

internal object SpoofZonePolicy {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MAX_ACCURACY_EXPANSION_METERS = 500.0

    fun assess(
        point: ZonePoint,
        accuracyMeters: Double?,
        enabledZones: List<SavedRiskZone>,
        airports: List<AirportRecord>,
        airportAvoidanceEnabled: Boolean = true,
        airportRadiusMeters: Int = SpoofZoneService.DEFAULT_AIRPORT_RADIUS_METERS,
        airportExclusions: Set<String> = emptySet()
    ): SpoofZoneAssessment {
        val airportCheckAvailable = !airportAvoidanceEnabled || airports.isNotEmpty()
        if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0
        ) {
            // Invalid coordinates fail closed if a spoof zone or airport buffer is configured.
            return SpoofZoneAssessment(
                insideManualZone = enabledZones.isNotEmpty(),
                nearAirport = airportAvoidanceEnabled && airports.isNotEmpty(),
                airportCheckAvailable = airportCheckAvailable
            )
        }

        val insideManual = enabledZones.any { zone ->
            zone.points.size < 3 || zone.points.any {
                !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                    !it.longitude.isFinite() || it.longitude !in -180.0..180.0
            } || ZoneGeometry.contains(point, zone.points, accuracyMeters)
        }

        val radius = airportRadiusMeters.coerceIn(
            SpoofZoneService.MIN_AIRPORT_RADIUS_METERS,
            SpoofZoneService.MAX_AIRPORT_RADIUS_METERS
        )
        val accuracyExpansion = accuracyMeters
            ?.takeIf { it.isFinite() && it in 0.0..MAX_ACCURACY_EXPANSION_METERS }
            ?: 0.0
        val searchRadiusMeters = radius + accuracyExpansion
        val latitudeMarginDegrees = searchRadiusMeters / 110_574.0
        val cosineLatitude = abs(cos(Math.toRadians(point.latitude)))
        val longitudeMarginDegrees = if (cosineLatitude < 1e-6) {
            180.0
        } else {
            min(180.0, searchRadiusMeters / (111_320.0 * cosineLatitude))
        }
        val nearest = if (airportAvoidanceEnabled) {
            airports.asSequence()
                .filter { airport ->
                    airport.latitude.isFinite() && airport.latitude in -90.0..90.0 &&
                        airport.longitude.isFinite() && airport.longitude in -180.0..180.0 &&
                        airport.allCodes().none { it in airportExclusions } &&
                        abs(airport.latitude - point.latitude) <= latitudeMarginDegrees &&
                        abs(shortestLongitudeDeltaDegrees(airport.longitude - point.longitude)) <= longitudeMarginDegrees
                }
                .map { airport -> airport to distanceMeters(point, ZonePoint(airport.latitude, airport.longitude)) }
                .filter { it.second <= searchRadiusMeters }
                .minByOrNull { it.second }
        } else null
        val nearAirport = nearest != null
        return SpoofZoneAssessment(
            insideManualZone = insideManual,
            nearAirport = nearAirport,
            nearestAirportCode = nearest?.first?.icao?.ifBlank {
                nearest.first.iata.ifBlank { nearest.first.ident }
            }?.takeIf { it.isNotBlank() },
            nearestAirportDistanceMeters = nearest?.second,
            airportRadiusMeters = radius,
            airportCheckAvailable = airportCheckAvailable
        )
    }

    private fun shortestLongitudeDeltaDegrees(delta: Double): Double {
        var normalized = delta % 360.0
        if (normalized > 180.0) normalized -= 360.0
        if (normalized < -180.0) normalized += 360.0
        return normalized
    }

    fun distanceMeters(first: ZonePoint, second: ZonePoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val dLat = lat2 - lat1
        var dLon = Math.toRadians(second.longitude - first.longitude)
        if (dLon > Math.PI) dLon -= 2.0 * Math.PI
        if (dLon < -Math.PI) dLon += 2.0 * Math.PI
        val a = (sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }
}
