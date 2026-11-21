package org.cleargps

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock

/**
 * The navigation surface uses the same position-mode preference and foreground monitor
 * as the full ClearGPS screen. It never computes a second, independent GPS estimate.
 */
internal enum class NavigationPositionMode(
    val key: String,
    val title: String,
    val description: String
) {
    AUTO(
        "auto",
        "Авто",
        "Физический GPS — основной источник; радиорасчёт включается, если GPS не даёт доверенный фикс. Нужно точное разрешение геолокации."
    ),
    GPS_ONLY(
        "gps",
        "Обычный GPS",
        "Координаты берутся из GPS после фильтрации и проверки доверия ClearGPS."
    ),
    RADIO_ONLY(
        "radio",
        "Радио",
        "Координаты рассчитываются ClearGPS по Wi‑Fi, сотовым сетям, Bluetooth и доступной радиобазе. Требуется точное разрешение геолокации; GPS-фиксы в этом режиме не запрашиваются."
    );

    companion object {
        fun fromStoredKey(key: String?): NavigationPositionMode = when (key) {
            "gps", "gps_obd2" -> GPS_ONLY
            "radio", "radio_obd2" -> RADIO_ONLY
            else -> AUTO
        }
    }
}

internal data class NavigationPositionSnapshot(
    val coordinate: NavigationCoordinate,
    val accuracyMeters: Double?,
    val source: String,
    val bearingDegrees: Double?
)

internal object NavigationPositionPolicy {
    fun canProvideHeading(source: String?): Boolean =
        source?.trim()?.uppercase() in setOf(
            "GPS",
            "LAST_TRUSTED",
            "NETWORK",
            "OBD2_DEAD_RECKONING"
        )

    fun acceptsSource(mode: NavigationPositionMode, source: String?): Boolean {
        val normalized = source?.trim()?.uppercase().orEmpty()
        return when (mode) {
            NavigationPositionMode.GPS_ONLY ->
                normalized == "GPS" || normalized == "LAST_TRUSTED" ||
                    normalized == "GPS_UNVERIFIED" || normalized == "OBD2_DEAD_RECKONING"
            NavigationPositionMode.RADIO_ONLY ->
                normalized == "LOCAL_RADIO" || normalized == "LOCAL_RADIO_STALE" || normalized == "BEACONDB"
            NavigationPositionMode.AUTO ->
                normalized in setOf(
                    "GPS",
                    "LAST_TRUSTED",
                    "GPS_UNVERIFIED",
                    "NETWORK",
                    "LOCAL_RADIO",
                    "LOCAL_RADIO_STALE",
                    "OBD2_DEAD_RECKONING",
                    "BEACONDB",
                    "MANUAL_ANCHOR"
                )
        }
    }
}

/**
 * Read the actual coordinate published by LocationMonitorService after ClearGPS's
 * trust/fusion pipeline. KEY_MOCK_OUTPUT_ACTIVE is intentionally not required:
 * the map must work even when Android mock-location publishing is disabled.
 */
