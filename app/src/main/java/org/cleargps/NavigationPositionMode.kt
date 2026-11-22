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
        "GPS — основной источник. Пока GPS проходит проверку ClearGPS, параллельно используется оценка по локальной радиобазе Wi‑Fi/сот/Bluetooth: она корректирует GPS через слияние измерений. При недоверенном GPS включается радиопозиционирование. Нужно точное разрешение геолокации."
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
    val bearingDegrees: Double?,
    val speedKmh: Int? = null
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
 * Read ClearGPS's active calculated position first (GPS or radio), then a recent physical
 * GPS coordinate as a map-only fallback. KEY_MOCK_OUTPUT_ACTIVE is intentionally not required:
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
                        ?.takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000_000.0 }
                    val bearing = if (NavigationPositionPolicy.canProvideHeading(source)) {
                        prefs.all[LocationMonitorService.KEY_BEARING]?.toString()?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
                    } else {
                        null
                    }
                    val speed = if (source == "GPS" || source == "OBD2_DEAD_RECKONING") {
                        prefs.getInt(LocationMonitorService.KEY_LAST_OBSERVED_GPS_SPEED_KMH, -1).takeIf { it in 0..350 }
                    } else null
                    return NavigationPositionSnapshot(coordinate, accuracy, source, bearing, speed)
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
                    .toDouble().takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000_000.0 }
                return NavigationPositionSnapshot(coordinate, accuracy, "LAST_TRUSTED", null, null)
            }
        }
    }

    // Prefer a fresh raw GPS fix for display, even before ClearGPS has accepted it.
    // It remains explicitly unverified and is never used as trusted navigation output.
    val observedElapsedNanos = prefs.getLong(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS, 0L)
    val observedAgeNanos = nowNanos - observedElapsedNanos
    val observedLatitude = prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, null)?.toDoubleOrNull()
    val observedLongitude = prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, null)?.toDoubleOrNull()
    val observedCoordinate = if (observedLatitude != null && observedLongitude != null) {
        NavigationCoordinate(observedLatitude, observedLongitude).takeIf { it.isValid() }
    } else null
    val observedAccuracy = prefs.getFloat(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ACCURACY, -1f)
        .toDouble().takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000_000.0 }
    if (observedCoordinate != null && observedElapsedNanos > 0L &&
        observedAgeNanos in 0L..LAST_OBSERVED_GPS_MAX_AGE_NANOS) {
        val observedSpeed = prefs.getInt(LocationMonitorService.KEY_LAST_OBSERVED_GPS_SPEED_KMH, -1).takeIf { it in 0..350 }
        return NavigationPositionSnapshot(observedCoordinate, observedAccuracy, "GPS_UNVERIFIED", null, observedSpeed)
    }

    // Android's network provider can give a coarse but useful map location while GPS is
    // still WAITING. Display it with its real (possibly very large) accuracy and a warning.
    // It is map-only: never accepted as a GPS fix or published as mock output.
    if (mode == NavigationPositionMode.AUTO) {
        val networkElapsedNanos = prefs.getLong(LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_ELAPSED_NANOS, 0L)
        val networkAgeNanos = nowNanos - networkElapsedNanos
        val networkLatitude = prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_LAT, null)?.toDoubleOrNull()
        val networkLongitude = prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_LON, null)?.toDoubleOrNull()
        val networkCoordinate = if (networkLatitude != null && networkLongitude != null) {
            NavigationCoordinate(networkLatitude, networkLongitude).takeIf { it.isValid() }
        } else null
        val networkAccuracy = prefs.getFloat(LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_ACCURACY, -1f)
            .toDouble().takeIf { it.isFinite() && it > 0.0 && it <= 1_000_000_000.0 }
        if (networkCoordinate != null && networkElapsedNanos > 0L &&
            networkAgeNanos in 0L..LAST_OBSERVED_NETWORK_MAX_AGE_NANOS) {
            return NavigationPositionSnapshot(networkCoordinate, networkAccuracy, "NETWORK_UNVERIFIED", null, null)
        }
    }

    // Retain the last physical GPS coordinate as a stale map hint when no fresher fix exists.
    if (observedCoordinate != null && observedElapsedNanos > 0L && observedAgeNanos >= 0L) {
        return NavigationPositionSnapshot(observedCoordinate, observedAccuracy, "GPS_UNVERIFIED_STALE", null, null)
    }
    return null
}

private const val LAST_TRUSTED_GPS_MAX_AGE_NANOS = 120_000_000_000L
private const val LAST_OBSERVED_GPS_MAX_AGE_NANOS = 120_000_000_000L
private const val LAST_OBSERVED_NETWORK_MAX_AGE_NANOS = 600_000_000_000L

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
