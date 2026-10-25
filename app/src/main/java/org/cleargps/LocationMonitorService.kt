package org.cleargps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cleargps.core.FilterDecision
import org.cleargps.core.FilterState
import org.cleargps.core.GnssRfSnapshot
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioOnlyJob: Job? = null
    private var obd2FallbackJob: Job? = null
    private var lastTrustedGpsFixForObd2: Location? = null
    private var obd2FallbackPublished = false
    private var gnssStatusRegistered = false
    private var gnssMeasurementsRegistered = false

    // Ephemeral aggregate only; never persisted or exported.
    private var lastStatusMeanCn0DbHz: Double? = null
    private var lastStatusConstellationCount = 0
    private var lastStatusAtNanos = 0L

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val count = status.satelliteCount
            val cn0Values = (0 until count).mapNotNull { index ->
                status.getCn0DbHz(index).toDouble().takeIf { it.isFinite() && it > 0.0 }
            }
            val mean = cn0Values.average().takeIf { cn0Values.isNotEmpty() && it.isFinite() }
            val spread = if (cn0Values.size >= 2 && mean != null) {
                kotlin.math.sqrt(cn0Values.sumOf { value -> (value - mean) * (value - mean) } / cn0Values.size)
            } else null
            val constellations = (0 until count).map { status.getConstellationType(it) }
                .filter { it != GnssStatus.CONSTELLATION_UNKNOWN }.distinct().size
            lastStatusMeanCn0DbHz = mean
            lastStatusConstellationCount = constellations
            lastStatusAtNanos = SystemClock.elapsedRealtimeNanos()
            val assessment = engine.observeGnssSignal(
                org.cleargps.core.GnssSignalSnapshot(
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    satellitesVisible = count,
                    satellitesUsedInFix = (0 until count).count { status.usedInFix(it) },
                    meanCn0DbHz = mean,
                    cn0SpreadDb = spread,
                    constellationCount = constellations,
                    strongSignalCount = cn0Values.count { it >= 32.0 }
                )
            )
            assessment?.let(::publishSignalDecision)
        }
    }

    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        @Suppress("DEPRECATION")
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            try {
                val measurements = event.measurements
                val cn0Values = measurements.mapNotNull { measurement ->
                    measurement.cn0DbHz.toDouble().takeIf { it.isFinite() && it > 0.0 }
                }
                // API 33+ exposes AGC separately, including when per-satellite measurements
                // are temporarily absent. Older APIs use the AGC field on GnssMeasurement.
                val agcValues = if (Build.VERSION.SDK_INT >= 33) {
                    event.gnssAutomaticGainControls.mapNotNull { it.levelDb.takeIf(Double::isFinite) }
                } else {
                    measurements.mapNotNull { measurement ->
                        try {
                            if (measurement.hasAutomaticGainControlLevelDb()) {
                                measurement.getAutomaticGainControlLevelDb().takeIf(Double::isFinite)
                            } else {
                                null
                            }
                        } catch (_: RuntimeException) {
                            null
                        }
                    }
                }
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val meanCn0FromMeasurements =
                    cn0Values.average().takeIf { cn0Values.isNotEmpty() && it.isFinite() }
                val statusFresh = lastStatusAtNanos > 0L &&
                    nowNanos >= lastStatusAtNanos &&
                    nowNanos - lastStatusAtNanos <= 3_000_000_000L
                val meanCn0 = meanCn0FromMeasurements
                    ?: lastStatusMeanCn0DbHz.takeIf { statusFresh }
                val meanAgc = agcValues.average().takeIf { agcValues.isNotEmpty() && it.isFinite() }
                val measurementConstellations = measurements.map { it.constellationType }
                    .filter { it != GnssStatus.CONSTELLATION_UNKNOWN }.distinct().size
                val constellationCount = if (measurementConstellations > 0) measurementConstellations
                    else lastStatusConstellationCount.takeIf { statusFresh } ?: 0
                val signalCount = maxOf(cn0Values.size, agcValues.size)
                val assessment = engine.observeGnssMeasurements(
                    GnssRfSnapshot(
                        elapsedRealtimeNanos = nowNanos,
                        signalCount = signalCount,
                        constellationCount = constellationCount,
                        meanCn0DbHz = meanCn0,
                        meanAgcDb = meanAgc,
                        agcSampleCount = agcValues.size
                    )
                )
                assessment?.let(::publishSignalDecision)
            } catch (_: RuntimeException) {
                // Some receiver implementations provide incomplete measurements; keep monitoring.
            }
        }
    }

    private fun registerGnssStatus() {
        if (!gnssStatusRegistered) {
            try {
                gnssStatusRegistered = if (Build.VERSION.SDK_INT >= 30) {
                    manager.registerGnssStatusCallback(mainExecutor, gnssStatusCallback)
                } else {
                    manager.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
                }
            } catch (_: SecurityException) {
                gnssStatusRegistered = false
            } catch (_: RuntimeException) {
                gnssStatusRegistered = false
            }
        }
        registerGnssMeasurements()
    }

    @Suppress("DEPRECATION")
    private fun registerGnssMeasurements() {
        if (gnssMeasurementsRegistered) return
        try {
            gnssMeasurementsRegistered = when {
                Build.VERSION.SDK_INT >= 31 ->
                    manager.registerGnssMeasurementsCallback(mainExecutor, gnssMeasurementsCallback)
                Build.VERSION.SDK_INT <= 29 ->
                    manager.registerGnssMeasurementsCallback(
                        gnssMeasurementsCallback,
                        Handler(Looper.getMainLooper())
                    )
                else -> false // Android 11's legacy measurement-delivery regression; signal status still works.
            }
        } catch (_: SecurityException) {
            gnssMeasurementsRegistered = false
        } catch (_: RuntimeException) {
            gnssMeasurementsRegistered = false
        }
    }

    private fun unregisterGnssMeasurements() {
        if (!gnssMeasurementsRegistered) return
        try {
            manager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
        } catch (_: RuntimeException) {
        } finally {
            gnssMeasurementsRegistered = false
        }
    }

    private fun unregisterGnssStatus() {
        if (gnssStatusRegistered) {
            try {
                manager.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (_: RuntimeException) {
            } finally {
                gnssStatusRegistered = false
            }
        }
        unregisterGnssMeasurements()
    }

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
                startTracking()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification("Starting location monitor"))
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
            unregisterGnssStatus()
            radioOnlyJob?.cancel()
            radioOnlyJob = null
            obd2FallbackJob?.cancel()
            obd2FallbackJob = null
            if (obd2FallbackPublished) clearObd2FallbackOutput("OBD-II fallback cleared while restarting location monitoring")
            lastTrustedGpsFixForObd2 = null

            when (prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")) {
                "radio", "radio_obd2" -> {
                    // Discard any previously published mock location before radio lookup begins.
                    // The radio-based modes never request Android GPS for their coordinates.
                    mock?.disable()
                    prefs.edit()
                        .putBoolean(KEY_HAS_OUTPUT, false)
                        .putString(KEY_SOURCE, "NONE")
                        .remove(KEY_LAT)
                        .remove(KEY_LON)
                        .remove(KEY_ACCURACY)
                        .apply()
                    val radioObdMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") == "radio_obd2"
                    saveServiceStatus(
                        if (radioObdMode) "Radio + OBD-II mode: GPS provider is not requested; radio remains the coordinate source"
                        else "Radio-only mode: GPS provider is not requested"
                    )
                    startRadioOnlyLoop()
                    return
                }
                "gps", "gps_obd2" -> {
                    if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
                        registerGnssStatus()
                    }
                    if (prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") == "gps_obd2") {
                        startObd2FallbackLoop()
                    }
                }
                else -> {
                    if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
                        registerGnssStatus()
                    }
                    if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, this)
                    }
                }
            }
            saveServiceStatus("Monitoring")
        } catch (_: SecurityException) {
            saveServiceStatus("Location permission denied")
            stopSelf()
        } catch (_: IllegalArgumentException) {
            saveServiceStatus("Location provider unavailable")
        }
    }

    /**
     * Bounded dead-reckoning fallback for GPS + OBD-II.
     *
     * It is only allowed after a recently accepted NORMAL GPS fix, and is stopped as soon as
     * the GNSS filter raises a warning, a required input becomes stale, or the prediction cap
     * is reached. This is a fallback, not an extra input to the GNSS spoofing detector.
     */
    private fun startObd2FallbackLoop() {
        obd2FallbackJob?.cancel()
        obd2FallbackJob = serviceScope.launch {
            while (isActive &&
                prefs.getBoolean(KEY_MONITORING, false) &&
                prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") == "gps_obd2") {
                delay(1_000L)

                val filterState = prefs.getString(KEY_STATE, FilterState.WAITING.name)
                val lastSource = prefs.getString(KEY_SOURCE, "NONE")
                val filterAllowsFallback = filterState == FilterState.NORMAL.name &&
                    lastSource in setOf("GPS", "OBD2_DEAD_RECKONING") &&
                    !prefs.getBoolean(KEY_IN_ZONE, false)
                val lastFix = lastTrustedGpsFixForObd2
                val now = System.currentTimeMillis()
                val speedKmh = Obd2SharedState.freshSpeedKmh(prefs, now)
                val speedAt = prefs.getLong(Obd2SharedState.KEY_SPEED_AT, 0L)
                val prediction = if (filterAllowsFallback && lastFix != null && lastFix.hasBearing()) {
                    Obd2LocationFusion.predictFromGps(
                        lastFix = MapCoordinate(lastFix.latitude, lastFix.longitude),
                        baseAccuracyMeters = lastFix.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                        bearingDegrees = lastFix.bearing.toDouble(),
                        fixTimeMillis = lastFix.time,
                        speedKmh = speedKmh,
                        speedUpdatedAtMillis = speedAt,
                        nowMillis = now
                    )
                } else null

                if (prediction != null && speedKmh != null && lastFix != null) {
                    val projected = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = prediction.coordinate.latitude
                        longitude = prediction.coordinate.longitude
                        accuracy = prediction.accuracyMeters.toFloat().coerceAtLeast(1f)
                        time = now
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        speed = speedKmh / 3.6f
                        bearing = lastFix.bearing
                    }
                    val mockEnabled = prefs.getBoolean(KEY_MOCK_ENABLED, false)
                    val canPublish = !mockEnabled || mock?.publish(projected) == true
                    if (canPublish) {
                        val wasAlreadyPublished = obd2FallbackPublished
                        prefs.edit()
                            .putString(KEY_SOURCE, "OBD2_DEAD_RECKONING")
                            .putBoolean(KEY_HAS_OUTPUT, true)
                            .putString(KEY_LAT, projected.latitude.toString())
                            .putString(KEY_LON, projected.longitude.toString())
                            .putFloat(KEY_ACCURACY, projected.accuracy)
                            .apply()
                        obd2FallbackPublished = true
                        if (!wasAlreadyPublished) {
                            val status = "GPS + OBD-II short fallback · about ±" +
                                projected.accuracy.toInt() + " m; " +
                                prediction.travelledMeters.toInt() + " m projected"
                            saveServiceStatus(status)
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(status))
                        }
                    } else {
                        if (obd2FallbackPublished) {
                            clearObd2FallbackOutput("OBD-II fallback stopped; mock-location output is unavailable")
                        } else {
                            saveServiceStatus("OBD-II has speed data, but mock-location output is unavailable")
                        }
                    }
                } else if (obd2FallbackPublished) {
                    val reason = when {
                        !filterAllowsFallback -> "OBD-II fallback stopped by GNSS filter state"
                        lastFix == null -> "OBD-II fallback stopped; waiting for an accepted GPS fix"
                        speedKmh == null -> "OBD-II fallback expired; waiting for fresh vehicle speed"
                        else -> "OBD-II fallback reached its time/distance limit; waiting for GPS"
                    }
                    clearObd2FallbackOutput(reason)
                }
            }
            if (obd2FallbackPublished) {
                clearObd2FallbackOutput("OBD-II fallback stopped with location monitoring")
            }
        }
    }

    private fun clearObd2FallbackOutput(reason: String) {
        if (!obd2FallbackPublished) return
        if (prefs.getBoolean(KEY_MOCK_ENABLED, false)) mock?.disable()
        prefs.edit()
            .putBoolean(KEY_HAS_OUTPUT, false)
            .putString(KEY_SOURCE, "NONE")
            .remove(KEY_LAT)
            .remove(KEY_LON)
            .remove(KEY_ACCURACY)
            .apply()
        obd2FallbackPublished = false
        saveServiceStatus(reason)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(reason))
    }

    private fun startRadioOnlyLoop() {
        radioOnlyJob?.cancel()
        radioOnlyJob = serviceScope.launch {
            val repository = RadioGeolocationRepository(applicationContext)
            while (isActive && prefs.getBoolean(KEY_MONITORING, false) &&
                prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") in setOf("radio", "radio_obd2")) {
                try {
                    val result = repository.scan(
                        record = false,
                        reference = null,
                        mode = "radio",
                        useWifi = prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true),
                        useCells = prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true),
                        useBle = prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)
                    )
                    val estimate = result.estimate
                    if (estimate != null && estimate.matchedCount >= RADIO_MIN_MATCHES &&
                        estimate.accuracyMeters <= RADIO_FALLBACK_MAX_ACCURACY_METERS) {
                        val output = Location(LocationManager.NETWORK_PROVIDER).apply {
                            latitude = estimate.latitude
                            longitude = estimate.longitude
                            accuracy = estimate.accuracyMeters.toFloat().coerceAtLeast(1f)
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        }
                        prefs.edit().putString(KEY_STATE, FilterState.CORRECTING.name)
                            .putInt(KEY_SCORE, 0).putString(KEY_REASONS, "").putString(KEY_SOURCE, "LOCAL_RADIO")
                            .putBoolean(KEY_HAS_OUTPUT, true).putString(KEY_LAT, output.latitude.toString())
                            .putString(KEY_LON, output.longitude.toString()).putFloat(KEY_ACCURACY, output.accuracy).apply()
                        if (prefs.getBoolean(KEY_MOCK_ENABLED, false) && mock?.publish(output) != true) {
                            saveServiceStatus("Radio match found, but mock output is unavailable; check Developer options")
                        } else {
                            saveServiceStatus("Radio-only · " + estimate.matchedCount + " local matches · ±" + estimate.accuracyMeters.toInt() + " m")
                        }
                    } else {
                        if (prefs.getBoolean(KEY_MOCK_ENABLED, false)) mock?.disable()
                        prefs.edit()
                            .putBoolean(KEY_HAS_OUTPUT, false)
                            .putString(KEY_SOURCE, "NONE")
                            .putString(KEY_STATE, FilterState.NO_FALLBACK.name)
                            .putInt(KEY_SCORE, 0).putString(KEY_REASONS, "")
                            // Do not leave an older position displayed as if it matched this scan.
                            .remove(ClearGpsPreferences.KEY_LAST_LAT)
                            .remove(ClearGpsPreferences.KEY_LAST_LON)
                            .remove(ClearGpsPreferences.KEY_LAST_ACCURACY)
                            .remove(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT)
                            .remove(ClearGpsPreferences.KEY_LAST_FINGERPRINT)
                            .remove(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT)
                            .apply()
                        saveServiceStatus("Radio-only · no reliable local match yet")
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        notification(prefs.getString(KEY_SERVICE_STATUS, "Radio-only") ?: "Radio-only"))
                } catch (error: Exception) {
                    saveServiceStatus("Radio-only scan failed: " + (error.message ?: error.javaClass.simpleName))
                }
                val baseInterval = prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15).coerceIn(10, 120)
                val radioObdMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") == "radio_obd2"
                val obdSpeedKmh = if (radioObdMode) Obd2SharedState.freshSpeedKmh(prefs) else null
                // While the car is moving, shorten radio rescans; if speed is unavailable/stale,
                // fall back to the user's normal scan interval without inventing a location.
                val nextInterval = if (obdSpeedKmh != null && obdSpeedKmh >= 5) minOf(baseInterval, 10) else baseInterval
                delay(nextInterval * 1_000L)
            }
        }
    }

    private fun stopTracking() {
        radioOnlyJob?.cancel()
        radioOnlyJob = null
        obd2FallbackJob?.cancel()
        obd2FallbackJob = null
        if (obd2FallbackPublished) clearObd2FallbackOutput("OBD-II fallback stopped with location monitoring")
        lastTrustedGpsFixForObd2 = null
        try { manager.removeUpdates(this) } catch (_: SecurityException) {}
        unregisterGnssStatus()
        mock?.disable()
    }

    private fun createLastResortFallback(decision: FilterDecision): LastResortFallback? {
        if (decision.state != FilterState.NO_FALLBACK || decision.outputLocation != null) return null
        val now = System.currentTimeMillis()
        val estimate = RadioGeolocationRepository.loadLastEstimate(this)
        if (estimate != null) {
            val age = now - estimate.updatedAt
            if (estimate.matchedCount >= RADIO_MIN_MATCHES &&
                age in 0..RADIO_FALLBACK_MAX_AGE_MS &&
                estimate.accuracyMeters <= RADIO_FALLBACK_MAX_ACCURACY_METERS) {
                val location = Location(LocationManager.NETWORK_PROVIDER).apply {
                    latitude = estimate.latitude
                    longitude = estimate.longitude
                    accuracy = estimate.accuracyMeters.toFloat().coerceAtLeast(1f)
                    time = now
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                }
                return LastResortFallback("LOCAL_RADIO", location)
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
            time = now
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        return LastResortFallback("MANUAL_ANCHOR", location)
    }

    override fun onLocationChanged(location: Location) {
        // Return before updating status, trusted-fix cache, or mock output: our own test-provider
        // updates must never be treated as new physical GPS fixes.
        if (LocationFilterEngine.isMockLocation(location)) return
        val gpsFix = location.provider.equals(LocationManager.GPS_PROVIDER, true)
        val point = ZonePoint(location.latitude, location.longitude)
        val inZone = if (gpsFix) {
            ZoneStore.loadAll(this).any { zone -> zone.enabled && ZoneGeometry.contains(point, zone.points) }
        } else engine.currentDecision().inRiskZone

        val observedDecision = engine.observe(location, inZone)
        val fallback = createLastResortFallback(observedDecision)
        val result = if (fallback != null) {
            observedDecision.copy(state = FilterState.CORRECTING, source = fallback.source, outputLocation = fallback.location)
        } else observedDecision
        persistDecision(result)
        if (gpsFix && result.state == FilterState.NORMAL && result.source == "GPS" && !inZone) {
            lastTrustedGpsFixForObd2 = Location(location)
            // A fresh physical GPS fix supersedes the synthetic prediction.
            obd2FallbackPublished = false
        }

        when (fallback?.source) {
            "LOCAL_RADIO" -> saveServiceStatus("Local radio fallback active · about ±" + fallback.location.accuracy.toInt() + " m")
            "MANUAL_ANCHOR" -> saveServiceStatus("Manual anchor fallback active · about ±" + fallback.location.accuracy.toInt() + " m")
            else -> {
                val status = prefs.getString(KEY_SERVICE_STATUS, "") ?: ""
                if (status.startsWith("Local radio fallback") || status.startsWith("Manual anchor fallback")) saveServiceStatus("Monitoring")
            }
        }

        if (prefs.getBoolean(KEY_MOCK_ENABLED, false) && result.outputLocation != null) {
            if (mock?.publish(result.outputLocation) != true) saveServiceStatus("Mock output unavailable; choose Clear GPS in Developer options")
        }
        val sourceHint = when (result.source) {
            "LOCAL_RADIO" -> " · local radio fallback"
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
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun publishSignalDecision(result: FilterDecision) {
        persistDecision(result)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(result.state.name + " · risk " + result.riskScore + "%")
        )
    }

    /**
     * Signal-only warnings update anomaly metadata but do not write position fields.
     */
    private fun persistDecision(result: FilterDecision) {
        if (result.source == "GNSS_SIGNAL_ONLY") {
            prefs.edit()
                .putString(KEY_STATE, result.state.name)
                .putInt(KEY_SCORE, result.riskScore)
                .putBoolean(KEY_IN_ZONE, result.inRiskZone)
                .putString(KEY_SOURCE, result.source)
                .putString(KEY_REASONS, result.reasons.joinToString("\n"))
                .apply()
            return
        }
        persist(result)
    }

    private fun persist(result: FilterDecision) {
        val edit = prefs.edit()
            .putString(KEY_STATE, result.state.name)
            .putInt(KEY_SCORE, result.riskScore)
            .putBoolean(KEY_IN_ZONE, result.inRiskZone)
            .putString(KEY_SOURCE, result.source)
            .putString(KEY_REASONS, result.reasons.joinToString("\n"))
            .putBoolean(KEY_HAS_OUTPUT, result.outputLocation != null)
        result.outputLocation?.let {
            edit.putString(KEY_LAT, it.latitude.toString()).putString(KEY_LON, it.longitude.toString())
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
        return builder.setContentTitle("Clear GPS").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
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
        const val KEY_REASONS = "risk_reasons"
        const val KEY_IN_ZONE = "in_zone"
        const val KEY_SOURCE = "source"
        const val KEY_HAS_OUTPUT = "has_output"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_SERVICE_STATUS = "service_status"
        private const val CHANNEL = "clear_gps_monitor"
        private const val NOTIFICATION_ID = 4101
        private const val RADIO_SCAN_INTERVAL_MS = 12_000L
        private const val RADIO_MIN_MATCHES = 3
        private const val RADIO_FALLBACK_MAX_AGE_MS = 30L * 60L * 1000L
        private const val RADIO_FALLBACK_MAX_ACCURACY_METERS = 1_000.0
        private const val MANUAL_FALLBACK_ACCURACY_METERS = 1_500f
        private const val MANUAL_FALLBACK_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
