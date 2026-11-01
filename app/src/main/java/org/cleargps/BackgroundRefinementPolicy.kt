package org.cleargps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import java.time.Instant
import java.time.ZoneOffset

internal data class BeaconDbRefinementEnvironment(
    val validatedWifi: Boolean,
    val batteryPercent: Int?,
    val batteryTemperatureCelsius: Double?
)

internal object BeaconDbRefinementEnvironmentReader {
    const val POLL_INTERVAL_MILLIS = 120_000L

    @Volatile private var cachedAtElapsedMillis = 0L
    @Volatile private var cached: BeaconDbRefinementEnvironment? = null

    @Synchronized
    fun current(
        context: Context,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime()
    ): BeaconDbRefinementEnvironment {
        val previous = cached
        if (previous != null && nowElapsedMillis >= cachedAtElapsedMillis &&
            nowElapsedMillis - cachedAtElapsedMillis < POLL_INTERVAL_MILLIS
        ) return previous

        val app = context.applicationContext
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else null
        val rawTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperature = rawTemperature.takeIf { it > 0 }?.div(10.0)

        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = runCatching {
            val activeNetwork = connectivity?.activeNetwork
            if (connectivity == null || activeNetwork == null) null
            else connectivity.getNetworkCapabilities(activeNetwork)
        }.getOrNull()
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return BeaconDbRefinementEnvironment(wifi, percent, temperature).also {
            cached = it
            cachedAtElapsedMillis = nowElapsedMillis
        }
    }

    @Synchronized
    fun clearCacheForTests() {
        cached = null
        cachedAtElapsedMillis = 0L
    }
}

internal object NetworkAccessPolicy {
    fun hasValidatedInternet(context: Context): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = runCatching { manager.activeNetwork?.let(manager::getNetworkCapabilities) }
            .getOrNull() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun hasValidatedWifi(context: Context): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = runCatching { manager.activeNetwork?.let(manager::getNetworkCapabilities) }
            .getOrNull() ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

internal object BeaconDbAutoRefinementPolicy {
    const val GLOBAL_REQUEST_INTERVAL_MILLIS = 5L * 60L * 1_000L
    const val MAX_REQUESTS_PER_MINUTE = 10
    const val MAX_WIFI_ACCESS_POINTS = 32
    const val MAX_ACCEPTED_ACCURACY_METERS = 5_000.0
    const val SOURCE_TRUST_WEIGHT = 0.20

    const val SUCCESS_BACKOFF_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val RATE_LIMIT_BACKOFF_MILLIS = 24L * 60L * 60L * 1_000L

    fun canRun(environment: BeaconDbRefinementEnvironment): Boolean =
        environment.validatedWifi &&
            (environment.batteryPercent ?: 0) >= 60 &&
            (environment.batteryTemperatureCelsius ?: Double.POSITIVE_INFINITY) <= 37.0

    fun mayAttempt(
        nowMillis: Long,
        lastGlobalAttemptAtMillis: Long,
        nextAllowedAtMillis: Long
    ): Boolean =
        nowMillis >= lastGlobalAttemptAtMillis &&
            nowMillis - lastGlobalAttemptAtMillis >= GLOBAL_REQUEST_INTERVAL_MILLIS &&
            nowMillis >= nextAllowedAtMillis

    fun nextAllowedAt(
        nowMillis: Long,
        attemptNumber: Int,
        outcome: String
    ): Long {
        val wait = when {
            outcome == "success" -> SUCCESS_BACKOFF_MILLIS
            outcome == "rate_limited" -> RATE_LIMIT_BACKOFF_MILLIS
            attemptNumber <= 1 -> 5L * 60L * 1_000L
            attemptNumber == 2 -> 30L * 60L * 1_000L
            else -> 12L * 60L * 60L * 1_000L
        }
        return nowMillis + wait
    }
}

internal object OpenCellIdLocalDbAutoLookupBudget {
    const val MAX_REQUESTS_PER_UTC_DAY = 10
    const val MIN_REQUEST_INTERVAL_MILLIS = 2L * 60L * 60L * 1_000L +
        30L * 60L * 1_000L