internal fun readNavigationPosition(
    prefs: SharedPreferences
): NavigationPositionSnapshot? {
    val mode = NavigationPositionMode.fromStoredKey(
        prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
    )
    val monitoring = prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)
    val hasOutput = prefs.getBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)

    if (monitoring && hasOutput) {
        val source = prefs.getString(LocationMonitorService.KEY_SOURCE, "NONE") ?: "NONE"
        if (NavigationPositionPolicy.acceptsSource(mode, source)) {
            val latitude = prefs.all[LocationMonitorService.KEY_LAT]?.toString()?.toDoubleOrNull()
            val longitude = prefs.all[LocationMonitorService.KEY_LON]?.toString()?.toDoubleOrNull()
            if (latitude != null && longitude != null) {
                val coordinate = NavigationCoordinate(latitude, longitude)
                if (coordinate.isValid()) {
                    val accuracy = prefs.all[LocationMonitorService.KEY_ACCURACY]?.toString()?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
                    val bearing = if (NavigationPositionPolicy.canProvideHeading(source)) {
                        prefs.all[LocationMonitorService.KEY_BEARING]?.toString()?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
                    } else {
                        null
                    }
                    return NavigationPositionSnapshot(coordinate, accuracy, source, bearing)
                }
            }
        }
    }

    // Keep the last genuinely trusted physical GPS fix visible while a new fix is acquired.
    // It expires quickly and is never used in Radio-only mode.
    if (mode == NavigationPositionMode.RADIO_ONLY) return null
    val nowNanos = SystemClock.elapsedRealtimeNanos()
    val trustedElapsedNanos = prefs.getLong(LocationMonitorService.KEY_LAST_TRUSTED_GPS_ELAPSED_NANOS, 0L)
    val trustedAgeNanos = nowNanos - trustedElapsedNanos
    if (trustedElapsedNanos > 0L && trustedAgeNanos in 0L..LAST_TRUSTED_GPS_MAX_AGE_NANOS) {
        val latitude = prefs.getString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT, null)?.toDoubleOrNull()
        val longitude = prefs.getString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LON, null)?.toDoubleOrNull()
        if (latitude != null && longitude != null) {
            val coordinate = NavigationCoordinate(latitude, longitude)
            if (coordinate.isValid()) {
                val accuracy = prefs.getFloat(LocationMonitorService.KEY_LAST_TRUSTED_GPS_ACCURACY, -1f)
                    .toDouble().takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
                return NavigationPositionSnapshot(coordinate, accuracy, "LAST_TRUSTED", null)
            }
        }
    }

    // If no trusted fix exists yet, still place the device on the map using Android's
    // latest valid physical GPS callback. This is display-only and explicitly unverified.
    val observedElapsedNanos = prefs.getLong(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS, 0L)
    val observedAgeNanos = nowNanos - observedElapsedNanos
    if (observedElapsedNanos <= 0L || observedAgeNanos !in 0L..LAST_OBSERVED_GPS_MAX_AGE_NANOS) return null
    val observedLatitude = prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, null)?.toDoubleOrNull() ?: return null
    val observedLongitude = prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, null)?.toDoubleOrNull() ?: return null
    val observedCoordinate = NavigationCoordinate(observedLatitude, observedLongitude)
    if (!observedCoordinate.isValid()) return null
    val observedAccuracy = prefs.getFloat(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ACCURACY, -1f)
        .toDouble().takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
    return NavigationPositionSnapshot(observedCoordinate, observedAccuracy, "GPS_UNVERIFIED", null)
}

private const val LAST_TRUSTED_GPS_MAX_AGE_NANOS = 120_000_000_000L
private const val LAST_OBSERVED_GPS_MAX_AGE_NANOS = 120_000_000_000L

/**
 * Mirrors ClearGpsApp.applyPositionMode(): update the one shared mode, then ask the
 * already-enabled foreground monitor to rebuild its GPS/radio pipeline.
 *
 * Returns an error message only when Android rejects the service start.
 */
internal fun applyNavigationPositionMode(
    context: Context,
    mode: NavigationPositionMode
): String? {
    val prefs = context.applicationContext.getSharedPreferences(
        ClearGpsPreferences.PREFS,
        Context.MODE_PRIVATE
    )
    val oldMode = NavigationPositionMode.fromStoredKey(
        prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
    )
    val edit = prefs.edit().putString(ClearGpsPreferences.KEY_POSITION_MODE, mode.key)
    if (mode == NavigationPositionMode.GPS_ONLY) {
        // Match ClearGpsApp: recording cannot remain armed in GPS-only mode.
        edit.putBoolean(ClearGpsPreferences.KEY_RECORDING, false)
    }
    if (oldMode != mode) {
        // The previous source is no longer the selected source. Hide it immediately while
        // LocationMonitorService tears down the old pipeline and waits for the first new fix.
        edit.putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            .putString(LocationMonitorService.KEY_SOURCE, "NONE")
            .remove(LocationMonitorService.KEY_LAT)
            .remove(LocationMonitorService.KEY_LON)
            .remove(LocationMonitorService.KEY_ACCURACY)
            .remove(LocationMonitorService.KEY_BEARING)
    }
    edit.apply()

    if (!prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) return null

    val intent = Intent(context, LocationMonitorService::class.java)
        .setAction(LocationMonitorService.ACTION_START)
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        null
    } catch (error: Exception) {
        error.message ?: error.javaClass.simpleName
    }
}
