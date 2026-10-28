package org.cleargps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import java.io.File
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cleargps.core.FilterDecision
import org.cleargps.core.FilterState
import org.cleargps.core.GnssRfSnapshot
import org.cleargps.core.LocationFilterEngine
import org.cleargps.core.RiskZoneMembershipTracker
import org.cleargps.core.ZonePoint
import org.cleargps.core.ZoneStore
import org.cleargps.core.GnssDeviceBaselineSnapshot
import org.cleargps.core.GnssReplayMeasurement
import org.cleargps.core.GnssReplaySatellite
import org.cleargps.core.RadioPositionSnapshot
import org.cleargps.fusion.HorizontalAccelerationSample
import org.cleargps.fusion.PositionFusionMotionModel
import org.cleargps.fusion.PositionFusionTimeline

private data class LastResortFallback(val source: String, val location: Location)

class LocationMonitorService : Service(), LocationListener {
    private lateinit var manager: LocationManager
    private lateinit var prefs: android.content.SharedPreferences
    private val engine = LocationFilterEngine()
    // Admits trusted GNSS and bounded radio measurements into a chronological replay timeline.
    private val positionFusionEngine = PositionFusionTimeline()
    private val riskZoneMembership = RiskZoneMembershipTracker()
    private var mock: MockLocationOutput? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioOnlyJob: Job? = null
    private var radioFusionJob: Job? = null
    private var replayRadioCaptureJob: Job? = null
    private var obd2FallbackJob: Job? = null
    private var mockFreshnessJob: Job? = null
    private var beaconDbOutputJob: Job? = null
    private var beaconDbOutputGeneration = 0L
    private var beaconDbOutputPositionMode: String? = null
    private var beaconDbOutputUntilElapsed = 0L
    private var lastTrustedGpsFixForObd2: Location? = null
    private var obd2FallbackPublished = false
    private var lastRadioMockFix: Location? = null
    private var lastRadioMockFixAtElapsedMs = 0L
    private var activePositionMode: String? = null
    private var gnssStatusRegistered = false
    private var gnssMeasurementsRegistered = false
    private var serviceHeartbeatJob: Job? = null
    private var barometerReader: AndroidBarometerSensorReader? = null
    private var imuMotionReader: AndroidImuMotionReader? = null
    private var demSampleJob: Job? = null
    private var demSampleGeneration = 0L
    private var lastDemSampleRequestElapsedRealtimeNanos = 0L
    private var lastAltitudeDiagnosticKey: String? = null

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
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            lastStatusAtNanos = nowNanos
            val liveSatellites = (0 until count).mapNotNull { index ->
                val svid = status.getSvid(index)
                if (svid !in 1..4096) return@mapNotNull null
                val carrierFrequencyHz = try {
                    if (status.hasCarrierFrequencyHz(index)) {
                        status.getCarrierFrequencyHz(index).toDouble().takeIf { it.isFinite() && it > 0.0 }
                    } else null
                } catch (_: RuntimeException) {
                    null
                }
                val basebandCn0 = if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        if (status.hasBasebandCn0DbHz(index)) {
                            status.getBasebandCn0DbHz(index).toDouble().takeIf { it.isFinite() && it > 0.0 }
                        } else null
                    } catch (_: RuntimeException) {
                        null
                    }
                } else null
                GnssSatelliteReading(
                    constellationType = status.getConstellationType(index),
                    svid = svid,
                    cn0DbHz = status.getCn0DbHz(index).toDouble().takeIf { it.isFinite() && it > 0.0 },
                    elevationDegrees = status.getElevationDegrees(index).toDouble().takeIf { it.isFinite() },
                    azimuthDegrees = status.getAzimuthDegrees(index).toDouble().takeIf { it.isFinite() },
                    usedInFix = status.usedInFix(index),
                    reportedCarrierFrequencyHz = carrierFrequencyHz,
                    basebandCn0DbHz = basebandCn0
                )
            }
            GnssDiagnosticsLiveStore.updateSatelliteStatus(nowNanos, liveSatellites)
            val signalSnapshot = org.cleargps.core.GnssSignalSnapshot(
                elapsedRealtimeNanos = nowNanos,
                satellitesVisible = count,
                satellitesUsedInFix = (0 until count).count { status.usedInFix(it) },
                meanCn0DbHz = mean,
                cn0SpreadDb = spread,
                constellationCount = constellations,
                strongSignalCount = cn0Values.count { it >= 32.0 }
            )
            try {
                GnssReplayRecorder.recordSignal(this@LocationMonitorService, nowNanos, signalSnapshot,
                    liveSatellites.map { r -> GnssReplaySatellite(
                        r.constellationType, r.svid, r.cn0DbHz, r.elevationDegrees, r.azimuthDegrees,
                        r.usedInFix, r.reportedCarrierFrequencyHz, r.basebandCn0DbHz
                    ) })
            } catch (_: RuntimeException) {
                // Replay capture must never interrupt live GNSS callbacks.
            }
            val assessment = engine.observeGnssSignal(signalSnapshot)
            persistGnssReceptionDiagnostics()
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
                val liveMeasurements = measurements.mapNotNull { measurement ->
                    try {
                        val frequencyHz = measurement.carrierFrequencyHz.toDouble()
                            .takeIf { it.isFinite() && it > 0.0 }
                        val satelliteAgcDb = if (Build.VERSION.SDK_INT < 33) {
                            try {
                                if (measurement.hasAutomaticGainControlLevelDb()) {
                                    measurement.getAutomaticGainControlLevelDb().takeIf(Double::isFinite)
                                } else null
                            } catch (_: RuntimeException) {
                                null
                            }
                        } else null
                        if (frequencyHz == null && satelliteAgcDb == null) null
                        else GnssSatelliteMeasurement(
                            constellationType = measurement.constellationType,
                            svid = measurement.svid,
                            carrierFrequencyHz = frequencyHz,
                            agcDb = satelliteAgcDb
                        )
                    } catch (_: RuntimeException) {
                        null
                    }
                }
                val agcBands = if (Build.VERSION.SDK_INT >= 33) {
                    event.gnssAutomaticGainControls.mapNotNull { reading ->
                        val frequencyHz = reading.carrierFrequencyHz.toDouble()
                            .takeIf { it.isFinite() && it > 0.0 }
                        val levelDb = reading.levelDb.takeIf(Double::isFinite)
                        if (frequencyHz == null || levelDb == null) null
                        else GnssAgcBandReading(
                            constellationType = reading.constellationType,
                            carrierFrequencyHz = frequencyHz,
                            levelDb = levelDb
                        )
                    }
                } else emptyList()
                GnssDiagnosticsLiveStore.updateMeasurements(nowNanos, liveMeasurements, agcBands)
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
                val rfSnapshot = GnssRfSnapshot(
                    elapsedRealtimeNanos = nowNanos,
                    signalCount = signalCount,
                    constellationCount = constellationCount,
                    meanCn0DbHz = meanCn0,
                    meanAgcDb = meanAgc,
                    agcSampleCount = agcValues.size
                )
                try {
                    val replayMeasurements = measurements.mapNotNull { m ->
                        try {
                            val frequency = m.carrierFrequencyHz.toDouble().takeIf { it.isFinite() && it > 0.0 }
                            val agc = if (Build.VERSION.SDK_INT < 33) {
                                try { if (m.hasAutomaticGainControlLevelDb()) m.getAutomaticGainControlLevelDb().takeIf(Double::isFinite) else null }
                                catch (_: RuntimeException) { null }
                            } else null
                            GnssReplayMeasurement(
                                m.constellationType, m.svid,
                                m.cn0DbHz.toDouble().takeIf { it.isFinite() && it > 0.0 }, frequency, agc,
                                m.pseudorangeRateMetersPerSecond.takeIf(Double::isFinite),
                                m.pseudorangeRateUncertaintyMetersPerSecond.takeIf { it.isFinite() && it >= 0.0 },
                                m.receivedSvTimeNanos.takeIf { it >= 0L },
                                m.receivedSvTimeUncertaintyNanos.takeIf { it >= 0L }, m.state
                            )
                        } catch (_: RuntimeException) { null }
                    }
                    GnssReplayRecorder.recordRf(this@LocationMonitorService, nowNanos, rfSnapshot, replayMeasurements)
                } catch (_: RuntimeException) {
                    // Replay capture must never interrupt live measurements processing.
                }
                val assessment = engine.observeGnssMeasurements(rfSnapshot)
                persistGnssReceptionDiagnostics()
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
        GnssReplayRecorder.reconcileInterruptedSession(this)
        GnssDeviceBaselineStore.load(this)?.let { savedBaseline ->
            if (!engine.restoreDeviceBaseline(savedBaseline)) GnssDeviceBaselineStore.clear(this)
        }
        mock = MockLocationOutput(this)
        barometerReader = AndroidBarometerSensorReader(this)
        imuMotionReader = AndroidImuMotionReader(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Clear GPS monitoring", NotificationManager.IMPORTANCE_LOW))
        }
        startServiceHeartbeatLoop()
    }

    private fun startServiceHeartbeatLoop() {
        serviceHeartbeatJob?.cancel()
        serviceHeartbeatJob = serviceScope.launch {
            while (isActive) {
                if (prefs.getBoolean(KEY_MONITORING, false)) {
                    prefs.edit()
                        .putLong(ServiceRecoveryWatchdog.KEY_SERVICE_HEARTBEAT_ELAPSED_MS, SystemClock.elapsedRealtime())
                        .apply()
                }
                delay(SERVICE_HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean(KEY_MONITORING, false).apply()
                ServiceRecoveryWatchdog.cancel(this)
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
            ACTION_PUBLISH_BEACONDB_FIX -> {
                startForeground(NOTIFICATION_ID, notification("Starting explicit BeaconDB mock-location output"))
                val request = intent ?: return START_NOT_STICKY
                val latitude = request.getDoubleExtra(EXTRA_BEACONDB_LATITUDE, Double.NaN)
                val longitude = request.getDoubleExtra(EXTRA_BEACONDB_LONGITUDE, Double.NaN)
                val accuracy = request.getDoubleExtra(EXTRA_BEACONDB_ACCURACY, Double.NaN)
                val safeMode = CrashLoopGuard.isSafeModeActive(this)
                val mayPublish = !safeMode &&
                    prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) &&
                    prefs.getBoolean(KEY_MONITORING, false) &&
                    prefs.getBoolean(KEY_MOCK_ENABLED, false)
                if (!mayPublish) {
                    val reason = if (safeMode) {
                        "BeaconDB mock output blocked by crash-loop safe mode. Re-enable the source explicitly after safe mode expires."
                    } else {
                        "BeaconDB mock output not started: keep BeaconDB access enabled and turn on «Включить фикс» + Mock GPS."
                    }
                    saveServiceStatus(reason)
                    DiagnosticLogStore.log(
                        this, "MOCK",
                        if (safeMode) "BeaconDB output refused by crash-loop safe mode"
                        else "BeaconDB output refused because its opt-in/monitor/mock switch is off",
                        "WARN"
                    )
                    if (prefs.getBoolean(KEY_MONITORING, false)) {
                        startTracking()
                        return START_STICKY
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startBeaconDbMockOutput(latitude, longitude, accuracy)
                return START_STICKY
            }
            ACTION_REPLAY_CAPTURE_CHANGED -> {
                if (!prefs.getBoolean(KEY_MONITORING, false)) {
                    stopReplayRadioCapture()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val status = prefs.getString(KEY_SERVICE_STATUS, "Monitoring") ?: "Monitoring"
                startForeground(NOTIFICATION_ID, notification(status))
                configureReplayRadioCapture()
                return START_STICKY
            }
            ACTION_DISABLE_MOCK -> {
                cancelBeaconDbMockOutput()
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
            ACTION_RECOVER -> {
                // A recovery request may race with the user turning monitoring off.
                // Re-check the persisted intent when the service receives the request.
                if (!prefs.getBoolean(KEY_MONITORING, false)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Restoring location monitor"))
                prefs.edit()
                    .putLong(ServiceRecoveryWatchdog.KEY_SERVICE_HEARTBEAT_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .apply()
                ServiceRecoveryWatchdog.ensureScheduled(this)
                startTracking()
                return START_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification("Starting location monitor"))
                prefs.edit()
                    .putBoolean(KEY_MONITORING, true)
                    .putLong(ServiceRecoveryWatchdog.KEY_SERVICE_HEARTBEAT_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .apply()
                ServiceRecoveryWatchdog.ensureScheduled(this)
                startTracking()
                return START_STICKY
            }
            null -> {
                // START_STICKY can redeliver a null intent after process death. Restore only
                // a monitor that was explicitly enabled before the process disappeared.
                if (!prefs.getBoolean(KEY_MONITORING, false)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Restoring location monitor"))
                ServiceRecoveryWatchdog.ensureScheduled(this)
                startTracking()
                return START_STICKY
            }
            else -> {
                DiagnosticLogStore.log(this, "SERVICE", "Ignored unsupported service action", "WARN")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun configuredUpdateIntervalMillis(): Long {
        val configured = prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15)
        val profile = BatteryProfile.fromKey(prefs.getString(ClearGpsPreferences.KEY_BATTERY_PROFILE, BatteryProfile.NORMAL.key))
        return BatteryProfilePolicy.profiledIntervalSeconds(configured, profile) * 1_000L
    }

    /**
     * Broadcast one explicitly requested BeaconDB cell-only estimate for a short, bounded window.
     * No additional BeaconDB request is made here. Both mock providers are updated while all
     * physical input listeners are paused, then removed before the selected source resumes.
     */
    private fun startBeaconDbMockOutput(latitude: Double, longitude: Double, accuracyMeters: Double) {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracyMeters.isFinite() || accuracyMeters !in 1.0..10_000_000.0) {
            saveServiceStatus("BeaconDB output refused: invalid coordinates or accuracy.")
            DiagnosticLogStore.log(this, "MOCK", "BeaconDB output refused because estimate was invalid", "ERROR")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            saveServiceStatus("BeaconDB mock output unavailable: location permission is missing.")
            return
        }

        // stopTracking cancels old output loops and removes previously registered test providers.
        stopTracking()
        val safeMode = CrashLoopGuard.isSafeModeActive(this)
        if (safeMode ||
            !prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) ||
            !prefs.getBoolean(KEY_MONITORING, false) ||
            !prefs.getBoolean(KEY_MOCK_ENABLED, false)) {
            val reason = if (safeMode) {
                "BeaconDB mock output cancelled by crash-loop safe mode."
            } else {
                "BeaconDB mock output cancelled because a required setting was turned off."
            }
            saveServiceStatus(reason)
            if (safeMode) DiagnosticLogStore.log(this, "MOCK", reason, "WARN")
            return
        }

        val generation = beaconDbOutputGeneration
        val selectedMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
        beaconDbOutputPositionMode = selectedMode
        val startedAtElapsed = SystemClock.elapsedRealtime()
        val deadlineElapsed = startedAtElapsed + BeaconDbMockPublicationPolicy.OUTPUT_WINDOW_MS
        beaconDbOutputUntilElapsed = deadlineElapsed
        startMockFreshnessWatchdog()
        beaconDbOutputJob = serviceScope.launch {
            var publicationFailure: String? = null
            try {
                while (isActive && generation == beaconDbOutputGeneration &&
                    prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) &&
                    prefs.getBoolean(KEY_MONITORING, false) &&
                    prefs.getBoolean(KEY_MOCK_ENABLED, false)) {
                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (BeaconDbMockPublicationPolicy.hasExpired(nowElapsed, deadlineElapsed)) break

                    // The queried cell set is a snapshot, not continuous tracking. Grow the
                    // reported uncertainty while the same estimate is held for external apps.
                    val effectiveAccuracy = BeaconDbMockPublicationPolicy.effectiveAccuracyMeters(
                        accuracyMeters, nowElapsed - startedAtElapsed
                    )
                    val location = Location(LocationManager.GPS_PROVIDER).apply {
                        this.latitude = latitude
                        this.longitude = longitude
                        this.accuracy = effectiveAccuracy.toFloat()
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }

                    if (mock?.publish(
                            location, alsoPublishNetworkProvider = true, publishGpsProvider = true
                        ) != true) {
                        publicationFailure = mock?.lastError ?: "Android rejected the BeaconDB mock location."
                        saveServiceStatus("BeaconDB result was calculated, but mock output failed: $publicationFailure")
                        break
                    }

                    prefs.edit()
                        .putBoolean(KEY_HAS_OUTPUT, true)
                        .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, true)
                        .putString(KEY_SOURCE, "BEACONDB")
                        .putString(KEY_STATE, FilterState.CORRECTING.name)
                        .putInt(KEY_SCORE, 0)
                        .putBoolean(KEY_IN_ZONE, false)
                        .putString(KEY_REASONS, "Manual BeaconDB estimate from cellular identifiers; position is held, not continuously re-queried.")
                        .putString(KEY_LAT, latitude.toString())
                        .putString(KEY_LON, longitude.toString())
                        .putFloat(KEY_ACCURACY, effectiveAccuracy.toFloat())
                        .apply()

                    val remainingSeconds = ((deadlineElapsed - nowElapsed).coerceAtLeast(0L) + 999L) / 1_000L
                    val status = radioMockProviderStatus() + " · BeaconDB cell estimate · expires in " +
                        remainingSeconds + " s · uncertainty ±" + effectiveAccuracy.toInt() + " m"
                    saveServiceStatus(status)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))

                    delay(
                        BeaconDbMockPublicationPolicy.nextDelayMillis(
                            SystemClock.elapsedRealtime(), deadlineElapsed
                        )
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } finally {
                // A user stop/source switch invalidates the generation, so this block must not
                // restart tracking over a newer service action.
                if (generation == beaconDbOutputGeneration) {
                    beaconDbOutputJob = null
                    beaconDbOutputPositionMode = null
                    beaconDbOutputUntilElapsed = 0L
                    mock?.disable()
                    prefs.edit()
                        .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                        .putBoolean(KEY_HAS_OUTPUT, false)
                        .putString(KEY_SOURCE, "NONE")
                        .remove(KEY_LAT)
                        .remove(KEY_LON)
                        .remove(KEY_ACCURACY)
                        .apply()
                    val reason = publicationFailure
                        ?: "BeaconDB mock estimate expired after 2 minutes; resuming the selected position source."
                    saveServiceStatus(reason)
                    DiagnosticLogStore.log(this@LocationMonitorService, "MOCK", reason,
                        if (publicationFailure == null) "INFO" else "ERROR")
                    if (prefs.getBoolean(KEY_MONITORING, false)) {
                        activePositionMode = null
                        startTracking()
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun cancelBeaconDbMockOutput() {
        beaconDbOutputGeneration += 1L
        beaconDbOutputUntilElapsed = 0L
        beaconDbOutputPositionMode = null
        beaconDbOutputJob?.cancel()
        beaconDbOutputJob = null
    }

    private fun startTracking() {
        if (beaconDbOutputJob?.isActive == true &&
            prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) &&
            prefs.getBoolean(KEY_MONITORING, false) &&
            prefs.getBoolean(KEY_MOCK_ENABLED, false)) {
            val requestedMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
            if (requestedMode == beaconDbOutputPositionMode) {
                // Same-mode refreshes (for example an interval change) must not replace the
                // user-requested BeaconDB result before its bounded expiry.
                return
            }
            // A real source-mode change immediately cancels the one-shot override.
            cancelBeaconDbMockOutput()
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_MONITORING, false).putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            ServiceRecoveryWatchdog.cancel(this)
            mock?.disable()
            saveServiceStatus("Location permission missing")
            DiagnosticLogStore.log(this, "SERVICE", "Мониторинг не запущен: отсутствует разрешение геолокации", "ERROR")
            stopSelf()
            return
        }

        prefs.edit()
            .putLong(ServiceRecoveryWatchdog.KEY_SERVICE_HEARTBEAT_ELAPSED_MS, SystemClock.elapsedRealtime())
            .apply()

        try {
            manager.removeUpdates(this)
            unregisterGnssStatus()
            radioOnlyJob?.cancel()
            radioOnlyJob = null
            radioFusionJob?.cancel()
            radioFusionJob = null
            obd2FallbackJob?.cancel()
            obd2FallbackJob = null
            if (obd2FallbackPublished) clearObd2FallbackOutput("OBD-II fallback cleared while restarting location monitoring")

            val nextPositionMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
            if (nextPositionMode in setOf("radio", "radio_obd2")) GnssReplayRecorder.stop(this, "SERVICE_STOP")
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
                positionFusionEngine.reset()
                demSampleGeneration += 1L
                demSampleJob?.cancel()
                demSampleJob = null
                lastDemSampleRequestElapsedRealtimeNanos = 0L
                SrtmElevationDiagnosticsLiveStore.clear()
                AltitudeDiagnosticsLiveStore.clear()
                lastAltitudeDiagnosticKey = null
                prefs.edit()
                    .putBoolean(KEY_HAS_OUTPUT, false)
                    .putString(KEY_SOURCE, "NONE")
                    .remove(KEY_LAT)
                    .remove(KEY_LON)
                    .remove(KEY_ACCURACY)
                    .apply()
            }
            activePositionMode = nextPositionMode
            // IMU may adjust a short prediction, but never creates a position anchor or trust signal.
            barometerReader?.start()
            imuMotionReader?.start()
            configureReplayRadioCapture(nextPositionMode)
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
            startRadioFusionLoop(nextPositionMode)
            saveServiceStatus("Monitoring")
        } catch (error: SecurityException) {
            prefs.edit().putBoolean(KEY_MONITORING, false).putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            ServiceRecoveryWatchdog.cancel(this)
            mock?.disable()
            saveServiceStatus("Location permission denied")
            DiagnosticLogStore.log(this, "SERVICE", "Android отказал в разрешении геолокации: ${error.message ?: "SecurityException"}", "ERROR")
            stopSelf()
        } catch (error: IllegalArgumentException) {
            prefs.edit().putBoolean(KEY_MONITORING, false).putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
            ServiceRecoveryWatchdog.cancel(this)
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
                val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                val speedSamples = lastFix?.let {
                    Obd2SharedState.speedSamplesSince(it.elapsedRealtimeNanos, nowElapsedRealtimeNanos)
                }.orEmpty()
                val prediction = if (filterAllowsFallback && lastFix != null && lastFix.hasBearing()) {
                    Obd2LocationFusion.predictFromGps(
                        lastFix = MapCoordinate(lastFix.latitude, lastFix.longitude),
                        baseAccuracyMeters = lastFix.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                        bearingDegrees = lastFix.bearing.toDouble(),
                        fixElapsedRealtimeNanos = lastFix.elapsedRealtimeNanos,
                        speedSamples = speedSamples,
                        nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
                    )
                } else null

                if (prediction != null && speedSamples.isNotEmpty() && lastFix != null) {
                    val projected = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = prediction.coordinate.latitude
                        longitude = prediction.coordinate.longitude
                        accuracy = prediction.accuracyMeters.toFloat().coerceAtLeast(1f)
                        time = now
                        elapsedRealtimeNanos = nowElapsedRealtimeNanos
                        speed = speedSamples.last().speedKmh / 3.6f
                        bearing = lastFix.bearing
                    }
                    val mockEnabled = prefs.getBoolean(KEY_MOCK_ENABLED, false)
                    val canPublish = !mockEnabled || mock?.publish(
                        projected, publishGpsProvider = false
                    ) == true
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
                        speedSamples.isEmpty() -> "OBD-II fallback expired; waiting for fresh vehicle speed"
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

        if (mock?.publish(
                held, alsoPublishNetworkProvider = true, publishGpsProvider = true
            ) != true) {
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
        val status = radioMockProviderStatus() + " · refreshing recent radio fix · cached age " +
            (ageMs / 1_000L) + " s · uncertainty ±" + held.accuracy.toInt() + " m"
        saveServiceStatus(status)
        DiagnosticLogStore.log(this, "MOCK", "Недавняя радиопозиция опубликована до завершения нового сканирования; age=" + (ageMs / 1_000L) + "s")
    }

    private fun radioMockProviderStatus(): String {
        val networkProviderPublished =
            mock?.lastPublishedProviders?.contains(LocationManager.NETWORK_PROVIDER) == true
        val base = if (networkProviderPublished) {
            "Mock GPS + network published"
        } else {
            "Mock GPS published · network provider unavailable"
        }
        val warning = mock?.lastWarning
        return if (warning.isNullOrBlank()) base else "$base · $warning"
    }

    private fun configureReplayRadioCapture(positionMode: String? =
        prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
    ) {
        val shouldCapture = GnssReplayRadioCapturePolicy.shouldCapture(
            monitoring = prefs.getBoolean(KEY_MONITORING, false),
            recording = GnssReplayRecorder.isRecording(this),
            positionMode = positionMode
        )
        if (!shouldCapture) {
            stopReplayRadioCapture()
            return
        }
        if (replayRadioCaptureJob?.isActive == true) return

        val repository = RadioGeolocationRepository(applicationContext)
        replayRadioCaptureJob = serviceScope.launch {
            while (isActive && GnssReplayRadioCapturePolicy.shouldCapture(
                monitoring = prefs.getBoolean(KEY_MONITORING, false),
                recording = GnssReplayRecorder.isRecording(this@LocationMonitorService),
                positionMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
            )) {
                try {
                    val observations = repository.captureReplaySnapshot(
                        useWifi = prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true),
                        useCells = prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true),
                        useBle = prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)
                    )
                    if (observations > 0) {
                        DiagnosticLogStore.log(
                            this@LocationMonitorService, "REPLAY_RADIO",
                            "Captured $observations radio observations for local replay"
                        )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DiagnosticLogStore.log(
                        this@LocationMonitorService, "REPLAY_RADIO",
                        "Radio replay sample skipped: ${error.javaClass.simpleName}", "WARN"
                    )
                }
                if (!GnssReplayRadioCapturePolicy.shouldCapture(
                    monitoring = prefs.getBoolean(KEY_MONITORING, false),
                    recording = GnssReplayRecorder.isRecording(this@LocationMonitorService),
                    positionMode = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
                )) break
                delay(
                    GnssReplayRadioCapturePolicy.intervalMillis(
                        BatteryProfilePolicy.profiledIntervalSeconds(
                            prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15),
                            BatteryProfile.fromKey(prefs.getString(ClearGpsPreferences.KEY_BATTERY_PROFILE, BatteryProfile.NORMAL.key))
                        )
                    )
                )
            }
        }
    }

    private fun stopReplayRadioCapture() {
        replayRadioCaptureJob?.cancel()
        replayRadioCaptureJob = null
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
                    val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val estimateAgeMillis = estimate?.let { nowWallMillis - it.updatedAt } ?: Long.MAX_VALUE
                    val estimateEvidenceRecent = estimateAgeMillis in 0..RadioFixReliability.WIFI_MAX_CACHE_AGE_MS
                    // Radio-only output must retain the timestamp of the actual matched observations.
                    // Wall-clock freshness alone cannot reconstruct Android elapsed realtime safely.
                    val measurementElapsedRealtimeNanos = estimate?.measurementElapsedRealtimeNanos
                    val measurementTimestampUsable = RadioFixReliability.hasUsableRadioMeasurementTimestamp(
                        measurementElapsedRealtimeNanos = measurementElapsedRealtimeNanos,
                        nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
                    )
                    val cachedEstimateAllowed = estimate != null && RadioFixReliability.canPublishRadioEstimate(
                        estimateUpdatedAtMillis = estimate.updatedAt,
                        nowWallMillis = nowWallMillis,
                        fromCache = result.fromCache,
                        lastConfirmedAtElapsedMillis = lastRadioMockFixAtElapsedMs,
                        nowElapsedMillis = nowElapsedMillis,
                        updateIntervalMillis = configuredUpdateIntervalMillis()
                    )
                    val estimateAllowedForOutput = estimateEvidenceRecent &&
                        measurementTimestampUsable &&
                        (!mockRequested || cachedEstimateAllowed)
                    val rejectionReason = when {
                        estimate == null && currentSignalCount < 2 ->
                            "Сейчас обнаружено только $currentSignalCount радиосигналов. Для расчёта нужны минимум 2 актуальных сигнала; локальная база знает ${result.knownRadioCount} из них, пригодных координатных записей: ${result.coordinateRadioCount}."
                        estimate == null ->
                            "Текущий скан увидел $currentSignalCount радиосигналов; совпадений идентификаторов в базе: ${result.knownRadioCount}, пригодных координатных записей: ${result.coordinateRadioCount}. Однозначная координата не построена."
                        !estimateEvidenceRecent ->
                            "Оценка отклонена: возраст радиоданных ${(estimateAgeMillis.coerceAtLeast(0L) / 1_000L)} с превышает допустимый предел ${RadioFixReliability.WIFI_MAX_CACHE_AGE_MS / 1_000L} с."
                        measurementElapsedRealtimeNanos == null ->
                            "Оценка отклонена: у совпавших радионаблюдений нет исходной монотонной метки времени."
                        !measurementTimestampUsable ->
                            "Оценка отклонена: исходная монотонная метка находится в будущем или старше ${RadioFixReliability.WIFI_MAX_CACHE_AGE_MS / 1_000L} с."
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
                            // Preserve the source measurement time; never freshen cached radio evidence.
                            elapsedRealtimeNanos = requireNotNull(measurementElapsedRealtimeNanos)
                        }
                        val mockPublished = !mockRequested || mock?.publish(
                            output, alsoPublishNetworkProvider = true, publishGpsProvider = true
                        ) == true
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
                                (if (mockRequested) radioMockProviderStatus() else "Radio estimate only") +
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
                            if (mock?.publish(
                                    held, alsoPublishNetworkProvider = true, publishGpsProvider = true
                                ) == true) {
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
                                    radioMockProviderStatus() + " · scan missed · holding last fix for " +
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
                        if (mock?.publish(
                                held, alsoPublishNetworkProvider = true, publishGpsProvider = true
                            ) == true) {
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
                                radioMockProviderStatus() + " · scan error · holding last fix for " + (ageMs / 1_000L) +
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
                val obdSpeedKmh = if (adaptive) Obd2SharedState.freshSpeedKmh(prefs)?.toFloat() else null
                val profile = BatteryProfile.fromKey(
                    prefs.getString(ClearGpsPreferences.KEY_BATTERY_PROFILE, BatteryProfile.NORMAL.key)
                )
                val nextInterval = BatteryProfilePolicy.intervalSeconds(baseInterval, obdSpeedKmh, adaptive, profile)
                delay(nextInterval * 1_000L)
            }
        }
    }

    /**
     * Periodically obtains a local radio estimate in Auto mode. The radio sample enters the
     * fusion timeline only while the independent trust filter still accepts physical GPS as NORMAL.
     * Radio scans never run in the explicit GPS-only or Radio-only modes.
     */
    private fun startRadioFusionLoop(positionMode: String) {
        radioFusionJob?.cancel()
        radioFusionJob = null
        if (positionMode != "auto") return

        radioFusionJob = serviceScope.launch {
            val repository = RadioGeolocationRepository(applicationContext)
            while (isActive &&
                prefs.getBoolean(KEY_MONITORING, false) &&
                prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") == "auto"
            ) {
                // Android throttles Wi-Fi scans. Do not turn the configured fix interval into a
                // one-second radio scan loop; keep this source deliberately low cadence.
                delay(maxOf(configuredUpdateIntervalMillis(), RadioFixReliability.WIFI_MIN_REQUEST_INTERVAL_MS))
                if (!radioScanEvidenceCurrentlyAllowed()) continue

                try {
                    val scan = repository.scan(
                        record = prefs.getBoolean(ClearGpsPreferences.KEY_RECORDING, false),
                        reference = null,
                        mode = "auto",
                        useWifi = prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true),
                        useCells = prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true),
                        useBle = prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)
                    )
                    val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val radioEstimate = scan.estimate
                    val measurement = radioEstimate?.let {
                        PositionFusionLocationAdapter.radioMeasurement(it, nowElapsedRealtimeNanos)
                    }
                    if (measurement == null || radioEstimate == null) {
                        DiagnosticLogStore.log(
                            this@LocationMonitorService, "FUSION",
                            "Radio measurement skipped: no sufficiently fresh, timestamped local estimate"
                        )
                        continue
                    }
                    val activeZones = ZoneStore.loadAll(this@LocationMonitorService)
                        .filter { it.enabled }
                        .map { it.points }
                    if (!RadioFusionZonePolicy.allowsFusion(measurement, activeZones)) {
                        DiagnosticLogStore.log(
                            this@LocationMonitorService, "FUSION",
                            "Radio measurement skipped because its point/uncertainty intersects an enabled risk zone or is too coarse to rule that out"
                        )
                        continue
                    }

                    // This is a separate supporting input. It does not initialize position fusion.
                    val radioDecision = engine.observeRadioPosition(
                        RadioPositionSnapshot(
                            latitude = measurement.latitude,
                            longitude = measurement.longitude,
                            accuracyMeters = measurement.horizontalUncertaintyMeters,
                            elapsedRealtimeNanos = measurement.elapsedRealtimeNanos
                        )
                    )
                    persistRadioEvidenceDecision(radioDecision)
                    if (radioDecision.state != FilterState.NORMAL) {
                        positionFusionEngine.reset()
                        val mockWasActive = prefs.getBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
                        mock?.disable()
                        prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
                        saveServiceStatus("GPS/radio disagreement: location output paused while trust is re-evaluated")
                        DiagnosticLogStore.log(
                            this@LocationMonitorService, "FUSION",
                            "Radio disagreement contributed to the trust decision: state=${radioDecision.state}, " +
                                "risk=${radioDecision.riskScore}; position fusion and mock output paused",
                            "WARN"
                        )
                        if (mockWasActive) {
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID,
                                notification("GNSS / radio disagreement · risk ${radioDecision.riskScore}")
                            )
                        }
                        continue
                    }
                    if (!radioFusionCurrentlyAllowed()) continue

                    val update = positionFusionEngine.update(measurement)
                    if (update.status == org.cleargps.fusion.PositionFusionUpdateStatus.REJECTED) {
                        DiagnosticLogStore.log(
                            this@LocationMonitorService, "FUSION",
                            "Radio measurement rejected: ${update.rejectionReason ?: "unknown reason"}"
                        )
                        continue
                    }

                    val physicalGpsTemplate = lastTrustedGpsFixForObd2 ?: continue
                    val publishAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val fusedEstimate = positionFusionEngine.estimate(publishAtElapsedRealtimeNanos)
                        ?.let { motionCompensatedEstimate(it, publishAtElapsedRealtimeNanos) } ?: continue
                    val fusedLocation = PositionFusionLocationAdapter.toLocation(
                        estimate = fusedEstimate,
                        template = physicalGpsTemplate,
                        wallTimeMillis = System.currentTimeMillis(),
                        nowElapsedRealtimeNanos = publishAtElapsedRealtimeNanos
                    ) ?: continue

                    // A radio result is never allowed to resurrect mock output after trust has
                    // changed. This check is kept immediately before publication.
                    if (!radioFusionCurrentlyAllowed()) continue
                    val mockRequested = prefs.getBoolean(KEY_MOCK_ENABLED, false)
                    // Persist the current valid estimate even if the optional system mock-provider
                    // publication fails; otherwise the map would keep exposing an older coordinate.
                    prefs.edit()
                        .putBoolean(KEY_HAS_OUTPUT, true)
                        .putString(KEY_LAT, fusedLocation.latitude.toString())
                        .putString(KEY_LON, fusedLocation.longitude.toString())
                        .putFloat(KEY_ACCURACY, fusedLocation.accuracy)
                        .apply()
                    if (mockRequested && mock?.publish(fusedLocation, publishGpsProvider = false) != true) {
                        prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false).apply()
                        val reason = mock?.lastError ?: "Mock-location output unavailable"
                        saveServiceStatus("Fused GPS + local radio estimate available; mock output unavailable")
                        DiagnosticLogStore.log(this@LocationMonitorService, "FUSION",
                            "Fused radio update was not published: $reason", "WARN")
                        continue
                    }
                    prefs.edit().putBoolean(KEY_MOCK_OUTPUT_ACTIVE, mockRequested).apply()
                    saveServiceStatus(
                        "Fused GPS + local radio · ±${fusedLocation.accuracy.toInt()} m"
                    )
                    DiagnosticLogStore.log(
                        this@LocationMonitorService, "FUSION",
                        "Accepted timestamped RADIO measurement into GNSS timeline: status=${update.status}, " +
                            "sources=${fusedEstimate.sourcesUsed}, accuracy≈${fusedEstimate.horizontalAccuracyMeters.toInt()} m"
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DiagnosticLogStore.log(
                        this@LocationMonitorService, "FUSION",
                        "Radio fusion scan failed: ${error.javaClass.simpleName}", "WARN"
                    )
                }
            }
        }
    }

    /**
     * Corrects only the already time-bounded constant-velocity prediction with fresh world-frame
     * IMU samples. Missing, stale, gapped or implausible IMU data leaves the base estimate intact.
     * IMU never becomes an absolute measurement and never changes TrustEngine state.
     */
    private fun motionCompensatedEstimate(
        estimate: org.cleargps.fusion.FusedPosition,
        nowElapsedRealtimeNanos: Long
    ): org.cleargps.fusion.FusedPosition {
        val samples = ImuMotionLiveStore.samplesSince(
            afterElapsedRealtimeNanos = estimate.lastMeasurementElapsedRealtimeNanos,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            maximumAgeNanos = PositionFusionMotionModel.MAXIMUM_PREDICTION_AGE_NANOS +
                PositionFusionMotionModel.MAXIMUM_SAMPLE_GAP_NANOS
        ).map {
            HorizontalAccelerationSample(
                eastMetersPerSecondSquared = it.eastMetersPerSecondSquared,
                northMetersPerSecondSquared = it.northMetersPerSecondSquared,
                elapsedRealtimeNanos = it.elapsedRealtimeNanos
            )
        }
        return PositionFusionMotionModel.compensate(
            estimate = estimate,
            samples = samples,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        ) ?: estimate
    }

    /**
     * Radio scans may still supply independent diagnostics while GPS trust is being re-evaluated,
     * but only when Auto mode and a recent physical GPS anchor are present.
     */
    private fun radioScanEvidenceCurrentlyAllowed(): Boolean {
        if (!prefs.getBoolean(KEY_MONITORING, false) ||
            prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") != "auto"
        ) return false
        val current = engine.currentDecision()
        if (current.inRiskZone || prefs.getBoolean(KEY_IN_ZONE, false)) return false
        // Use the latest physical receiver callback, not only the last trusted fix: the latter
        // stops refreshing precisely when GNSS becomes suspicious, but fresh evidence is still
        // needed to compare successive radio scans during re-evaluation.
        return engine.hasRecentPhysicalGpsReference(SystemClock.elapsedRealtimeNanos())
    }

    private fun persistRadioEvidenceDecision(result: FilterDecision) {
        if (result.state != FilterState.NORMAL || result.source == "GNSS_SIGNAL_ONLY") {
            persistDecision(result)
            return
        }
        // Refresh trust metadata without replacing a newer fused map coordinate with the raw GPS fix.
        prefs.edit()
            .putString(KEY_STATE, result.state.name)
            .putInt(KEY_SCORE, result.riskScore)
            .putBoolean(KEY_IN_ZONE, result.inRiskZone)
            .putString(KEY_SOURCE, result.source)
            .putString(KEY_REASONS, result.reasons.joinToString("\n"))
            .apply()
        RiskBreakdownStore.write(prefs, result.riskBreakdown, SystemClock.elapsedRealtimeNanos())
    }

    private fun radioFusionCurrentlyAllowed(): Boolean {
        if (!prefs.getBoolean(KEY_MONITORING, false) ||
            prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") != "auto"
        ) return false

        val decision = engine.currentDecision()
        if (decision.state != FilterState.NORMAL || decision.source != "GPS" ||
            decision.inRiskZone || prefs.getBoolean(KEY_IN_ZONE, false)
        ) return false

        // Preferences can retain the last NORMAL decision after callbacks stop. Require a fresh
        // physical fix within the fusion publisher's short prediction horizon before admitting new
        // radio data; otherwise the update could mutate fusion state but be too old to publish.
        val lastGps = lastTrustedGpsFixForObd2 ?: return false
        val gpsAgeNanos = SystemClock.elapsedRealtimeNanos() - lastGps.elapsedRealtimeNanos
        return gpsAgeNanos in 0L..PositionFusionLocationAdapter.MAX_OUTPUT_AGE_NANOS
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
        demSampleGeneration += 1L
        demSampleJob?.cancel()
        demSampleJob = null
        lastDemSampleRequestElapsedRealtimeNanos = 0L
        SrtmElevationDiagnosticsLiveStore.clear()
        AltitudeDiagnosticsLiveStore.clear()
        lastAltitudeDiagnosticKey = null
        barometerReader?.stop()
        imuMotionReader?.stop()
        positionFusionEngine.reset()
        GnssReplayRecorder.stop(this, "SERVICE_STOP")
        stopReplayRadioCapture()
        cancelBeaconDbMockOutput()
        radioOnlyJob?.cancel()
        radioOnlyJob = null
        radioFusionJob?.cancel()
        radioFusionJob = null
        obd2FallbackJob?.cancel()
        obd2FallbackJob = null
        mockFreshnessJob?.cancel()
        mockFreshnessJob = null
        if (obd2FallbackPublished) clearObd2FallbackOutput("OBD-II fallback stopped with location monitoring")
        lastTrustedGpsFixForObd2 = null
        try { manager.removeUpdates(this) } catch (_: SecurityException) {}
        unregisterGnssStatus()
        clearGnssReceptionDiagnostics()
        GnssDiagnosticsLiveStore.clear()
        RiskBreakdownStore.clear(prefs)
        riskZoneMembership.reset()
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


    /**
     * Never feed mock coordinates into the GNSS filter. If Clear GPS's own mock output is off,
     * a mock GPS/network callback is instead recorded as a strong external-mock warning.
     */
    private fun handleExternalMockLocation(location: Location) {
        val provider = location.provider?.takeIf { it.isNotBlank() } ?: "unknown"
        val reason = "Обнаружена фиктивная геопозиция стороннего приложения через провайдер $provider; координата не использована."
        val previous = engine.currentDecision()
        val result = FilterDecision(
            state = FilterState.SUSPECT,
            riskScore = 90,
            inRiskZone = previous.inRiskZone,
            source = "EXTERNAL_MOCK",
            outputLocation = null,
            reasons = listOf(reason)
        )

        // The setting is off in this path. Tear down our own provider only if preferences
        // still report an active output; repeated external mock callbacks must not repeatedly
        // unregister providers or flood diagnostic logs.
        val ownOutputActive = prefs.getBoolean(KEY_MOCK_OUTPUT_ACTIVE, false) ||
            prefs.getBoolean(KEY_FUSED_MOCK_ACTIVE, false)
        if (ownOutputActive) mock?.disable()
        persistDecision(result)
        prefs.edit()
            .putBoolean(KEY_MOCK_OUTPUT_ACTIVE, false)
            .putBoolean(KEY_HAS_OUTPUT, false)
            .apply()
        saveServiceStatus(reason)
        DiagnosticLogStore.log(this, "GNSS_EXTERNAL_MOCK", reason, "WARN")
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("Внешняя mock-геопозиция обнаружена; координата отклонена")
        )
    }

    override fun onLocationChanged(location: Location) {
        // Never feed any mock callback into the physical GNSS filter. Depending on our own
        // mock setting, classify it as expected output or a possible external mock source.
        if (LocationFilterEngine.isMockLocation(location)) {
            val ownMockEnabled = prefs.getBoolean(KEY_MOCK_ENABLED, false)
            if (shouldFlagExternalMockLocation(location.provider, true, ownMockEnabled)) {
                handleExternalMockLocation(location)
            } else {
                val message = if (ownMockEnabled) {
                    "Mock-location callback ignored while Clear GPS mock output is enabled"
                } else {
                    "Mock-location callback from an unmonitored provider ignored"
                }
                DiagnosticLogStore.log(this, "GNSS", message)
            }
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
        val accuracyMeters = if (location.hasAccuracy() && location.accuracy.isFinite() &&
            location.accuracy >= 0f) location.accuracy.toDouble() else null
        val inZone = if (gpsFix) {
            riskZoneMembership.update(
                point = point,
                enabledZones = ZoneStore.loadAll(this).filter { it.enabled }.map { it.points },
                accuracyMeters = accuracyMeters
            )
        } else engine.currentDecision().inRiskZone

        val observedDecision = engine.observe(location, inZone)
        if (gpsFix) {
            GnssDeviceBaselineStore.saveIfChanged(this, engine.deviceBaselineSnapshot())
        }
        val fallback = createLastResortFallback(observedDecision)
        var result = if (fallback != null) {
            observedDecision.copy(state = FilterState.CORRECTING, source = fallback.source, outputLocation = fallback.location)
        } else observedDecision

        var fusionAcceptedCurrentFix = false
        // The Kalman core is an output smoother, not a trust detector. Feed it only a physical,
        // in-zone-free GPS fix that the existing trust engine has explicitly classified NORMAL.
        val trustedGpsDecision = PositionFusionLocationAdapter.isTrustedGpsDecision(
            trustState = result.state,
            decisionSource = result.source,
            inRiskZone = result.inRiskZone
        )
        if (gpsFix) {
            if (!trustedGpsDecision) {
                positionFusionEngine.reset()
                if (PositionFusionLocationAdapter.shouldSuppressGpsOutput(
                        result.state, result.source, result.inRiskZone
                    )
                ) {
                    result = result.copy(
                        outputLocation = null,
                        reasons = (result.reasons + "GPS-кандидат не опубликован: состояние доверия или зона не допускают прямую публикацию.").distinct()
                    )
                }
            } else {
                val fusionInput = PositionFusionLocationAdapter.trustedGnssMeasurement(
                    location = location,
                    trustState = result.state,
                    decisionSource = result.source,
                    inRiskZone = result.inRiskZone
                )
                if (fusionInput == null) {
                    // A fix that cannot be normalized conservatively is not allowed to bypass fusion.
                    positionFusionEngine.reset()
                    result = result.copy(
                        outputLocation = null,
                        reasons = (result.reasons + "Точность/время GPS не подходят для позиционного фильтра; публикация приостановлена.").distinct()
                    )
                } else {
                    val fusionUpdate = positionFusionEngine.update(fusionInput)
                    val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val fusedEstimate = positionFusionEngine.estimate(nowElapsedRealtimeNanos)
                ?.let { motionCompensatedEstimate(it, nowElapsedRealtimeNanos) }
                    val fusedLocation = fusedEstimate?.let {
                        PositionFusionLocationAdapter.toLocation(
                            estimate = it,
                            template = location,
                            wallTimeMillis = System.currentTimeMillis(),
                            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
                        )
                    }
                    if (fusedLocation != null) {
                        result = result.copy(outputLocation = fusedLocation)
                        fusionAcceptedCurrentFix = fusionUpdate.status !=
                            org.cleargps.fusion.PositionFusionUpdateStatus.REJECTED
                    } else {
                        // Do not fall back to the same raw candidate rejected or aged out by fusion.
                        positionFusionEngine.reset()
                        result = result.copy(
                            outputLocation = null,
                            reasons = (result.reasons + "Нет свежей оценки позиционного фильтра; публикация координаты приостановлена.").distinct()
                        )
                        DiagnosticLogStore.log(
                            this, "FUSION",
                            "Оценка физического GPS не прошла временные/статистические ограничения; кандидат не опубликован. " +
                                "update=${fusionUpdate.status}, reason=${fusionUpdate.rejectionReason ?: "none"}",
                            "WARN"
                        )
                    }
                }
            }
        } else if (PositionFusionLocationAdapter.shouldSuppressGpsOutput(
                result.state, result.source, result.inRiskZone
            )
        ) {
            // A NETWORK callback can cause the trust engine to return its cached GPS candidate.
            // Do not let that raw cached position bypass the current trust decision.
            result = result.copy(
                outputLocation = null,
                reasons = (result.reasons + "GPS-кандидат не опубликован: состояние доверия или зона не допускают прямую публикацию.").distinct()
            )
        } else if (trustedGpsDecision) {
            // NETWORK callbacks may refresh the service decision, but must reuse a fresh fused
            // estimate rather than republishing the cached raw GPS Location.
            val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val fusedEstimate = positionFusionEngine.estimate(nowElapsedRealtimeNanos)
                ?.let { motionCompensatedEstimate(it, nowElapsedRealtimeNanos) }
            val template = result.outputLocation ?: location
            val fusedLocation = fusedEstimate?.let {
                PositionFusionLocationAdapter.toLocation(
                    estimate = it,
                    template = template,
                    wallTimeMillis = System.currentTimeMillis(),
                    nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
                )
            }
            if (fusedLocation != null) {
                result = result.copy(outputLocation = fusedLocation)
            } else {
                result = result.copy(
                    outputLocation = null,
                    reasons = (result.reasons + "Нет свежей оценки позиционного фильтра; кэшированный GPS-кандидат не опубликован.").distinct()
                )
            }
        }
        try { GnssReplayRecorder.recordPosition(this, location, inZone, result) }
        catch (_: RuntimeException) { /* Replay capture never changes live filter behavior. */ }
        persistDecision(result)
        DiagnosticLogStore.log(
            this, "GNSS",
            "Позиция от Android: provider=${location.provider ?: "unknown"}, filter=${result.state}, source=${result.source}, " +
                "risk=${result.riskScore}, hasOutput=${result.outputLocation != null}"
        )
        if (gpsFix && result.state == FilterState.NORMAL && result.source == "GPS" && !inZone && fusionAcceptedCurrentFix) {
            lastTrustedGpsFixForObd2 = Location(location)
            scheduleSrtmDiagnosticSample(location)
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
            if (mock?.publish(
                    result.outputLocation, publishGpsProvider = false
                ) == true) {
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Let a still-running foreground service refresh its heartbeat before recovery.
        ServiceRecoveryWatchdog.enqueueRecoveryCheck(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized && prefs.getBoolean(KEY_MONITORING, false)) {
            ServiceRecoveryWatchdog.enqueueRecoveryCheck(this)
        }
        stopTracking()
        mock?.close()
        serviceScope.cancel()
        super.onDestroy()
    }


    /**
     * Diagnostics-only DEM lookup. It accepts only the already-trusted physical GPS path above,
     * runs raster I/O off the main thread, and keeps elevation/time only in process memory.
     * It deliberately does not compare vertical datums or contribute to the live risk score.
     */
    private fun scheduleSrtmDiagnosticSample(location: Location) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (demSampleJob?.isActive == true) return
        if (!SrtmElevationDiagnosticSamplingPolicy.shouldSample(
                lastAttemptElapsedRealtimeNanos = lastDemSampleRequestElapsedRealtimeNanos,
                nowElapsedRealtimeNanos = nowNanos
            )
        ) return

        lastDemSampleRequestElapsedRealtimeNanos = nowNanos
        val latitude = location.latitude
        val longitude = location.longitude
        val generation = ++demSampleGeneration
        demSampleJob = serviceScope.launch {
            try {
                val sample = withContext(Dispatchers.IO) {
                    runCatching {
                        SrtmHgtElevationStore(File(filesDir, "dem/srtm"))
                            .elevationAt(latitude, longitude)
                    }.getOrNull()
                }
                if (isActive &&
                    generation == demSampleGeneration &&
                    prefs.getBoolean(KEY_MONITORING, false)
                ) {
                    val sampledAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    SrtmElevationDiagnosticsLiveStore.update(
                        sample = sample,
                        sampledAtElapsedRealtimeNanos = sampledAtElapsedRealtimeNanos
                    )
                    val altitudeDiagnostics = AltitudeDiagnosticsReadinessPolicy.assess(
                        gnssObservation = AltitudeDiagnosticsReadinessPolicy.gnssObservation(location),
                        demObservation = AltitudeDiagnosticsReadinessPolicy.demObservation(
                            sample = sample,
                            fixElapsedRealtimeNanos = location.elapsedRealtimeNanos
                        ),
                        barometerPressureSample = BarometerPressureLiveStore.snapshot(
                            sampledAtElapsedRealtimeNanos
                        ),
                        nowElapsedRealtimeNanos = sampledAtElapsedRealtimeNanos
                    )
                    AltitudeDiagnosticsLiveStore.update(altitudeDiagnostics)
                    val diagnosticKey = listOf(
                        altitudeDiagnostics.comparisonReadiness.name,
                        altitudeDiagnostics.gnssDatum?.name ?: "none",
                        altitudeDiagnostics.demDatum?.name ?: "none",
                        altitudeDiagnostics.barometerReadiness.name
                    ).joinToString(":")
                    if (diagnosticKey != lastAltitudeDiagnosticKey) {
                        lastAltitudeDiagnosticKey = diagnosticKey
                        DiagnosticLogStore.log(
                            this@LocationMonitorService,
                            "ALTITUDE",
                            AltitudeDiagnosticsReadinessPolicy.description(altitudeDiagnostics),
                            "INFO"
                        )
                    }
                }
            } finally {
                if (generation == demSampleGeneration) demSampleJob = null
            }
        }
    }

    private fun persistGnssReceptionDiagnostics() {
        if (!::prefs.isInitialized) return
        try {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val diagnostics = engine.receptionDiagnostics(nowNanos)
            prefs.edit()
                .putString(KEY_GNSS_ENVIRONMENT, diagnostics.environment.context.name)
                .putString(KEY_GNSS_ENVIRONMENT_REASONS, diagnostics.environment.reasons.joinToString("\n"))
                .putBoolean(KEY_GNSS_BASELINE_READY, diagnostics.baselineDeviation.ready)
                .putString(
                    KEY_GNSS_BASELINE_DEVIATIONS,
                    diagnostics.baselineDeviation.deviations.joinToString("\n") { it.reason }
                )
                .putLong(KEY_GNSS_DIAGNOSTICS_AT_NANOS, nowNanos)
                .apply()
        } catch (_: RuntimeException) {
            // Diagnostics are observational and must never interrupt GNSS callbacks.
        }
    }

    private fun clearGnssReceptionDiagnostics() {
        if (!::prefs.isInitialized) return
        try {
            prefs.edit()
                .putString(KEY_GNSS_ENVIRONMENT, "UNKNOWN")
                .putString(KEY_GNSS_ENVIRONMENT_REASONS, "")
                .putBoolean(KEY_GNSS_BASELINE_READY, false)
                .putString(KEY_GNSS_BASELINE_DEVIATIONS, "")
                .putLong(KEY_GNSS_DIAGNOSTICS_AT_NANOS, 0L)
                .apply()
        } catch (_: RuntimeException) {
            // Clearing display-only state must not interrupt service shutdown.
        }
    }

    private fun publishSignalDecision(result: FilterDecision) {
        if (result.state != FilterState.NORMAL || result.source == "GNSS_SIGNAL_ONLY") {
            positionFusionEngine.reset()
        }
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
            RiskBreakdownStore.write(prefs, result.riskBreakdown, SystemClock.elapsedRealtimeNanos())
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
        RiskBreakdownStore.write(prefs, result.riskBreakdown, SystemClock.elapsedRealtimeNanos())
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
        const val ACTION_RECOVER = "org.cleargps.RECOVER"
        const val ACTION_STOP = "org.cleargps.STOP"
        const val ACTION_DISABLE_MOCK = "org.cleargps.DISABLE_MOCK"
        const val ACTION_REPLAY_CAPTURE_CHANGED = "org.cleargps.REPLAY_CAPTURE_CHANGED"
        const val ACTION_PUBLISH_BEACONDB_FIX = "org.cleargps.PUBLISH_BEACONDB_FIX"
        const val EXTRA_BEACONDB_LATITUDE = "beacondb_latitude"
        const val EXTRA_BEACONDB_LONGITUDE = "beacondb_longitude"
        const val EXTRA_BEACONDB_ACCURACY = "beacondb_accuracy"
        const val PREFS = "clear_gps"
        const val KEY_MONITORING = "monitoring"
        const val KEY_MOCK_ENABLED = "mock_enabled"
        const val KEY_MOCK_OUTPUT_ACTIVE = "mock_output_active"
        const val KEY_MOCK_LAST_PUBLISHED_ELAPSED_MS = "mock_last_published_elapsed_ms"
        const val KEY_FUSED_MOCK_ACTIVE = "fused_mock_active"
        const val KEY_FUSED_MOCK_STATUS = "fused_mock_status"
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
        const val KEY_GNSS_ENVIRONMENT = "gnss_environment"
        const val KEY_GNSS_ENVIRONMENT_REASONS = "gnss_environment_reasons"
        const val KEY_GNSS_BASELINE_READY = "gnss_baseline_ready"
        const val KEY_GNSS_BASELINE_DEVIATIONS = "gnss_baseline_deviations"
        const val KEY_GNSS_DIAGNOSTICS_AT_NANOS = "gnss_diagnostics_at_nanos"

        /**
         * Notify the existing foreground monitor when the user starts/stops an opt-in replay.
         * Does not restart location registration or alter the live positioning source.
         */
        fun notifyReplayRecordingChanged(context: Context) {
            val currentPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!currentPrefs.getBoolean(KEY_MONITORING, false)) return
            val intent = Intent(context, LocationMonitorService::class.java)
                .setAction(ACTION_REPLAY_CAPTURE_CHANGED)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                DiagnosticLogStore.log(
                    context, "REPLAY_RADIO",
                    "Could not update replay radio capture: ${error.javaClass.simpleName}", "WARN"
                )
            }
        }

        private const val CHANNEL = "clear_gps_monitor"
        private const val NOTIFICATION_ID = 4101
        private const val MOCK_WATCHDOG_CHECK_INTERVAL_MS = 5_000L
        private const val SERVICE_HEARTBEAT_INTERVAL_MS = 30_000L
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