    private const val PREFS = "opencellid_local_database_budget"
    private const val KEY_DAY = "utc_day"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_ATTEMPT = "last_attempt_at"

    @Synchronized
    fun tryReserve(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val storedDay = preferences.getString(KEY_DAY, null)
        val count = if (storedDay == day) preferences.getInt(KEY_COUNT, 0) else 0
        val last = if (storedDay == day) preferences.getLong(KEY_LAST_ATTEMPT, 0L) else 0L
        if (count >= MAX_REQUESTS_PER_UTC_DAY) return false
        if (last > 0L && (nowMillis < last || nowMillis - last < MIN_REQUEST_INTERVAL_MILLIS)) return false

        return preferences.edit()
            .putString(KEY_DAY, day)
            .putInt(KEY_COUNT, count + 1)
            .putLong(KEY_LAST_ATTEMPT, nowMillis)
            .commit()
    }

    fun requestsUsedToday(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        return if (preferences.getString(KEY_DAY, null) == day) {
            preferences.getInt(KEY_COUNT, 0).coerceIn(0, MAX_REQUESTS_PER_UTC_DAY)
        } else 0
    }
}

internal object OpenCellIdLocalDbAutoLookupPolicy {
    const val SUCCESS_BACKOFF_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    const val NOT_FOUND_BACKOFF_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val RATE_LIMIT_BACKOFF_MILLIS = 24L * 60L * 60L * 1_000L

    fun nextAllowedAt(nowMillis: Long, attemptNumber: Int, outcome: String): Long {
        val wait = when (outcome) {
            "found" -> SUCCESS_BACKOFF_MILLIS
            "not_found" -> NOT_FOUND_BACKOFF_MILLIS
            "rate_limited" -> RATE_LIMIT_BACKOFF_MILLIS
            else -> when {
                attemptNumber <= 1 -> 15L * 60L * 1_000L
                attemptNumber == 2 -> 2L * 60L * 60L * 1_000L
                else -> 12L * 60L * 60L * 1_000L
            }
        }
        return nowMillis + wait
    }
}

internal object PassiveRadioCapturePolicy {
    const val PASSIVE_PROVIDER = "passive"
    const val MAX_GPS_ACCURACY_METERS = 10f
    const val MAX_FIX_AGE_MILLIS = 15_000L
    const val WIFI_MIN_DISTANCE_METERS = 30.0
    const val CELL_MIN_DISTANCE_METERS = 200.0
    const val BLE_MIN_DISTANCE_METERS = 30.0
    const val WIFI_MIN_RSSI_DBM = -70
    const val CELL_MIN_RSSI_DBM = -90
    const val BLE_MIN_RSSI_DBM = -75
    const val MAX_PASSIVE_OBSERVATIONS = 500_000

    fun acceptsFix(
        provider: String?,
        isMock: Boolean,
        accuracyMeters: Float,
        ageMillis: Long,
        zoneBlocked: Boolean,
        optionalBackgroundAllowed: Boolean
    ): Boolean =
        provider == android.location.LocationManager.GPS_PROVIDER &&
            !isMock &&
            accuracyMeters.isFinite() && accuracyMeters in 1f..MAX_GPS_ACCURACY_METERS &&
            ageMillis in 0L..MAX_FIX_AGE_MILLIS &&
            !zoneBlocked &&
            optionalBackgroundAllowed

    fun hasMovedEnough(
        latitude: Double,
        longitude: Double,
        previousLatitude: Double?,
        previousLongitude: Double?,
        minimumMeters: Double
    ): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) return false
        val previousLat = previousLatitude ?: return true
        val previousLon = previousLongitude ?: return true
        if (!previousLat.isFinite() || previousLat !in -90.0..90.0 ||
            !previousLon.isFinite() || previousLon !in -180.0..180.0
        ) return true

        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(previousLat)
        val lat2 = Math.toRadians(latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitude - previousLon)
        val a = kotlin.math.sin(deltaLat / 2.0) * kotlin.math.sin(deltaLat / 2.0) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(deltaLon / 2.0) * kotlin.math.sin(deltaLon / 2.0)
        val distance = 2.0 * earthRadius * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        return distance >= minimumMeters.coerceAtLeast(0.0)
    }
}
