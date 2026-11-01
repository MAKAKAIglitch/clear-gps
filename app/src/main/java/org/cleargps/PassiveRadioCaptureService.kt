package org.cleargps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cleargps.core.LocationFilterEngine
import org.cleargps.core.LocationFilterEngine
import java.time.Instant
import java.time.ZoneOffset

/**
 * Observes only locations already requested by other applications through Android's passive
 * provider. This service never asks GPS or the network provider to produce a new fix.
 */
class PassiveRadioCaptureService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var preferences: android.content.SharedPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val zoneService by lazy(LazyThreadSafetyMode.NONE) { SpoofZoneService(applicationContext) }
    private var captureInProgress = false
    private var lastAcceptedLocationElapsedNanos = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        preferences = getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Пассивная запись радиобазы",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP ||
            !preferences.getBoolean(ClearGpsPreferences.KEY_PASSIVE_RADIO_CAPTURE_ENABLED, false)
        ) {
            unregisterPassiveProvider()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit()
                .putBoolean(ClearGpsPreferences.KEY_PASSIVE_RADIO_CAPTURE_ENABLED, false)
                .apply()
            unregisterPassiveProvider()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())
        registerPassiveProvider()
        return START_STICKY
    }

    private fun registerPassiveProvider() {
        unregisterPassiveProvider()
        if (!preferences.getBoolean(ClearGpsPreferences.KEY_PASSIVE_RADIO_CAPTURE_ENABLED, false) ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            // PASSIVE_PROVIDER relays updates initiated by other clients; no GPS fix is requested here.
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                1_000L,
                0f,
                this,
                Looper.getMainLooper()
            )
            DiagnosticLogStore.log(this, "PASSIVE_DB", "Пассивная подписка активна; приложение не запрашивает GPS")
        } catch (error: SecurityException) {
            DiagnosticLogStore.log(this, "PASSIVE_DB", "Нет разрешения для пассивной подписки: ${error.message}", "WARN")
            preferences.edit().putBoolean(ClearGpsPreferences.KEY_PASSIVE_RADIO_CAPTURE_ENABLED, false).apply()
            stopSelf()
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(this, "PASSIVE_DB", "Пассивная подписка недоступна: ${error.javaClass.simpleName}", "WARN")
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (captureInProgress ||
            !preferences.getBoolean(ClearGpsPreferences.KEY_PASSIVE_RADIO_CAPTURE_ENABLED, false) ||
            location.provider != LocationManager.GPS_PROVIDER ||
            LocationFilterEngine.isMockLocation(location) ||
            !location.hasAccuracy() ||
            !location.accuracy.isFinite()
        ) return

        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val ageMillis = if (location.elapsedRealtimeNanos > 0L && nowElapsed >= location.elapsedRealtimeNanos) {
            (nowElapsed - location.elapsedRealtimeNanos) / 1_000_000L
        } else Long.MAX_VALUE

        zoneService.refreshPolicy()
        val zoneBlocked = zoneService.assess(
            location.latitude, location.longitude, location.accuracy.toDouble()
        ).blocksRadioData
        if (!PassiveRadioCapturePolicy.acceptsFix(
                provider = location.provider,
                isMock = LocationFilterEngine.isMockLocation(location),
                accuracyMeters = location.accuracy,
                ageMillis = ageMillis,
                zoneBlocked = zoneBlocked,
                optionalBackgroundAllowed = PowerPolicy.canRunPassiveCapture(this)
            )
        ) return
        if (location.elapsedRealtimeNanos <= lastAcceptedLocationElapsedNanos) return
        lastAcceptedLocationElapsedNanos = location.elapsedRealtimeNanos

        val wifi = preferences.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true) &&
            movedEnough(location, "wifi", PassiveRadioCapturePolicy.WIFI_MIN_DISTANCE_METERS)
        val cells = preferences.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true) &&
            movedEnough(location, "cell", PassiveRadioCapturePolicy.CELL_MIN_DISTANCE_METERS)
        val ble = preferences.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true) &&
            movedEnough(location, "ble", PassiveRadioCapturePolicy.BLE_MIN_DISTANCE_METERS)
        if (!wifi && !cells && !ble) return

        captureInProgress = true
        serviceScope.launch {
            try {
                if (!preferences.getBoolean(ClearGpsPreferences.KEY_PASSIVE_RADIO_CAPTURE_ENABLED, false) ||
                    !PowerPolicy.canRunOptionalBackground(this@PassiveRadioCaptureService)
                ) return@launch

                val dayKey = Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(ZoneOffset.UTC).toLocalDate().toString()
                val database = LocalRadioDatabase(applicationContext)
                val sourceId = withContext(Dispatchers.IO) {
                    database.getOrCreatePassiveCaptureSource(dayKey)
                }
                val capturedTypes = RadioGeolocationRepository(applicationContext)
                    .capturePassiveRadioSnapshot(location, sourceId, wifi, cells, ble)
                if (capturedTypes.isNotEmpty()) {
                    preferences.edit().apply {
                        capturedTypes.forEach { type ->
                            putString(key(type, "latitude"), location.latitude.toString())
                            putString(key(type, "longitude"), location.longitude.toString())
                        }
                    }.apply()
                    DiagnosticLogStore.log(
                        this@PassiveRadioCaptureService,
                        "PASSIVE_DB",
                        "Пассивно записаны типы радио: ${capturedTypes.sorted().joinToString(",")}"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLogStore.log(
                    this@PassiveRadioCaptureService,
                    "PASSIVE_DB",
                    "Ошибка пассивной записи: ${error.message ?: error.javaClass.simpleName}",
                    "WARN"
                )
            } finally {
                captureInProgress = false
            }
        }
    }

    private fun movedEnough(location: Location, type: String, minimumMeters: Double): Boolean {
        val latitude = preferences.getString(key(type, "latitude"), null)?.toDoubleOrNull()
        val longitude = preferences.getString(key(type, "longitude"), null)?.toDoubleOrNull()
        return PassiveRadioCapturePolicy.hasMovedEnough(
            latitude = location.latitude,
            longitude = location.longitude,
            previousLatitude = latitude,
            previousLongitude = longitude,
            minimumMeters = minimumMeters
        )
    }

    private fun unregisterPassiveProvider() {
        runCatching { locationManager.removeUpdates(this) }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Clear GPS · пассивная радиобаза")
            .setContentText("Ожидание GPS-фиксов от других приложений; Clear GPS не запрашивает GPS")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        unregisterPassiveProvider()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    companion object {
        const val ACTION_START = "org.cleargps.PASSIVE_CAPTURE_START"
        const val ACTION_STOP = "org.cleargps.PASSIVE_CAPTURE_STOP"

        private const val CHANNEL_ID = "clear_gps_passive_capture"
        private const val NOTIFICATION_ID = 4112

        private fun key(type: String, field: String): String =
            "passive_capture_last_${type}_${field}"
    }
}
