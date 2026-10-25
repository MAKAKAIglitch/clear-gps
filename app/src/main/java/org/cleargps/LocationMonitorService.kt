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
    private var mockFreshnessJob: Job? = null
    private var lastTrustedGpsFixForObd2: Location? = null
    private var obd2FallbackPublished = false
    private var lastRadioMockFix: Location? = null
    private var lastRadioMockFixAtElapsedMs = 0L
    private var activePositionMode: String? = null
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
                prefs.edit()
                    .putBoolean(KEY_HAS_OUTPUT, false)
                    .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                    .putString(KEY_SOURCE, "NONE")
                    .putString(KEY_STATE, FilterState.WAITING.name)
                    .putInt(KEY_SCORE, 0)
                    .putBoolean(KEY_IN_ZONE, false)
                    .putString(KEY_REASONS, "")
                    .remove(KEY_LAT)
                    .remove(KEY_LON)
                    .remove(KEY_ACCURACY)
                    .apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISABLE_MOCK -> {
                prefs.edit()
                    .putBoolean(KEY_MOCK_ENABLED, false)
                    .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                    .apply()
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

    private fun configuredUpdateIntervalMillis(): Long =
        prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15).coerceIn(1, 30) * 1_000L

    private fun startTracking() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_MONITORING, false).putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            mock?.disable()
            saveServiceStatus("Location permission missing")
            DiagnosticLogStore.log(this, "SERVICE", "Мониторинг не запущен: отсутствует разрешение геолокации", "ERROR")
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

            val nextPositionMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
            val modeChanged = activePositionMode != nextPositionMode
            // Don't unregister a working mock provider every time the user changes the refresh
            // interval. Unregister only for a real source change or when mock output was turned off.
            if (modeChanged || !prefs.getBoolean(KEY_MOCK_ENABLED, false)) {
                mock?.disable()
                prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
                lastRadioMockFix = null
                lastRadioMockFixAtElapsedMs = 0L
            }
            if (modeChanged) {
                prefs.edit()
                    .putBoolean(KEY_HAS_OUTPUT, false)
                    .putString(KEY_SOURCE, "NONE")
                    .remove(KEY_LAT)
                    .remove(KEY_LON)
                    .remove(KEY_ACCURACY)
                    .apply()
            }
            activePositionMode = nextPositionMode
            lastTrustedGpsFixForObd2 = null
            if (mockFreshnessJob?.isActive != true) startMockFreshnessWatchdog()
            DiagnosticLogStore.log(
                this, "SERVICE",
                "Запуск трекинга: mode=$nextPositionMode, interval=${configuredUpdateIntervalMillis() / 1_000}s, " +
                    "monitoring=true, mockRequested=${prefs.getBoolean(KEY_MOCK_ENABLED, false)}, modeChanged=$modeChanged"
            )

            when (nextPositionMode) {
                "radio", "radio_obd2" -> {
                    // Radio-based modes never request Android GPS for their coordinates.
                    // A same-mode ACTION_START is often just a refresh-setting update: preserve
                    // the registered mock provider and last known output in that case.
                    val radioObdMode = Obd2SharedState.freshSpeedKmh(prefs) != null
                    val mockRequested = prefs.getBoolean(KEY_MOCK_ENABLED, false)
                    saveServiceStatus(
                        when {
                            radioObdMode && mockRequested ->
                                "Radio + OBD-II: scanning local radio database; waiting for a reliable match before publishing mock GPS"
                            radioObdMode ->
                                "Radio + OBD-II: scanning local radio database; mock output is disabled"
                            mockRequested ->
                                "Radio-only: searching the local database; mock GPS will publish after a reliable match"
                            else ->
                                "Radio-only: searching the local database; mock output is disabled"
                        }
                    )
                    DiagnosticLogStore.log(
                        this, "RADIO_FIX",
                        "Radio positioning started: mockRequested=$mockRequested; Android physical GPS input intentionally disabled"
                    )
                    startRadioOnlyLoop()
                    return
                }
                "gps", "gps_obd2" -> {
                    if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, configuredUpdateIntervalMillis(), 0f, this)
                        registerGnssStatus()
                    }
                    startObd2FallbackLoop()
                }
                else -> {
                    if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, configuredUpdateIntervalMillis(), 0f, this)
                        registerGnssStatus()
                    }
                    if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, configuredUpdateIntervalMillis(), 0f, this)
                    }
                    startObd2FallbackLoop()
                }
            }
            saveServiceStatus("Monitoring")
        } catch (error: SecurityException) {
            prefs.edit().putBoolean(KEY_MONITORING, false).putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            mock?.disable()
            saveServiceStatus("Location permission denied")
            DiagnosticLogStore.log(this, "SERVICE", "Android отказал в разрешении геолокации: ${error.message ?: "SecurityException"}", "ERROR")
            stopSelf()
        } catch (error: IllegalArgumentException) {
            prefs.edit().putBoolean(KEY_MONITORING, false).putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            mock?.disable()
            saveServiceStatus("Location provider unavailable: " + (error.message ?: "unknown"))
            DiagnosticLogStore.log(this, "SERVICE", "GPS provider недоступен: ${error.message ?: error.javaClass.simpleName}", "ERROR")
            stopSelf()
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
                prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") in setOf("auto", "gps", "gps_obd2")) {
                delay(configuredUpdateIntervalMillis())

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
                            .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, mockEnabled)
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
            .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
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

    /**
     * At service startup a scan can take several seconds and Android may throttle Wi-Fi/BLE.
     * Re-publish only a still-credible, very recent radio estimate during that first scan.
     * Its age continues from the original evidence timestamp; publishing it does not make it fresh.
     */
    private fun restoreRecentRadioMockFixWhileScanning() {
        if (!prefs.getBoolean(KEY_MOCK_ENABLED, false) || lastRadioMockFix != null) return

        val estimate = RadioGeolocationRepository.loadLastEstimate(applicationContext) ?: return
        val nowWall = System.currentTimeMillis()
        val ageMs = nowWall - estimate.updatedAt
        val intervalMs = configuredUpdateIntervalMillis()
        if (!RadioFixReliability.canRetainLastEstimate(
                estimate.updatedAt, nowWall, estimate.matchedCount, estimate.accuracyMeters
            ) || ageMs > RadioFixReliability.mockHoldWindowMillis(intervalMs)) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (ageMs < 0L || ageMs >= nowElapsed) return
        val confirmedAt = nowElapsed - ageMs
        if (!RadioFixReliability.shouldHoldLastRadioMockFix(confirmedAt, nowElapsed, intervalMs)) return

        val confirmed = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = estimate.latitude
            longitude = estimate.longitude
            accuracy = estimate.accuracyMeters.toFloat().coerceIn(1f, 100_000f)
            time = estimate.updatedAt
            elapsedRealtimeNanos = (nowElapsed - ageMs).coerceAtLeast(1L) * 1_000_000L
        }
        val held = Location(confirmed).apply {
            accuracy = RadioFixReliability.mockAccuracyWithAge(confirmed.accuracy, ageMs)
            time = nowWall
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        if (mock?.publish(held) != true) {
            prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            val reason = mock?.lastError ?: "Проверь выбор Clear GPS в настройках разработчика."
            saveServiceStatus("Mock GPS не опубликован: $reason")
            DiagnosticLogStore.log(this, "MOCK", "Не удалось восстановить недавнюю радиопозицию: $reason", "ERROR")
            return
        }

        lastRadioMockFix = confirmed
        lastRadioMockFixAtElapsedMs = confirmedAt
        prefs.edit()
            .putString(KEY_SOURCE, "LOCAL_RADIO_STALE")
            .putString(KEY_STATE, FilterState.CORRECTING.name)
            .putInt(KEY_SCORE, 0)
            .putString(KEY_REASONS, "Используется недавняя позиция, пока запускается новый радиоскан.")
            .putBoolean(KEY_HAS_OUTPUT, true)
            .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, true)
            .putString(KEY_LAT, held.latitude.toString())
            .putString(KEY_LON, held.longitude.toString())
            .putFloat(KEY_ACCURACY, held.accuracy)
            .apply()
        val status = "Mock GPS published · refreshing recent radio fix · cached age " +
            (ageMs / 1_000L) + " s · uncertainty ±" + held.accuracy.toInt() + " m"
        saveServiceStatus(status)
        DiagnosticLogStore.log(this, "MOCK", "Недавняя радиопозиция опубликована до завершения нового сканирования; age=" + (ageMs / 1_000L) + "s")
    }

    private fun startRadioOnlyLoop() {
        radioOnlyJob?.cancel()
        radioOnlyJob = serviceScope.launch {
            val repository = RadioGeolocationRepository(applicationContext)
            restoreRecentRadioMockFixWhileScanning()
            while (isActive && prefs.getBoolean(KEY_MONITORING, false) &&
                prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") in setOf("radio", "radio_obd2")) {
                try {
                    val result = repository.scan(
                        // Persist the same observations the UI recording toggle requests, but
                        // never attach a self-derived radio estimate as a trusted coordinate.
                        record = prefs.getBoolean(ClearGpsPreferences.KEY_RECORDING, false),
                        reference = null,
                        mode = "radio",
                        useWifi = prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true),
                        useCells = prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true),
                        useBle = prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)
                    )
                    val estimate = result.estimate
                    val currentSignalCount = result.wifiCount + result.cellCount + result.beaconCount
                    val mockRequested = prefs.getBoolean(KEY_MOCK_ENABLED, false)
                    val nowWallMillis = System.currentTimeMillis()
                    val nowElapsedMillis = SystemClock.elapsedRealtime()
                    val estimateAgeMillis = estimate?.let { nowWallMillis - it.updatedAt } ?: Long.MAX_VALUE
                    val estimateEvidenceRecent = estimateAgeMillis in 0..RadioFixReliability.WIFI_MAX_CACHE_AGE_MS
                    val cachedEstimateAllowed = estimate != null && RadioFixReliability.canPublishRadioEstimate(
                        estimateUpdatedAtMillis = estimate.updatedAt,
                        nowWallMillis = nowWallMillis,
                        fromCache = result.fromCache,
                        lastConfirmedAtElapsedMillis = lastRadioMockFixAtElapsedMs,
                        nowElapsedMillis = nowElapsedMillis,
                        updateIntervalMillis = configuredUpdateIntervalMillis()
                    )
                    val estimateAllowedForOutput = estimateEvidenceRecent &&
                        (!mockRequested || cachedEstimateAllowed)
                    val rejectionReason = when {
                        estimate == null && currentSignalCount < 2 ->
                            "Сейчас обнаружено только $currentSignalCount радиосигналов. Для расчёта нужны минимум 2 актуальных сигнала; локальная база знает ${result.knownRadioCount} из них, пригодных координатных записей: ${result.coordinateRadioCount}."
                        estimate == null ->
                            "Текущий скан увидел $currentSignalCount радиосигналов; совпадений идентификаторов в базе: ${result.knownRadioCount}, пригодных координатных записей: ${result.coordinateRadioCount}. Однозначная координата не построена."
                        !estimateEvidenceRecent ->
                            "Оценка отклонена: возраст радиоданных ${(estimateAgeMillis.coerceAtLeast(0L) / 1_000L)} с превышает допустимый предел ${RadioFixReliability.WIFI_MAX_CACHE_AGE_MS / 1_000L} с."
                        mockRequested && !cachedEstimateAllowed ->
                            "Кэш радиосигналов больше не продлевает mock GPS: ожидаю новый подтверждённый скан или истечение ограниченного резерва."
                        estimate.matchedCount < RADIO_MIN_MATCHES ->
                            "Оценка отклонена для mock GPS: совпадений ${estimate.matchedCount}, нужно не меньше $RADIO_MIN_MATCHES."
                        estimate.accuracyMeters > RADIO_FALLBACK_MAX_ACCURACY_METERS ->
                            "Оценка отклонена для mock GPS: погрешность ±${estimate.accuracyMeters.toInt()} м превышает лимит ±${RADIO_FALLBACK_MAX_ACCURACY_METERS.toInt()} м."
                        else -> ""
                    }
                    DiagnosticLogStore.log(
                        this@LocationMonitorService, "RADIO_FIX",
                        "Служба: сигналы Wi-Fi=${result.wifiCount}, соты=${result.cellCount}, BLE=${result.beaconCount}; " +
                            "estimate=${estimate != null}, estimateMatches=${estimate?.matchedCount ?: 0}, " +
                            "dbKnown=${result.knownRadioCount}, dbLocated=${result.coordinateRadioCount}, " +
                            "accuracy=${estimate?.accuracyMeters?.toInt() ?: -1}m, cached=${result.fromCache}, " +
                            (rejectionReason.ifBlank { "оценка прошла пороги" })
                    )
                    if (estimate != null && estimateAllowedForOutput &&
                        estimate.matchedCount >= RADIO_MIN_MATCHES &&
                        estimate.accuracyMeters <= RADIO_FALLBACK_MAX_ACCURACY_METERS) {
                        val output = Location(LocationManager.NETWORK_PROVIDER).apply {
                            latitude = estimate.latitude
                            longitude = estimate.longitude
                            accuracy = estimate.accuracyMeters.toFloat().coerceAtLeast(1f)
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        }
                        val mockPublished = !mockRequested || mock?.publish(output) == true
                        if (mockPublished) {
                            if (mockRequested) {
                                // Anchor the hold timer to the oldest contributing evidence, not
                                // to the moment we read the Android cache or finished calculation.
                                val evidenceAge = (System.currentTimeMillis() - estimate.updatedAt)
                                    .coerceIn(0L, RadioFixReliability.WIFI_MAX_CACHE_AGE_MS)
                                lastRadioMockFix = Location(output)
                                lastRadioMockFixAtElapsedMs =
                                    (SystemClock.elapsedRealtime() - evidenceAge).coerceAtLeast(1L)
                            } else {
                                lastRadioMockFix = null
                                lastRadioMockFixAtElapsedMs = 0L
                            }
                            prefs.edit().putString(KEY_STATE, FilterState.CORRECTING.name)
                                .putInt(KEY_SCORE, 0).putString(KEY_REASONS, "").putString(KEY_SOURCE, "LOCAL_RADIO")
                                .putBoolean(KEY_HAS_OUTPUT, true)
                                .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, mockRequested)
                                .putString(KEY_LAT, output.latitude.toString())
                                .putString(KEY_LON, output.longitude.toString()).putFloat(KEY_ACCURACY, output.accuracy).apply()
                            saveServiceStatus(
                                (if (mockRequested) "Mock GPS published" else "Radio estimate only") +
                                    (if (result.fromCache) " · cached radio data" else "") +
                                    " · " + estimate.matchedCount + " local matches · ±" + estimate.accuracyMeters.toInt() + " m"
                            )
                        } else {
                            lastRadioMockFix = null
                            lastRadioMockFixAtElapsedMs = 0L
                            mock?.disable()
                            // Keep the map's estimate separate from the system mock-provider state.
                            prefs.edit().putBoolean(KEY_HAS_OUTPUT, false)
                                .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                                .putString(KEY_SOURCE, "NONE")
                                .remove(KEY_LAT).remove(KEY_LON).remove(KEY_ACCURACY).apply()
                            val reason = mock?.lastError ?: "Проверь, что Clear GPS выбрано в настройках разработчика как приложение фиктивного местоположения."
                            saveServiceStatus("Радиосовпадение найдено, но mock GPS не опубликован: $reason")
                            DiagnosticLogStore.log(this@LocationMonitorService, "MOCK", "Публикация радиопозиции не удалась: $reason", "ERROR")
                        }
                    } else {
                        val mockRequested = prefs.getBoolean(KEY_MOCK_ENABLED, false)
                        val nowElapsedMs = SystemClock.elapsedRealtime()
                        val confirmed = lastRadioMockFix
                        val confirmedAt = lastRadioMockFixAtElapsedMs
                        val ageMs = if (confirmedAt > 0L && nowElapsedMs >= confirmedAt) nowElapsedMs - confirmedAt else Long.MAX_VALUE
                        val intervalMs = configuredUpdateIntervalMillis()
                        val canHold = mockRequested && confirmed != null &&
                            RadioFixReliability.shouldHoldLastRadioMockFix(confirmedAt, nowElapsedMs, intervalMs)
                        DiagnosticLogStore.log(
                            this@LocationMonitorService, "RADIO_FIX",
                            "Резерв radio fix: $canHold; возраст подтверждённой позиции=${if (ageMs == Long.MAX_VALUE) "unknown" else ageMs / 1_000} s; причина=$rejectionReason"
                        )
                        if (canHold && confirmed != null) {
                            // A single missed/throttled radio scan must not unregister GPS.
                            // Briefly hold the last confirmed position with a growing accuracy
                            // radius, but never extend the hold based on another failed scan.
                            val held = Location(confirmed).apply {
                                accuracy = RadioFixReliability.mockAccuracyWithAge(confirmed.accuracy, ageMs)
                                time = System.currentTimeMillis()
                                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            }
                            if (mock?.publish(held) == true) {
                                prefs.edit()
                                    .putString(KEY_SOURCE, "LOCAL_RADIO_STALE")
                                    .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, true)
                                    .putString(KEY_STATE, FilterState.CORRECTING.name)
                                    .putInt(KEY_SCORE, 0)
                                    .putString(KEY_REASONS, "Временный пропуск радиосканирования; точность последней позиции снижена.")
                                    .putBoolean(KEY_HAS_OUTPUT, true)
                                    .putString(KEY_LAT, held.latitude.toString())
                                    .putString(KEY_LON, held.longitude.toString())
                                    .putFloat(KEY_ACCURACY, held.accuracy)
                                    .apply()
                                saveServiceStatus(
                                    "Radio scan missed · holding last mock GPS fix for " +
                                        (ageMs / 1_000L) + " s · uncertainty ±" + held.accuracy.toInt() + " m. " +
                                        rejectionReason
                                )
                            } else {
                                lastRadioMockFix = null
                                lastRadioMockFixAtElapsedMs = 0L
                                mock?.disable()
                                prefs.edit()
                                    .putBoolean(KEY_HAS_OUTPUT, false)
                                    .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                                    .putString(KEY_SOURCE, "NONE")
                                    .remove(KEY_LAT).remove(KEY_LON).remove(KEY_ACCURACY)
                                    .apply()
                                val reason = mock?.lastError ?: "Проверь выбор Clear GPS в настройках разработчика."
                            saveServiceStatus("Mock GPS не опубликован: $reason")
                            DiagnosticLogStore.log(this@LocationMonitorService, "MOCK", "Не удалось удержать последнюю mock-позицию: $reason", "ERROR")
                            }
                        } else {
                            // A previous position is retained for at most a bounded grace
                            // period. When it expires, remove the provider rather than silently
                            // presenting an old coordinate as current forever.
                            if (mockRequested) mock?.disable()
                            lastRadioMockFix = null
                            lastRadioMockFixAtElapsedMs = 0L
                            prefs.edit()
                                .putBoolean(KEY_HAS_OUTPUT, false)
                                .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                                .putString(KEY_SOURCE, "NONE")
                                .putString(KEY_STATE, FilterState.NO_FALLBACK.name)
                                .putInt(KEY_SCORE, 0).putString(KEY_REASONS, rejectionReason)
                                .remove(KEY_LAT).remove(KEY_LON).remove(KEY_ACCURACY)
                                .apply()
                            saveServiceStatus("Radio-only · no reliable local match. " + rejectionReason)
                        }
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        notification(prefs.getString(KEY_SERVICE_STATUS, "Radio-only") ?: "Radio-only"))
                } catch (error: Exception) {
                    DiagnosticLogStore.log(
                        this@LocationMonitorService, "RADIO_FIX",
                        "Исключение во время радиообновления: ${error.javaClass.simpleName}: ${error.message ?: "без описания"}",
                        "ERROR"
                    )
                    // Exceptions can happen while querying radio hardware or the SQLite database.
                    // Do not leave an old mock fix active indefinitely if all future scans fail.
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val confirmed = lastRadioMockFix
                    val confirmedAt = lastRadioMockFixAtElapsedMs
                    val intervalMs = configuredUpdateIntervalMillis()
                    val ageMs = if (confirmedAt > 0L && nowElapsedMs >= confirmedAt) nowElapsedMs - confirmedAt else Long.MAX_VALUE
                    val canHold = prefs.getBoolean(KEY_MOCK_ENABLED, false) && confirmed != null &&
                        RadioFixReliability.shouldHoldLastRadioMockFix(confirmedAt, nowElapsedMs, intervalMs)
                    if (canHold && confirmed != null) {
                        val held = Location(confirmed).apply {
                            accuracy = RadioFixReliability.mockAccuracyWithAge(confirmed.accuracy, ageMs)
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        }
                        if (mock?.publish(held) == true) {
                            prefs.edit()
                                .putBoolean(KEY_HAS_OUTPUT, true)
                                .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, true)
                                .putString(KEY_SOURCE, "LOCAL_RADIO_STALE")
                                .putFloat(KEY_ACCURACY, held.accuracy)
                                .putString(KEY_LAT, held.latitude.toString())
                                .putString(KEY_LON, held.longitude.toString())
                                .putString(KEY_REASONS, "Ошибка радиосканирования; используется краткий резерв.")
                                .apply()
                            saveServiceStatus(
                                "Radio scan error · holding last mock GPS fix for " + (ageMs / 1_000L) +
                                    " s · uncertainty ±" + held.accuracy.toInt() + " m"
                            )
                        } else {
                            mock?.disable()
                            lastRadioMockFix = null
                            lastRadioMockFixAtElapsedMs = 0L
                            prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                                .putBoolean(KEY_HAS_OUTPUT, false).putString(KEY_SOURCE, "NONE")
                                .remove(KEY_LAT).remove(KEY_LON).remove(KEY_ACCURACY).apply()
                            val reason = mock?.lastError ?: "Проверь выбор Clear GPS в настройках разработчика."
                            saveServiceStatus("Mock GPS не опубликован после ошибки радио-сканирования: $reason")
                            DiagnosticLogStore.log(this@LocationMonitorService, "MOCK", "Не удалось удержать mock-позицию после ошибки радио-сканирования: $reason", "ERROR")
                        }
                    } else {
                        val failureReason = "Ошибка радиосканирования: " + (error.message ?: error.javaClass.simpleName)
                        DiagnosticLogStore.log(this@LocationMonitorService, "RADIO_FIX", failureReason, "ERROR")
                        if (prefs.getBoolean(KEY_MOCK_ENABLED, false)) mock?.disable()
                        lastRadioMockFix = null
                        lastRadioMockFixAtElapsedMs = 0L
                        prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                            .putBoolean(KEY_HAS_OUTPUT, false).putString(KEY_SOURCE, "NONE")
                            .putString(KEY_STATE, FilterState.NO_FALLBACK.name)
                            .putString(KEY_REASONS, failureReason)
                            .putInt(KEY_SCORE, 0)
                            .remove(KEY_LAT).remove(KEY_LON).remove(KEY_ACCURACY).apply()
                        saveServiceStatus(failureReason)
                    }
                }
                val baseInterval = prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15).coerceIn(1, 30)
                val adaptive = prefs.getBoolean(ClearGpsPreferences.KEY_ADAPTIVE_SCAN, true)
                val obdSpeedKmh = if (adaptive) Obd2SharedState.freshSpeedKmh(prefs) else null
                // Respect the chosen interval; only halve it in faster motion when the user
                // explicitly keeps adaptive scanning enabled.
                val nextInterval = if (adaptive && obdSpeedKmh != null && obdSpeedKmh > 25f) {
                    (baseInterval / 2).coerceAtLeast(1)
                } else baseInterval
                delay(nextInterval * 1_000L)
            }
        }
    }

    /**
     * A successful setTestProviderLocation call is only a heartbeat, not a permanent lease.
     * If location delivery stops, remove the old mock fix so apps cannot consume it forever.
     */
    private fun startMockFreshnessWatchdog() {
        mockFreshnessJob?.cancel()
        mockFreshnessJob = serviceScope.launch {
            while (isActive && prefs.getBoolean(KEY_MONITORING, false)) {
                delay(MOCK_WATCHDOG_CHECK_INTERVAL_MS)
                if (!prefs.getBoolean(KEY_MOCK_ENABLED, false) ||
                    !prefs.getBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)) continue

                val nowElapsed = SystemClock.elapsedRealtime()
                val lastPublished = prefs.getLong(KEY_MOCK_LAST_PUBLISHED_ELAPSED_MS, 0L)
                val timeout = RadioFixReliability.mockPublicationTimeoutMillis(configuredUpdateIntervalMillis())
                val age = if (lastPublished > 0L && nowElapsed >= lastPublished) {
                    nowElapsed - lastPublished
                } else Long.MAX_VALUE
                if (age <= timeout) continue

                mock?.disable()
                lastRadioMockFix = null
                lastRadioMockFixAtElapsedMs = 0L
                val reason = "Mock GPS приостановлен: нет свежих координат более ${if (age == Long.MAX_VALUE) "неизвестного времени" else age / 1_000L} с."
                val currentState = prefs.getString(KEY_STATE, FilterState.WAITING.name)
                val edit = prefs.edit()
                    .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                    .putBoolean(KEY_HAS_OUTPUT, false)
                    .putString(KEY_SOURCE, "NONE")
                    .putString(KEY_REASONS, reason)
                    .remove(KEY_LAT)
                    .remove(KEY_LON)
                    .remove(KEY_ACCURACY)
                if (currentState == FilterState.NORMAL.name || currentState == FilterState.CORRECTING.name) {
                    edit.putString(KEY_STATE, FilterState.NO_FALLBACK.name).putInt(KEY_SCORE, 0)
                }
                edit.apply()
                saveServiceStatus(reason)
                DiagnosticLogStore.log(this@LocationMonitorService, "MOCK", reason, "WARN")
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(reason))
            }
        }
    }

    private fun stopTracking() {
        radioOnlyJob?.cancel()
        radioOnlyJob = null
        obd2FallbackJob?.cancel()
        obd2FallbackJob = null
        mockFreshnessJob?.cancel()
        mockFreshnessJob = null
        if (obd2FallbackPublished) clearObd2FallbackOutput("OBD-II fallback stopped with location monitoring")
        lastTrustedGpsFixForObd2 = null
        try { manager.removeUpdates(this) } catch (_: SecurityException) {}
        unregisterGnssStatus()
        mock?.disable()
        // This method also runs from onDestroy(), not only from ACTION_STOP. Clear every
        // shared position flag here so the UI cannot claim GPS is active after service death.
        prefs.edit()
            .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
            .putBoolean(KEY_HAS_OUTPUT, false)
            .putString(KEY_SOURCE, "NONE")
            .remove(KEY_LAT)
            .remove(KEY_LON)
            .remove(KEY_ACCURACY)
            .apply()
        lastRadioMockFix = null
        lastRadioMockFixAtElapsedMs = 0L
        activePositionMode = null
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
        if (LocationFilterEngine.isMockLocation(location)) {
            DiagnosticLogStore.log(this, "GNSS", "Mock-кallback от собственного провайдера проигнорирован")
            return
        }
        if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            location.elapsedRealtimeNanos <= 0L ||
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0f) {
            DiagnosticLogStore.log(this, "GNSS", "Некорректный Android location callback проигнорирован", "WARN")
            return
        }
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
        DiagnosticLogStore.log(
            this, "GNSS",
            "Позиция от Android: provider=${location.provider ?: "unknown"}, filter=${result.state}, source=${result.source}, " +
                "risk=${result.riskScore}, hasOutput=${result.outputLocation != null}"
        )
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
            if (mock?.publish(result.outputLocation) == true) {
                prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, true).apply()
                val accuracyText = if (result.outputLocation.hasAccuracy()) " · ±" + result.outputLocation.accuracy.toInt() + " m" else ""
                saveServiceStatus("Mock GPS published · " + result.source + accuracyText)
                DiagnosticLogStore.log(this, "MOCK", "Mock GPS активен; источник=${result.source}, accuracy=${result.outputLocation.accuracy.toInt()} m")
            } else {
                prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
                val reason = mock?.lastError ?: "Проверь выбор Clear GPS в настройках разработчика."
                saveServiceStatus("Mock GPS недоступен: $reason")
                DiagnosticLogStore.log(this, "MOCK", "Mock-публикация из location filter не удалась: $reason", "ERROR")
            }
        } else if (prefs.getBoolean(KEY_MOCK_ENABLED, false)) {
            // Never leave an older test-provider position active when the filter has no safe
            // output candidate. The user can see that mock output is currently unavailable.
            mock?.disable()
            prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            saveServiceStatus("Mock GPS paused: no safe location candidate")
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
        // A signal-only GNSS warning has no safe replacement coordinate. Stop mock publication
        // immediately instead of leaving the last test-provider position looking current.
        if (result.state != FilterState.NORMAL && prefs.getBoolean(KEY_MOCK_ENABLED, false)) {
            mock?.disable()
            prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            val status = "Mock GPS приостановлен: GNSS-фильтр сообщил ${result.state}"
            saveServiceStatus(status)
            DiagnosticLogStore.log(this, "MOCK", status, "WARN")
        } else {
            DiagnosticLogStore.log(
                this, "GNSS",
                "Оценка спутникового сигнала: state=${result.state}, risk=${result.riskScore}"
            )
        }
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
            // This decision carries no safe position. Clear the last candidate so stale values
            // cannot remain visible or be mistaken for an active mock fix while GNSS is suspect.
            prefs.edit()
                .putString(KEY_STATE, result.state.name)
                .putInt(KEY_SCORE, result.riskScore)
                .putBoolean(KEY_IN_ZONE, result.inRiskZone)
                .putString(KEY_SOURCE, result.source)
                .putString(KEY_REASONS, result.reasons.joinToString("\n"))
                .putBoolean(KEY_HAS_OUTPUT, false)
                .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                .remove(KEY_LAT)
                .remove(KEY_LON)
                .remove(KEY_ACCURACY)
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
        } ?: run {
            edit.remove(KEY_LAT).remove(KEY_LON).remove(KEY_ACCURACY)
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
        const val KEY_MOCK_OUTPUT_ACTIVE = "mock_output_active"
        const val KEY_MOCK_LAST_PUBLISHED_ELAPSED_MS = "mock_last_published_elapsed_ms"
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
        private const val MOCK_WATCHDOG_CHECK_INTERVAL_MS = 5_000L
        private const val RADIO_SCAN_INTERVAL_MS = 12_000L
        private const val RADIO_MIN_MATCHES = 2
        private const val RADIO_FALLBACK_MAX_AGE_MS = 60_000L
        // Radio-only mode is an approximate estimate; publish multi-signal fixes with
        // honest uncertainty instead of dropping everything above a strict 1 km cutoff.
        private const val RADIO_FALLBACK_MAX_ACCURACY_METERS = 50_000.0
        private const val MANUAL_FALLBACK_ACCURACY_METERS = 1_500f
        private const val MANUAL_FALLBACK_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
