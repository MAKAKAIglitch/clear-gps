package org.cleargps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import org.cleargps.core.FilterDecision
import org.cleargps.core.FilterState
import org.cleargps.core.LocationFilterEngine
import org.cleargps.core.ZoneGeometry
import org.cleargps.core.ZonePoint
import org.cleargps.core.ZoneStore

private data class LastResortFallback(val source: String, val location: Location)

class LocationMonitorService : Service(), LocationListener {
    private lateinit var manager: LocationManager
    private lateinit var prefs: android.content.SharedPreferences
    private val engine = LocationFilterEngine()
    private var mock: MockLocationOutput? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mock = MockLocationOutput(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Clear GPS monitoring", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean(KEY_MONITORING, false).apply()
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISABLE_MOCK -> {
                prefs.edit().putBoolean(KEY_MOCK_ENABLED, false).apply()
                mock?.disable()
                saveServiceStatus("Mock output disabled")
                if (!prefs.getBoolean(KEY_MONITORING, false)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Monitoring GNSS; mock output off"))
                return START_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification("Monitoring GNSS anomalies"))
                prefs.edit().putBoolean(KEY_MONITORING, true).apply()
                startTracking()
                return START_STICKY
            }
        }
    }

    private fun startTracking() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            saveServiceStatus("Location permission missing")
            stopSelf()
            return
        }
        try {
            manager.removeUpdates(this)
            if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
            }
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, this)
            }
            saveServiceStatus("Monitoring")
        } catch (_: SecurityException) {
            saveServiceStatus("Location permission denied")
            stopSelf()
        } catch (_: IllegalArgumentException) {
            saveServiceStatus("Location provider unavailable")
        }
    }

    private fun stopTracking() {
        try { manager.removeUpdates(this) } catch (_: SecurityException) {}
        mock?.disable()
    }

    /**
     * A recent, accurate radio estimate is preferred. A manually placed anchor is considered
     * only after an explicit checkbox opt-in and only when no other fallback exists.
     */
    private fun createLastResortFallback(decision: FilterDecision): LastResortFallback? {
        if (decision.state != FilterState.NO_FALLBACK || decision.outputLocation != null) return null
        val now = System.currentTimeMillis()
        val estimate = RadioGeolocationRepository.loadLastEstimate(this)
        if (estimate != null) {
            val age = now - estimate.updatedAt
            if (age in 0..RADIO_FALLBACK_MAX_AGE_MS &&
                estimate.accuracyMeters <= RADIO_FALLBACK_MAX_ACCURACY_METERS) {
                val location = Location(LocationManager.NETWORK_PROVIDER).apply {
                    latitude = estimate.latitude
                    longitude = estimate.longitude
                    accuracy = estimate.accuracyMeters.toFloat().coerceAtLeast(1f)
                    time = estimate.updatedAt
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                }
                return LastResortFallback("RADIO_DB", location)
            }
        }

        if (!prefs.getBoolean(ClearGpsPreferences.KEY_MANUAL_FALLBACK_ENABLED, false)) return null
        val anchor = ManualLocationStore.load(this) ?: return null
        val anchorSetAt = prefs.getLong(ClearGpsPreferences.KEY_MANUAL_SET_AT, 0L)
        if (anchorSetAt <= 0L || now - anchorSetAt !in 0..MANUAL_FALLBACK_MAX_AGE_MS) return null

        val location = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = anchor.latitude
            longitude = anchor.longitude
            accuracy = MANUAL_FALLBACK_ACCURACY_METERS
            time = anchorSetAt
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        return LastResortFallback("MANUAL_ANCHOR", location)
    }

    override fun onLocationChanged(location: Location) {
        val gpsFix = location.provider.equals(LocationManager.GPS_PROVIDER, true)
        val inZone = if (gpsFix) {
            ZoneGeometry.contains(ZonePoint(location.latitude, location.longitude), ZoneStore.load(this))
        } else {
            engine.currentDecision().inRiskZone
        }
        val observedDecision = engine.observe(location, inZone)
        val lastResortFallback = createLastResortFallback(observedDecision)
        val result = if (lastResortFallback != null) {
            observedDecision.copy(
                state = FilterState.CORRECTING,
                source = lastResortFallback.source,
                outputLocation = lastResortFallback.location
            )
        } else {
            observedDecision
        }
        persist(result)

        when (lastResortFallback?.source) {
            "RADIO_DB" -> saveServiceStatus(
                "Radio DB fallback active · about ±" + lastResortFallback.location.accuracy.toInt() + " m"
            )
            "MANUAL_ANCHOR" -> saveServiceStatus(
                "Manual anchor fallback active · about ±" + lastResortFallback.location.accuracy.toInt() + " m"
            )
            else -> {
                val status = prefs.getString(KEY_SERVICE_STATUS, "") ?: ""
                if (status.startsWith("Radio DB fallback") || status.startsWith("Manual anchor fallback")) {
                    saveServiceStatus("Monitoring")
                }
            }
        }

        if (prefs.getBoolean(KEY_MOCK_ENABLED, false) && result.outputLocation != null) {
            if (mock?.publish(result.outputLocation) != true) {
                saveServiceStatus("Mock output unavailable; choose Clear GPS in Developer options")
            }
        }
        val sourceHint = when (result.source) {
            "RADIO_DB" -> " · radio fallback"
            "MANUAL_ANCHOR" -> " · manual anchor fallback"
            else -> ""
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            notification(result.state.name + " · risk " + result.riskScore + "%" + sourceHint))
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun persist(result: FilterDecision) {
        val edit = prefs.edit()
            .putString(KEY_STATE, result.state.name)
            .putInt(KEY_SCORE, result.riskScore)
            .putBoolean(KEY_IN_ZONE, result.inRiskZone)
            .putString(KEY_SOURCE, result.source)
            .putBoolean(KEY_HAS_OUTPUT, result.outputLocation != null)
        result.outputLocation?.let {
            edit.putString(KEY_LAT, it.latitude.toString())
                .putString(KEY_LON, it.longitude.toString())
                .putFloat(KEY_ACCURACY, if (it.hasAccuracy()) it.accuracy else -1f)
        }
        edit.apply()
    }

    private fun saveServiceStatus(text: String) {
        prefs.edit().putString(KEY_SERVICE_STATUS, text).apply()
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else Notification.Builder(this)
        return builder.setContentTitle("Clear GPS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "org.cleargps.START"
        const val ACTION_STOP = "org.cleargps.STOP"
        const val ACTION_DISABLE_MOCK = "org.cleargps.DISABLE_MOCK"
        const val PREFS = "clear_gps"
        const val KEY_MONITORING = "monitoring"
        const val KEY_MOCK_ENABLED = "mock_enabled"
        const val KEY_STATE = "state"
        const val KEY_SCORE = "risk_score"
        const val KEY_IN_ZONE = "in_zone"
        const val KEY_SOURCE = "source"
        const val KEY_HAS_OUTPUT = "has_output"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_SERVICE_STATUS = "service_status"
        private const val CHANNEL = "clear_gps_monitor"
        private const val NOTIFICATION_ID = 4101
        private const val RADIO_FALLBACK_MAX_AGE_MS = 30L * 60L * 1000L
        private const val RADIO_FALLBACK_MAX_ACCURACY_METERS = 1_000.0
        private const val MANUAL_FALLBACK_ACCURACY_METERS = 1_500f
        private const val MANUAL_FALLBACK_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
