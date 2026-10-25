package org.cleargps

import android.content.SharedPreferences
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared, short-lived OBD-II speed sample for the map and foreground location service.
 * The data is volatile in meaning even though SharedPreferences is used for process hand-off:
 * speed is accepted only for a few seconds and never treated as a coordinate.
 */
internal object Obd2SharedState {
    const val KEY_ADAPTER_ADDRESS = "obd2_adapter_address"
    const val KEY_SPEED_KMH = "obd2_vehicle_speed_kmh"
    const val KEY_SPEED_AT = "obd2_vehicle_speed_at"
    const val SPEED_MAX_AGE_MS = 5_000L
    const val MAX_PLAUSIBLE_SPEED_KMH = 250

    fun freshSpeedKmh(
        prefs: SharedPreferences,
        nowMillis: Long = System.currentTimeMillis()
    ): Int? {
        val speed = prefs.getInt(KEY_SPEED_KMH, -1)
        val updatedAt = prefs.getLong(KEY_SPEED_AT, 0L)
        return speed.takeIf {
            it in 0..MAX_PLAUSIBLE_SPEED_KMH &&
                updatedAt > 0L &&
                nowMillis >= updatedAt &&
                nowMillis - updatedAt <= SPEED_MAX_AGE_MS
        }
    }

    fun storeTelemetry(prefs: SharedPreferences, telemetry: Obd2Telemetry) {
        val speed = telemetry.vehicleSpeedKmh
            ?.takeIf { it in 0..MAX_PLAUSIBLE_SPEED_KMH }
        prefs.edit()
            .putInt(KEY_SPEED_KMH, speed ?: -1)
            .putLong(KEY_SPEED_AT, if (speed != null) telemetry.updatedAt else 0L)
            .apply()
    }

    fun clearSpeed(prefs: SharedPreferences) {
        prefs.edit().putInt(KEY_SPEED_KMH, -1).putLong(KEY_SPEED_AT, 0L).apply()
    }
}

internal data class Obd2PredictedPosition(
    val coordinate: MapCoordinate,
    val accuracyMeters: Double,
    val travelledMeters: Double,
    val ageMillis: Long
)

/**
 * A deliberately bounded fallback for a brief GPS outage.
 *
 * OBD-II supplies speed but not heading/coordinates. Prediction is allowed only when a recent
 * GPS fix has a bearing and a fresh, plausible vehicle-speed PID is available. It is disabled
 * when either input is stale or the projected distance would be too large. Radio-only mode
 * must not call this method because it intentionally does not use GPS bearing.
 */
internal object Obd2LocationFusion {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_FIX_AGE_MS = 3_000L
    private const val MAX_FIX_AGE_MS = 30_000L
    private const val MAX_PREDICTED_DISTANCE_METERS = 300.0
    private const val BASE_UNCERTAINTY_GROWTH_METERS_PER_SECOND = 1.5
    private const val DISTANCE_UNCERTAINTY_FACTOR = 0.12

    fun predictFromGps(
        lastFix: MapCoordinate,
        baseAccuracyMeters: Double?,
        bearingDegrees: Double?,
        fixTimeMillis: Long,
        speedKmh: Int?,
        speedUpdatedAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Obd2PredictedPosition? {
        val fixAge = nowMillis - fixTimeMillis
        if (fixAge !in MIN_FIX_AGE_MS..MAX_FIX_AGE_MS) return null
        if (bearingDegrees == null || !bearingDegrees.isFinite() || bearingDegrees !in 0.0..360.0) return null
        if (speedKmh == null || speedKmh !in 1..Obd2SharedState.MAX_PLAUSIBLE_SPEED_KMH) return null
        val speedAge = nowMillis - speedUpdatedAtMillis
        if (speedAge !in 0L..Obd2SharedState.SPEED_MAX_AGE_MS) return null

        val seconds = fixAge / 1_000.0
        val distanceMeters = speedKmh / 3.6 * seconds
        if (!distanceMeters.isFinite() || distanceMeters !in 1.0..MAX_PREDICTED_DISTANCE_METERS) return null

        val lat1 = Math.toRadians(lastFix.latitude)
        val lon1 = Math.toRadians(lastFix.longitude)
        val bearing = Math.toRadians(bearingDegrees % 360.0)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val lat2 = asin(
            (sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        val longitude = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        val coordinate = MapCoordinate(Math.toDegrees(lat2), longitude)
        if (!coordinate.latitude.isFinite() || !coordinate.longitude.isFinite()) return null

        val baseAccuracy = baseAccuracyMeters
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(1_000.0) ?: 25.0
        val estimatedAccuracy = (
            baseAccuracy +
                seconds * BASE_UNCERTAINTY_GROWTH_METERS_PER_SECOND +
                distanceMeters * DISTANCE_UNCERTAINTY_FACTOR
            ).coerceAtMost(2_000.0)

        return Obd2PredictedPosition(
            coordinate = coordinate,
            accuracyMeters = estimatedAccuracy,
            travelledMeters = distanceMeters,
            ageMillis = fixAge
        )
    }
}
