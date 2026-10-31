package org.cleargps

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private data class CellObservation(
    val type: String, val mcc: Int, val mnc: Int, val area: Int, val cellId: Long, val rssi: Int
) {
    fun toSignal() = RadioSignal("cell", "$type:$mcc:$mnc:$area:$cellId", rssi)
}

private data class TimedRadioSignals(
    val signals: List<RadioSignal>,
    val ageMillis: Long?,
    val bleProtocols: Set<String> = emptySet()
)

private data class BleScanPayload(
    val signals: List<RadioSignal>,
    val protocols: Set<String>
)

private data class ScanSnapshot(
    val signals: List<RadioSignal>,
    val wifiCount: Int,
    val cellCount: Int,
    val bleCount: Int,
    val wifiAgeMillis: Long?,
    val cellAgeMillis: Long?,
    val bleAgeMillis: Long?,
    val bleProtocols: Set<String>,
    val collectedAtElapsedRealtimeNanos: Long
)

class RadioGeolocationRepository(private val context: Context) {
    private val database = LocalRadioDatabase(context.applicationContext)
    private val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)

    suspend fun scan(
        record: Boolean,
        reference: RadioReference?,
        mode: String,
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean
    ): RadioLookupResult = scanMutex.withLock {
        DiagnosticLogStore.log(context, "RADIO", "Сканирование начато; mode=$mode, Wi-Fi=$useWifi, соты=$useCells, BLE=$useBle, запись=$record")
        // The UI, recording loop and foreground service can all request a scan. Serialize
        // them process-wide so concurrent Wi-Fi/BLE scans do not cancel or throttle each other.
        val fineLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val precisePermissionHint = if (RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(
                fineLocationGranted = fineLocationGranted,
                useWifi = useWifi,
                useCells = useCells,
                useBle = useBle
            )) {
            " Precise location permission is required for this app to use nearby Wi‑Fi, cellular and Bluetooth identifiers for positioning. Grant Precise location in app permissions. Radio-only mode still does not request GPS fixes."
        } else ""
        val snapshot = withContext(Dispatchers.Main.immediate) {
            collectSnapshot(useWifi = useWifi, useCells = useCells, useBle = useBle)
        }
        // Independent opt-in: no-op unless a local replay session is active.
        try {
            GnssReplayRecorder.recordRadioSnapshot(context, SystemClock.elapsedRealtimeNanos(), snapshot.signals)
        } catch (_: RuntimeException) {
            // Recording must not interrupt live radio positioning.
        }
        val localSourceEnabled = prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_LOCAL_ENABLED, true)
        val beaconSourceEnabled = prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_BEACONDB_ENABLED, true)
        val openCellIdSourceEnabled = prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true)
        val safeMode = CrashLoopGuard.isSafeModeActive(context)

        val localEstimate = if (localSourceEnabled) {
            withContext(Dispatchers.IO) {
                database.estimatePosition(snapshot.signals, useWifi, useCells, useBle)
            }
        } else null
        val localEstimateWithFreshness = localEstimate?.let { candidate ->
            val staleWifiPenalty = RadioFixReliability.wifiAgePenaltyMeters(
                ageMillis = snapshot.wifiAgeMillis,
                wifiSignalCount = snapshot.wifiCount,
                totalSignalCount = snapshot.signals.size
            )
            val staleCellPenalty = RadioFixReliability.cellAgePenaltyMeters(
                ageMillis = snapshot.cellAgeMillis,
                cellSignalCount = snapshot.cellCount,
                totalSignalCount = snapshot.signals.size
            )
            val staleBlePenalty = RadioFixReliability.bleAgePenaltyMeters(
                ageMillis = snapshot.bleAgeMillis,
                bleSignalCount = snapshot.bleCount,
                totalSignalCount = snapshot.signals.size
            )
            val oldestEvidenceAgeMillis = listOfNotNull(
                snapshot.wifiAgeMillis.takeIf { snapshot.wifiCount > 0 },
                snapshot.cellAgeMillis.takeIf { snapshot.cellCount > 0 },
                snapshot.bleAgeMillis.takeIf { snapshot.bleCount > 0 }
            ).maxOrNull()?.coerceAtLeast(0L) ?: 0L
            val evidenceTimestamp = (System.currentTimeMillis() - oldestEvidenceAgeMillis).coerceAtLeast(0L)
            candidate.copy(
                updatedAt = evidenceTimestamp,
                accuracyMeters = (candidate.accuracyMeters + staleWifiPenalty + staleCellPenalty + staleBlePenalty)
                    .coerceAtMost(50_000.0),
                measurementElapsedRealtimeNanos =
                    RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
                        signals = snapshot.signals,
                        matchedRadioIdentifiers = candidate.matchedRadioIdentifiers,
                        collectedAtElapsedRealtimeNanos = snapshot.collectedAtElapsedRealtimeNanos
                    )
            )
        }

        // BeaconDB is queried only by the explicit button. Normal radio scans can consume its
        // short-lived cache when the user independently enabled this source for position fusion.
        val beaconCachedResult = if (beaconSourceEnabled && !safeMode) {
            val cellSignals = snapshot.signals.filter { it.type == "cell" }
            val request = BeaconDbRequest(
                cells = cellSignals.mapNotNull {
                    BeaconDbProtocol.cellFromSignal(it, snapshot.collectedAtElapsedRealtimeNanos)
                },
                wifiAccessPoints = snapshot.signals.mapNotNull {
                    BeaconDbProtocol.wifiFromSignal(it, snapshot.collectedAtElapsedRealtimeNanos)
                },
                bluetoothBeacons = snapshot.signals.mapNotNull {
                    BeaconDbProtocol.bluetoothFromSignal(it, snapshot.collectedAtElapsedRealtimeNanos)
                }
            ).normalized()
            val unsupportedCount = (cellSignals.size - request.cells.size).coerceAtLeast(0)
            BeaconDbClient.lookupCached(request, unsupportedCount)
        } else null

        // OpenCellID tower coordinates are read from the local catalogue only. Periodic radio
        // scans never call /cell/get; refreshing that catalogue remains an explicit, quota-guarded action.
        val openCellIdEstimate = if (openCellIdSourceEnabled && useCells && !safeMode) {
            withContext(Dispatchers.IO) {
                val evidence = snapshot.signals.asSequence()
                    .filter { it.type == "cell" }
                    .mapNotNull { signal ->
                        val key = OpenCellIdCellKey.fromRadioSignal(signal) ?: return@mapNotNull null
                        val record = database.getOpenCellIdCell(key) ?: return@mapNotNull null
                        signal to record
                    }.toList()
                OpenCellIdPositionEstimator.estimate(evidence)
            }
        } else null

        val sourceEstimates = buildList {
            localEstimateWithFreshness?.let {
                add(RadioPositionFusion.SourceEstimate("local", it, trustWeight = 1.0))
            }
            beaconCachedResult?.let { cachedResult ->
                cachedResult.coordinate?.let { coordinate ->
                    val cacheAgePenalty = (cachedResult.cacheAgeMillis / 60_000.0) * 10.0
                    val beaconEstimate = RadioEstimate(
                        latitude = coordinate.latitude,
                        longitude = coordinate.longitude,
                        accuracyMeters = (coordinate.accuracyMeters + cacheAgePenalty).coerceAtMost(100_000.0),
                        updatedAt = cachedResult.queriedAtMillis,
                        fingerprint = "beacondb-cache",
                        fallbackUsed = true,
                        matchedCount = (
                            cachedResult.cells.size +
                                cachedResult.wifiAccessPoints.size +
                                cachedResult.bluetoothBeacons.size
                        ).coerceAtLeast(1),
                        matchedRadioTypes = buildSet {
                            if (cachedResult.cells.isNotEmpty()) add("cell")
                            if (cachedResult.wifiAccessPoints.isNotEmpty()) add("wifi")
                            if (cachedResult.bluetoothBeacons.isNotEmpty()) add("ble")
                        },
                        contributingSources = setOf("beacondb")
                    )
                    add(RadioPositionFusion.SourceEstimate("beacondb", beaconEstimate, trustWeight = 0.85))
                }
            }
            openCellIdEstimate?.let {
                add(RadioPositionFusion.SourceEstimate("opencellid", it, trustWeight = 0.30))
            }
        }
        val estimate = RadioPositionFusion.combine(sourceEstimates)
        val matchCounts = if (localSourceEnabled) {
            withContext(Dispatchers.IO) {
                database.countMatchingPoints(snapshot.signals, useWifi, useCells, useBle)
            }
        } else RadioMatchCounts(0, 0)
        // Radio-only coordinates are estimates from this very database, not independent
        // reference fixes. Never persist them as training coordinates, even if a caller passes one.
        val safeReference = if (mode == "radio" || mode == "radio_obd2") null else reference
        val recorded = withContext(Dispatchers.IO) {
            database.recordScan(snapshot.signals, safeReference, estimate, mode,
                snapshot.wifiCount, snapshot.cellCount, snapshot.bleCount, record)
        }
        prefs.edit()
            .putInt(ClearGpsPreferences.KEY_LAST_WIFI_COUNT, snapshot.wifiCount)
            .putInt(ClearGpsPreferences.KEY_LAST_CELL_COUNT, snapshot.cellCount)
            .putInt(ClearGpsPreferences.KEY_LAST_BLE_COUNT, snapshot.bleCount)
            .putLong(ClearGpsPreferences.KEY_LAST_SCAN_AT, System.currentTimeMillis())
            .putInt(ClearGpsPreferences.KEY_LAST_RECORDED_COUNT, recorded)
            .apply()
        if (estimate != null) saveEstimate(context, estimate)

        val protocolSummary = snapshot.bleProtocols.sorted().joinToString(", ")
        val summary = buildList {
            if (snapshot.wifiCount > 0) add("${snapshot.wifiCount} Wi‑Fi")
            if (snapshot.cellCount > 0) add("${snapshot.cellCount} сот")
            if (snapshot.bleCount > 0) add("${snapshot.bleCount} BLE-идентификаторов")
            if (snapshot.bleProtocols.isNotEmpty()) add("форматы BLE: $protocolSummary")
        }.joinToString(", ").ifBlank { "радиосигналы не обнаружены" }
        val message = when {
            snapshot.signals.isEmpty() && snapshot.bleProtocols.isNotEmpty() ->
                "Обнаружены BLE-кадры ($protocolSummary), но среди них нет постоянного идентификатора, пригодного для радиопозиционирования."
            snapshot.signals.isEmpty() ->
                "Сканирование завершено, но Android не вернул доступных радиосигналов. Проверь разрешения, включённые радио, системную геолокацию и ограничения Android."
            estimate != null -> {
                val sourceTitles = estimate.contributingSources.map { source ->
                    when (source) {
                        "local" -> "локальная БД"
                        "beacondb" -> "BeaconDB (кэш)"
                        "opencellid" -> "OpenCellID (кэш вышек)"
                        else -> source
                    }
                }
                val sourcesText = sourceTitles.joinToString(" + ").ifBlank { "доступные радиоданные" }
                val conflictHint = if (estimate.sourceConflictDetected) {
                    " Источники не согласовались; выбран наиболее надёжный вариант, погрешность увеличена."
                } else ""
                "Сканирование завершено: $summary. Комбинированная оценка: $sourcesText. " +
                    "Совпадений: ${estimate.matchedCount}; погрешность ±${estimate.accuracyMeters.toInt()} м.$conflictHint"
            }
            else ->
                "Сканирование завершено: $summary. Совпадений идентификаторов в локальной базе: ${matchCounts.knownRadios}; пригодных координатных записей: ${matchCounts.usableCoordinateRadios}. " +
                    "Координата не построена: может не хватать согласованных координатных точек или совпадения неоднозначны."
        }
        val cacheAgeHints = buildList {
            snapshot.wifiAgeMillis
                ?.takeIf { it > RadioFixReliability.WIFI_FRESH_AGE_MS }
                ?.let { add("Wi‑Fi: кэш ${it / 1_000} с") }
            snapshot.cellAgeMillis
                ?.takeIf { it > RadioFixReliability.CELL_INFO_FRESH_AGE_MS }
                ?.let { add("соты: кэш ${it / 1_000} с") }
            snapshot.bleAgeMillis
                ?.takeIf { it > RadioFixReliability.BLE_FRESH_AGE_MS }
                ?.let { add("Bluetooth: кэш ${it / 1_000} с") }
        }.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " Используется кэш радиосигналов (", postfix = "); погрешность увеличена.")
            .orEmpty()
        val fullMessage = message + cacheAgeHints + precisePermissionHint
        DiagnosticLogStore.log(
            context, "RADIO",
            "Сканирование завершено: Wi-Fi=${snapshot.wifiCount}, соты=${snapshot.cellCount}, BLE=${snapshot.bleCount}, " +
                "Wi-Fi age=${snapshot.wifiAgeMillis?.div(1_000) ?: "unknown"}s, cell age=${snapshot.cellAgeMillis?.div(1_000) ?: "unknown"}s, " +
                "BLE age=${snapshot.bleAgeMillis?.div(1_000) ?: "unknown"}s, " +
                (estimate?.let { "оценка есть, источники=${it.contributingSources.joinToString("+")}, совпадений=${it.matchedCount}, точность≈${it.accuracyMeters.toInt()} м, конфликт=${it.sourceConflictDetected}" }
                    ?: "координата не рассчитана")
        )
        prefs.edit()
            .putString(ClearGpsPreferences.KEY_LAST_SCAN_MESSAGE, fullMessage)
            .putBoolean(ClearGpsPreferences.KEY_LAST_SCAN_HAS_ESTIMATE, estimate != null)
            .apply()
        RadioLookupResult(
            estimate, snapshot.wifiCount, snapshot.cellCount, snapshot.bleCount,
            RadioFixReliability.isAllRadioDataCached(
                wifiCount = snapshot.wifiCount,
                wifiAgeMillis = snapshot.wifiAgeMillis,
                cellCount = snapshot.cellCount,
                cellAgeMillis = snapshot.cellAgeMillis,
                bleCount = snapshot.bleCount,
                bleAgeMillis = snapshot.bleAgeMillis
            ),
            fullMessage, estimate?.matchedCount ?: 0, recorded,
            matchCounts.knownRadios, matchCounts.usableCoordinateRadios
        )
    }

    /**
     * Capture a radio snapshot for an explicitly enabled replay without calculating or persisting
     * a radio-derived position. The same mutex serializes this with manual/background scans.
     */
    suspend fun captureReplaySnapshot(
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean
    ): Int = scanMutex.withLock {
        if (!GnssReplayRecorder.isRecording(context)) return@withLock 0
        val snapshot = withContext(Dispatchers.Main.immediate) {
            collectSnapshot(useWifi = useWifi, useCells = useCells, useBle = useBle)
        }
        if (GnssReplayRecorder.isRecording(context) && snapshot.signals.isNotEmpty()) {
            GnssReplayRecorder.recordRadioSnapshot(
                context, SystemClock.elapsedRealtimeNanos(), snapshot.signals
            )
        }
        snapshot.signals.size
    }


    /**
     * Updates approved external radio sources while location monitoring is active.
     * BeaconDB returns a geolocation estimate (not a downloadable database) kept in its
     * bounded memory cache. OpenCellID records are persisted in the separate SQLite table.
     * Neither response is written into local training fingerprints.
     */
    suspend fun refreshExternalDatabasesAutomatically(): String? {
        if (!prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false) ||
            CrashLoopGuard.isSafeModeActive(context)
        ) return null

        val beaconEnabled =
            prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_BEACONDB_ENABLED, true) &&
                prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)
        val openCellIdEnabled =
            prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true) &&
                OpenCellIdKeyStore.isEnabled(context) &&
                OpenCellIdKeyStore.hasKey(context)
        if (!beaconEnabled && !openCellIdEnabled) return null

        val fineLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) return null

        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldCapture = synchronized(AUTO_REFRESH_LOCK) {
            if (!ExternalRadioDatabaseRefreshPolicy.shouldCaptureSnapshot(
                    nowElapsedMillis = nowElapsed,
                    lastSnapshotElapsedMillis = lastAutomaticDatabaseSnapshotElapsedMs
                )
            ) false else {
                lastAutomaticDatabaseSnapshotElapsedMs = nowElapsed
                true
            }
        }
        if (!shouldCapture) return null

        val useCells = prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true) &&
            (beaconEnabled || openCellIdEnabled)
        val useWifi = beaconEnabled && prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true)
        val useBle = beaconEnabled && prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)
        if (!useCells && !useWifi && !useBle) return null

        val snapshot = scanMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                collectSnapshot(
                    useWifi = useWifi,
                    useCells = useCells,
                    useBle = useBle,
                    refreshCellInfo = false
                )
            }
        }
        if (snapshot.signals.isEmpty()) return null

        var beaconStatus = ""
        if (beaconEnabled) {
            val request = BeaconDbRequest(
                cells = snapshot.signals.filter { it.type == "cell" }
                    .mapNotNull { BeaconDbProtocol.cellFromSignal(it, snapshot.collectedAtElapsedRealtimeNanos) },
                wifiAccessPoints = snapshot.signals.mapNotNull {
                    BeaconDbProtocol.wifiFromSignal(it, snapshot.collectedAtElapsedRealtimeNanos)
                },
                bluetoothBeacons = snapshot.signals.mapNotNull {
                    BeaconDbProtocol.bluetoothFromSignal(it, snapshot.collectedAtElapsedRealtimeNanos)
                }
            ).normalized()
            if (request.canLookup()) {
                val identity = BeaconDbProtocol.identityFingerprint(request)
                val autoPrefs = context.getSharedPreferences(AUTO_REFRESH_PREFS, Context.MODE_PRIVATE)
                val state = BeaconDbAutoRefreshState(
                    identityFingerprint = autoPrefs.getString(KEY_BEACON_IDENTITY, null),
                    lastAttemptAtMillis = autoPrefs.getLong(KEY_BEACON_ATTEMPT_AT, 0L),
                    lastSuccessAtMillis = autoPrefs.getLong(KEY_BEACON_SUCCESS_AT, 0L)
                )
                val now = System.currentTimeMillis()
                if (ExternalRadioDatabaseRefreshPolicy.shouldRequestBeaconDb(state, identity, now)) {
                    val savedAttempt = autoPrefs.edit()
                        .putString(KEY_BEACON_IDENTITY, identity)
                        .putLong(KEY_BEACON_ATTEMPT_AT, now)
                        .commit()
                    if (savedAttempt &&
                        prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false) &&
                        prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_BEACONDB_ENABLED, true) &&
                        prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) &&
                        !CrashLoopGuard.isSafeModeActive(context)
                    ) {
                        val result = BeaconDbClient.lookup(
                            request,
                            (snapshot.cellCount - request.cells.size).coerceAtLeast(0)
                        )
                        if (result.success) {
                            autoPrefs.edit().putLong(KEY_BEACON_SUCCESS_AT, System.currentTimeMillis()).apply()
                            beaconStatus = "BeaconDB estimate refreshed"
                        } else {
                            beaconStatus = "BeaconDB refresh deferred: ${result.message}"
                        }
                    }
                }
            }
        }

        var openCellIdStatus = ""
        if (openCellIdEnabled &&
            prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false) &&
            prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true) &&
            OpenCellIdKeyStore.isEnabled(context)
        ) {
            val apiKey = OpenCellIdKeyStore.read(context)
            if (!apiKey.isNullOrBlank()) {
                val candidates = snapshot.signals.asSequence()
                    .filter { it.type == "cell" }
                    .mapNotNull { signal -> OpenCellIdCellKey.fromRadioSignal(signal)?.let { it to signal.rssi } }
                    .groupBy { it.first }
                    .values.mapNotNull { it.maxByOrNull { candidate -> candidate.second } }
                    .sortedByDescending { it.second }
                    .take(16)

                val staleCells = ArrayList<OpenCellIdCellKey>()
                for ((cell, _) in candidates) {
                    val cached = withContext(Dispatchers.IO) { database.getOpenCellIdCell(cell) }
                    if (OpenCellIdLookupPolicy.shouldLookup(cached, System.currentTimeMillis())) {
                        staleCells.add(cell)
                    }
                }

                if (staleCells.isNotEmpty() &&
                    prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false) &&
                    prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true) &&
                    OpenCellIdKeyStore.isEnabled(context)
                ) {
                    when (OpenCellIdUsageBudget.tryStartBatch(context)) {
                        OpenCellIdBudgetBlock.NONE -> {
                            val batch = staleCells.take(OpenCellIdLookupPolicy.MAX_LOOKUPS_PER_ACTION)
                            var queried = 0
                            var saved = 0
                            var stoppedMessage = ""
                            for (cell in batch) {
                                if (!prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false) ||
                                    !prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true) ||
                                    !OpenCellIdKeyStore.isEnabled(context)
                                ) break
                                if (!OpenCellIdUsageBudget.reserveRequest(context)) {
                                    stoppedMessage = "local daily quota reached"
                                    break
                                }
                                queried++
                                val response = OpenCellIdClient.lookup(apiKey, cell)
                                if (response.status == OpenCellIdLookupStatus.RATE_LIMITED) {
                                    OpenCellIdUsageBudget.markServerLimitReached(context)
                                    stoppedMessage = "OpenCellID server quota reached"
                                    break
                                }
                                val record = response.record
                                if (record != null) {
                                    withContext(Dispatchers.IO) { database.saveOpenCellIdCell(record) }
                                    saved++
                                } else {
                                    stoppedMessage = response.message
                                    break
                                }
                                if (queried < batch.size) delay(150L)
                            }
                            if (queried > 0) {
                                openCellIdStatus = "OpenCellID checked ${queried} new cells; ${saved} records saved" +
                                    if (stoppedMessage.isBlank()) "" else " (${stoppedMessage})"
                            }
                        }
                        OpenCellIdBudgetBlock.DAILY_BUDGET ->
                            openCellIdStatus = "OpenCellID daily app budget reached"
                        OpenCellIdBudgetBlock.SERVER_LIMIT ->
                            openCellIdStatus = "OpenCellID server quota is blocked"
                        OpenCellIdBudgetBlock.COOLDOWN ->
                            openCellIdStatus = "OpenCellID batch cooldown active"
                    }
                }
            }
        }

        return listOf(beaconStatus, openCellIdStatus)
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ")
    }

    /**
     * Requests one read-only estimate using the available Wi-Fi, BLE and supported cell signals.
     * Explicit consent is checked here and again immediately before networking.
     */
    suspend fun lookupBeaconDbAllRadios(): BeaconDbLookupResult {
        if (CrashLoopGuard.isSafeModeActive(context)) {
            return BeaconDbLookupResult(
                cells = emptyList(),
                message = "Безопасный режим после повторных сбоев: запросы BeaconDB заблокированы. Сетевого обращения не было."
            )
        }
        if (!prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)) {
            return BeaconDbLookupResult(
                cells = emptyList(),
                message = "Запросы BeaconDB выключены в настройках. Сетевого обращения не было."
            )
        }

        val fineLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) {
            return BeaconDbLookupResult(
                cells = emptyList(),
                message = "Для чтения сотовых ячеек нужно разрешение точной геолокации Android. Запрос не отправлялся."
            )
        }

        val snapshot = scanMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                collectSnapshot(
                    useWifi = true,
                    useCells = true,
                    useBle = true,
                    refreshCellInfo = true
                )
            }
        }
        val nowNanos = snapshot.collectedAtElapsedRealtimeNanos
        val cellSignals = snapshot.signals.filter { it.type == "cell" }
        val request = BeaconDbRequest(
            cells = cellSignals.mapNotNull { BeaconDbProtocol.cellFromSignal(it, nowNanos) },
            wifiAccessPoints = snapshot.signals.mapNotNull { BeaconDbProtocol.wifiFromSignal(it, nowNanos) },
            bluetoothBeacons = snapshot.signals.mapNotNull { BeaconDbProtocol.bluetoothFromSignal(it, nowNanos) }
        ).normalized()
        val unsupportedCount = (cellSignals.size - request.cells.size).coerceAtLeast(0)
        val unsupportedNrCount = cellSignals.count {
            it.identifier.substringBefore(':').equals("nr", ignoreCase = true)
        }
        val cellAvailabilityHint = buildList {
            if (cellSignals.isEmpty()) {
                add(
                    "Android не предоставил актуальных идентификаторов сотовых ячеек в этом запросе. " +
                        "Наличие мобильного интернета не гарантирует, что TelephonyManager раскроет CellInfo приложению."
                )
            }
            if (unsupportedNrCount > 0) {
                add(
                    "Android вернул $unsupportedNrCount ячеек 5G NR, но текущий контракт BeaconDB/Ichnaea " +
                        "документирует GSM/WCDMA/LTE; NR не отправлялись. Если Android также предоставил LTE-якорь, он включён в запрос."
                )
            }
        }.joinToString(separator = " ")

        // Recheck both the crash-loop guard and consent immediately before networking.
        if (CrashLoopGuard.isSafeModeActive(context)) {
            return BeaconDbLookupResult(
                cells = emptyList(),
                unsupportedCellCount = unsupportedCount,
                message = "Безопасный режим после повторных сбоев: запросы BeaconDB заблокированы. Сетевого обращения не было."
            )
        }
        if (!prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)) {
            return BeaconDbLookupResult(
                cells = emptyList(),
                unsupportedCellCount = unsupportedCount,
                message = "Запросы BeaconDB были выключены до отправки. Сетевого обращения не было."
            )
        }
        if (!request.canLookup()) {
            return BeaconDbLookupResult(
                cells = request.cells,
                unsupportedCellCount = unsupportedCount,
                wifiAccessPoints = request.wifiAccessPoints,
                bluetoothBeacons = request.bluetoothBeacons,
                message = listOf(
                    "Недостаточно поддерживаемых сигналов. BeaconDB нужна поддерживаемая сота либо минимум два допустимых Wi-Fi/BLE-идентификатора. Запрос не отправлялся.",
                    cellAvailabilityHint
                ).filter { it.isNotBlank() }.joinToString(" ")
            )
        }

        val lookup = BeaconDbClient.lookup(request, unsupportedCount)
        return if (cellAvailabilityHint.isBlank()) lookup
            else lookup.copy(message = lookup.message + " " + cellAvailabilityHint)
    }

    @Deprecated("Use lookupBeaconDbAllRadios(); all supported radio types are included.")
    suspend fun lookupBeaconDbCellsOnly(): BeaconDbLookupResult = lookupBeaconDbAllRadios()

    /**
     * Captures fresh Android CellInfo for a manual OpenCellID lookup. This path never reads
     * Wi-Fi/BLE identifiers; only normalized cellular identifiers are returned to its caller.
     */
    suspend fun currentCellsForOpenCellId(): List<Pair<OpenCellIdCellKey, Int>> {
        val signals = scanMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                collectCells(forceRefresh = true).signals
            }
        }
        return signals.asSequence()
            .mapNotNull { signal ->
                OpenCellIdCellKey.fromRadioSignal(signal)?.let { it to signal.rssi }
            }
            .groupBy { it.first }
            .values.mapNotNull { values -> values.maxByOrNull { it.second } }
            .sortedByDescending { it.second }
    }

    private suspend fun collectSnapshot(
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean,
        refreshCellInfo: Boolean = false
    ): ScanSnapshot = coroutineScope {
        // The modem query is asynchronous on Android 10+; collect it concurrently with Wi-Fi/BLE.
        val wifiTask = async {
            if (useWifi) collectWifi() else TimedRadioSignals(emptyList(), null)
        }
        val bleTask = async {
            if (useBle) collectBluetooth() else TimedRadioSignals(emptyList(), null)
        }
        val cellsTask = async {
            if (useCells) collectCells(forceRefresh = refreshCellInfo)
            else TimedRadioSignals(emptyList(), null)
        }
        val wifi = wifiTask.await()
        val ble = bleTask.await()
        val cells = cellsTask.await()
        ScanSnapshot(
            signals = wifi.signals + cells.signals + ble.signals,
            wifiCount = wifi.signals.size,
            cellCount = cells.signals.size,
            bleCount = ble.signals.size,
            wifiAgeMillis = wifi.ageMillis,
            cellAgeMillis = cells.ageMillis,
            bleAgeMillis = ble.ageMillis,
            bleProtocols = ble.bleProtocols,
            collectedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun collectWifi(): TimedRadioSignals {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!RadioPermissionPolicy.canReadWifiScan(fineGranted)) {
            DiagnosticLogStore.log(context, "RADIO_WIFI", "Wi-Fi пропущен: нет разрешения точной геолокации")
            return TimedRadioSignals(emptyList(), null)
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: run {
                DiagnosticLogStore.log(context, "RADIO_WIFI", "WifiManager недоступен", "ERROR")
                return TimedRadioSignals(emptyList(), null)
            }
        if (!manager.isWifiEnabled) {
            DiagnosticLogStore.log(context, "RADIO_WIFI", "Wi-Fi выключен системой")
            return TimedRadioSignals(emptyList(), null)
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val scanRequestedAllowed = RadioFixReliability.shouldRequestWifiScan(
            nowElapsedMs, lastWifiScanRequestAtElapsedMs
        )
        val scanStartedAtMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
        var scanRequested = false
        if (scanRequestedAllowed) {
            // Record the attempt even if Android refuses it. Otherwise a 1-second fix interval
            // would repeatedly call startScan() and rapidly hit system throttling.
            lastWifiScanRequestAtElapsedMs = nowElapsedMs
            scanRequested = try {
                manager.startScan()
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }

        if (scanRequestedAllowed) {
            DiagnosticLogStore.log(context, "RADIO_WIFI", "Запрос Wi-Fi scan: ${if (scanRequested) "принят Android" else "отклонён/ограничен Android"}")
        } else {
            DiagnosticLogStore.log(context, "RADIO_WIFI", "Новый Wi-Fi scan пропущен по cooldown; используется ограниченный системный кэш")
        }

        var rawResults = try { manager.scanResults }
        catch (_: SecurityException) { emptyList() }
        catch (_: RuntimeException) { emptyList() }

        if (scanRequested) {
            val deadline = SystemClock.elapsedRealtime() + WIFI_SCAN_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(WIFI_SCAN_POLL_MS)
                val latest = try { manager.scanResults }
                catch (_: SecurityException) { emptyList() }
                catch (_: RuntimeException) { emptyList() }
                if (latest.any { it.timestamp >= scanStartedAtMicros }) {
                    rawResults = latest
                    break
                }
                rawResults = latest
            }
        }

        val nowMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
        return try {
            val validResults = rawResults.filter { result ->
                result.timestamp > 0L && nowMicros >= result.timestamp &&
                    nowMicros - result.timestamp <= RadioFixReliability.WIFI_MAX_CACHE_AGE_MS * 1_000L &&
                    result.BSSID.isNotBlank() && result.BSSID.matches(MAC_REGEX) &&
                    result.level in -110..0
            }
            // Use the oldest contributing radio result for conservative freshness checks.
            // A single new AP must not make an otherwise stale scan look fresh.
            val ageMillis = validResults.maxOfOrNull { (nowMicros - it.timestamp) / 1_000L }
            DiagnosticLogStore.log(
                context, "RADIO_WIFI",
                "Результаты Wi-Fi: валидных BSSID=${validResults.size}, возраст самого старого сигнала=${ageMillis?.div(1_000) ?: "unknown"}s"
            )
            val signals = validResults.asSequence()
                .distinctBy { it.BSSID.lowercase(Locale.ROOT) }
                .sortedByDescending { it.level }
                .take(MAX_WIFI)
                .map {
                    val ssid = it.SSID.orEmpty().takeUnless { name ->
                        name == WifiManager.UNKNOWN_SSID || name.equals("<unknown ssid>", ignoreCase = true)
                    }.orEmpty().take(100)
                    RadioSignal(
                        "wifi", it.BSSID.lowercase(Locale.ROOT), it.level, it.frequency, ssid,
                        observedAtElapsedRealtimeNanos = it.timestamp
                            .takeIf { timestamp -> timestamp > 0L && timestamp <= Long.MAX_VALUE / 1_000L }
                            ?.times(1_000L)
                    )
                }
                .toList()
            TimedRadioSignals(signals, ageMillis)
        } catch (error: SecurityException) {
            DiagnosticLogStore.log(context, "RADIO_WIFI", "Чтение результатов Wi-Fi запрещено: ${error.message ?: "SecurityException"}", "ERROR")
            TimedRadioSignals(emptyList(), null)
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(context, "RADIO_WIFI", "Ошибка чтения результатов Wi-Fi: ${error.javaClass.simpleName}", "ERROR")
            TimedRadioSignals(emptyList(), null)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun collectCells(forceRefresh: Boolean): TimedRadioSignals {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!RadioPermissionPolicy.canReadCellInfo(fineGranted)) {
            DiagnosticLogStore.log(context, "RADIO_CELL", "Сотовые данные пропущены: нет разрешения точной геолокации")
            return TimedRadioSignals(emptyList(), null)
        }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: run {
            DiagnosticLogStore.log(context, "RADIO_CELL", "TelephonyManager недоступен", "ERROR")
            return TimedRadioSignals(emptyList(), null)
        }
        return try {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val radioAccessFeatureAvailable = context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS
            )
            val shouldRefresh = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                radioAccessFeatureAvailable &&
                CellInfoRefreshPolicy.shouldRequestUpdate(
                    nowElapsedMillis = nowElapsedMs,
                    lastRequestElapsedMillis = lastCellInfoRefreshRequestedAtElapsedMs,
                    force = forceRefresh
                )
            val requestedCellInfo = if (shouldRefresh) {
                // Android Q+ getAllCellInfo() only exposes the current cache for apps targeting Q+.
                // requestCellInfoUpdate() asks the modem asynchronously; Android may rate-limit it.
                lastCellInfoRefreshRequestedAtElapsedMs = nowElapsedMs
                requestFreshCellInfo(telephony)
            } else null
            val snapshotSource = when {
                !radioAccessFeatureAvailable -> "cached API only: TELEPHONY_RADIO_ACCESS unavailable"
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "legacy getAllCellInfo"
                !shouldRefresh -> "Android cache (request cooldown)"
                requestedCellInfo == null -> "Android cache (refresh timeout/unavailable)"
                requestedCellInfo.isEmpty() -> "Android cache (refresh returned no cells)"
                else -> "requestCellInfoUpdate"
            }
            val allCellInfo = requestedCellInfo?.takeIf { it.isNotEmpty() }
                ?: telephony.allCellInfo.orEmpty()
            fun timestampMillis(info: CellInfo): Long =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.timestampMillis
                else info.timeStamp / 1_000_000L
            fun timestampNanos(info: CellInfo): Long =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    info.timestampMillis.takeIf {
                        it > 0L && it <= Long.MAX_VALUE / 1_000_000L
                    }?.times(1_000_000L) ?: 0L
                } else {
                    info.timeStamp.takeIf { it > 0L } ?: 0L
                }

            val freshCellInfo = allCellInfo.filter { info ->
                RadioFixReliability.isCellInfoFresh(timestampMillis(info), nowElapsedMs)
            }
            val parsed = freshCellInfo.mapNotNull { info ->
                parseCell(info)?.let { observation -> info to observation }
            }
                .distinctBy { (_, observation) ->
                    "${observation.type}:${observation.mcc}:${observation.mnc}:${observation.area}:${observation.cellId}"
                }
                .sortedByDescending { (_, observation) -> observation.rssi }
                .take(MAX_CELLS)
            val ageMillis = parsed.maxOfOrNull { (info, _) ->
                (nowElapsedMs - timestampMillis(info)).coerceAtLeast(0L)
            }
            DiagnosticLogStore.log(
                context, "RADIO_CELL",
                "CellInfo от Android: источник=$snapshotSource, всего=${allCellInfo.size}, " +
                    "актуальных=${freshCellInfo.size}, используется=${parsed.size}, " +
                    "возраст самого старого=${ageMillis?.div(1_000) ?: "unknown"}s, " +
                    "просрочено/некорректно=${allCellInfo.size - freshCellInfo.size}, " +
                    "неподдерживаемых/неполных=${freshCellInfo.size - parsed.size}"
            )
            TimedRadioSignals(parsed.map { (info, observation) ->
                observation.toSignal().copy(
                    observedAtElapsedRealtimeNanos = timestampNanos(info).takeIf { it > 0L }
                )
            }, ageMillis)
        } catch (error: SecurityException) {
            DiagnosticLogStore.log(context, "RADIO_CELL", "Чтение CellInfo запрещено: ${error.message ?: "SecurityException"}", "ERROR")
            TimedRadioSignals(emptyList(), null)
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(context, "RADIO_CELL", "Ошибка чтения CellInfo: ${error.javaClass.simpleName}", "ERROR")
            TimedRadioSignals(emptyList(), null)
        }
    }

    /**
     * Ask Android/the modem for a fresh CellInfo snapshot. This API is available from Android 10.
     * A timeout, rate limit, empty modem response or missing hardware feature falls back to
     * TelephonyManager.allCellInfo(), whose timestamps are still checked before use.
     */
    private suspend fun requestFreshCellInfo(telephony: TelephonyManager): List<CellInfo>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            withTimeoutOrNull(CellInfoRefreshPolicy.CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        telephony.requestCellInfoUpdate(
                            context.mainExecutor,
                            object : TelephonyManager.CellInfoCallback() {
                                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                    if (continuation.isActive) {
                                        continuation.resume(cellInfo.toList())
                                    }
                                }

                                override fun onError(errorCode: Int, detail: Throwable?) {
                                    DiagnosticLogStore.log(
                                        context,
                                        "RADIO_CELL",
                                        "Android не обновил CellInfo: modem error=$errorCode, detail=${detail?.javaClass?.simpleName ?: "none"}",
                                        "WARN"
                                    )
                                    if (continuation.isActive) continuation.resume(emptyList())
                                }
                            }
                        )
                    } catch (error: SecurityException) {
                        DiagnosticLogStore.log(
                            context, "RADIO_CELL",
                            "Запрос свежих CellInfo запрещён Android: ${error.message ?: "SecurityException"}",
                            "WARN"
                        )
                        if (continuation.isActive) continuation.resume(emptyList())
                    } catch (error: UnsupportedOperationException) {
                        DiagnosticLogStore.log(
                            context, "RADIO_CELL",
                            "Модем не поддерживает requestCellInfoUpdate(): ${error.javaClass.simpleName}",
                            "WARN"
                        )
                        if (continuation.isActive) continuation.resume(emptyList())
                    } catch (error: RuntimeException) {
                        DiagnosticLogStore.log(
                            context, "RADIO_CELL",
                            "Не удалось запросить обновление CellInfo: ${error.javaClass.simpleName}",
                            "WARN"
                        )
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    @Suppress("DEPRECATION")
    private fun parseCell(info: CellInfo): CellObservation? = try {
        when (info) {
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString?.toIntOrNull() else id.mcc
                val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString?.toIntOrNull() else id.mnc
                cell("gsm", mcc, mnc, id.lac, id.cid.toLong(), info.cellSignalStrength.dbm)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString?.toIntOrNull() else id.mcc
                val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString?.toIntOrNull() else id.mnc
                cell("wcdma", mcc, mnc, id.lac, id.cid.toLong(), info.cellSignalStrength.dbm)
            }
            is CellInfoLte -> {
                val id = info.cellIdentity
                val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString?.toIntOrNull() else id.mcc
                val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString?.toIntOrNull() else id.mnc
                cell("lte", mcc, mnc, id.tac, id.ci.toLong(), info.cellSignalStrength.dbm)
            }
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT < 29) null
                else {
                    val id = info.cellIdentity as? CellIdentityNr ?: return null
                    cell("nr", id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(),
                        id.tac, id.nci, info.cellSignalStrength.dbm)
                }
            }
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun cell(type: String, mcc: Int?, mnc: Int?, area: Int, id: Long, rssi: Int): CellObservation? {
        if (mcc == null || mnc == null || mcc !in 1..999 || mnc !in 0..999 ||
            area < 0 || id <= 0L || rssi !in -160..0) return null
        if (type != "nr" && id > Int.MAX_VALUE.toLong()) return null
        return CellObservation(type, mcc, mnc, area, id, rssi)
    }

    private suspend fun collectBluetooth(): TimedRadioSignals {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "BLE пропущен: нет разрешения точной геолокации")
            return TimedRadioSignals(emptyList(), null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "BLE пропущен: нет BLUETOOTH_SCAN")
            return TimedRadioSignals(emptyList(), null)
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: run {
            DiagnosticLogStore.log(context, "RADIO_BLE", "BluetoothManager недоступен", "ERROR")
            return TimedRadioSignals(emptyList(), null)
        }
        val adapter = try { manager.adapter } catch (error: SecurityException) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "Доступ к Bluetooth-адаптеру запрещён: ${error.message ?: "SecurityException"}", "ERROR")
            null
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "Не удалось получить Bluetooth-адаптер: ${error.javaClass.simpleName}", "ERROR")
            null
        } ?: return TimedRadioSignals(emptyList(), null)
        val scanner = try { adapter.bluetoothLeScanner } catch (error: SecurityException) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "Доступ к BLE scanner запрещён", "ERROR")
            null
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "BLE scanner недоступен: ${error.javaClass.simpleName}", "ERROR")
            null
        } ?: return TimedRadioSignals(emptyList(), null)
        val bluetoothEnabled = try {
            adapter.isEnabled
        } catch (error: SecurityException) {
            DiagnosticLogStore.log(
                context, "RADIO_BLE",
                "Android запретил проверку состояния Bluetooth; проверь BLUETOOTH_CONNECT: ${error.message ?: "SecurityException"}",
                "ERROR"
            )
            false
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "Не удалось проверить Bluetooth: ${error.javaClass.simpleName}", "ERROR")
            false
        }
        if (!bluetoothEnabled) {
            DiagnosticLogStore.log(context, "RADIO_BLE", "Bluetooth выключен или состояние адаптера недоступно")
            return TimedRadioSignals(emptyList(), null)
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val cachedAgeMs = if (lastBleScanFinishedAtElapsedMs > 0L &&
            nowElapsedMs >= lastBleScanFinishedAtElapsedMs) {
            nowElapsedMs - lastBleScanFinishedAtElapsedMs
        } else Long.MAX_VALUE

        // Avoid starting/stopping the Bluetooth scanner on every 1-second location tick.
        // If no recent cache exists during the cooldown, return no BLE data instead of bypassing it.
        if (!RadioFixReliability.shouldRequestBleScan(nowElapsedMs, lastBleScanStartedAtElapsedMs)) {
            return if (cachedBleSignals.isNotEmpty() && cachedAgeMs <= RadioFixReliability.BLE_MAX_CACHE_AGE_MS) {
                TimedRadioSignals(cachedBleSignals, cachedAgeMs, cachedBleProtocols)
            } else {
                TimedRadioSignals(emptyList(), null)
            }
        }

        lastBleScanStartedAtElapsedMs = nowElapsedMs
        val freshScan = suspendCancellableCoroutine<BleScanPayload> { continuation ->
            val seen = linkedMapOf<String, RadioSignal>()
            val seenProtocols = linkedSetOf<String>()
            val resultLock = Any()
            val completed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            lateinit var callback: ScanCallback

            fun snapshot(): BleScanPayload = synchronized(resultLock) {
                BleScanPayload(seen.values.toList(), seenProtocols.toSet())
            }

            // Scan failure and timeout can race. Only the first completion may resume the
            // coroutine, and callbacks cannot mutate results after the scan has completed.
            fun finish(results: BleScanPayload) {
                if (!completed.compareAndSet(false, true)) return
                if (continuation.isActive) continuation.resume(results)
            }

            val stopTask = object : Runnable {
                override fun run() {
                    try { scanner.stopScan(callback) } catch (_: SecurityException) { } catch (_: RuntimeException) { }
                    finish(snapshot())
                }
            }
            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (completed.get() || result.rssi !in -115..0) return

                    val scanRecord = result.scanRecord
                    val manufacturerData = buildMap<Int, ByteArray> {
                        val data = try { scanRecord?.manufacturerSpecificData } catch (_: RuntimeException) { null }
                        if (data != null) {
                            for (index in 0 until data.size()) {
                                val payload = data.valueAt(index) ?: continue
                                put(data.keyAt(index), payload)
                            }
                        }
                    }
                    val serviceData = try {
                        scanRecord?.serviceData?.mapNotNull { (uuid, payload) ->
                            payload?.let { uuid.uuid.toString() to it }
                        }?.toMap().orEmpty()
                    } catch (_: RuntimeException) {
                        emptyMap()
                    }

                    // Decode the supported frames once. Only protocol-defined stable IDs
                    // become position fingerprints; URL, telemetry and rotating EID remain
                    // metadata and never become synthetic beacon coordinates.
                    val frames = BleBeaconAdvertisementParser.parse(manufacturerData, serviceData)
                    val protocolNames = frames.mapNotNull { frame ->
                        when (frame) {
                            is BleBeaconFrame.StableIdentity -> frame.protocol
                            is BleBeaconFrame.EddystoneUrl -> "eddystone-url"
                            is BleBeaconFrame.EddystoneTelemetry -> "eddystone-tlm"
                            is BleBeaconFrame.EddystoneEphemeralId -> "eddystone-eid"
                        }
                    }.toSet()
                    val protocolIdentities = frames.filterIsInstance<BleBeaconFrame.StableIdentity>()
                        .map { it.identifier }
                        .distinct()

                    val identifiers = if (protocolIdentities.isNotEmpty()) {
                        protocolIdentities
                    } else if (Build.VERSION.SDK_INT >= 35 &&
                        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        // On API 35+ Android explicitly classifies the address. Keep only a
                        // reported public unicast address; RANDOM/ANONYMOUS/UNKNOWN are transient.
                        // BLUETOOTH_CONNECT is checked rather than requested by the scanner.
                        val device = try { result.device } catch (_: SecurityException) { null }
                            catch (_: RuntimeException) { null }
                        val addressType = try { device?.addressType } catch (_: SecurityException) { null }
                            catch (_: RuntimeException) { null }
                        val address = try { device?.address?.lowercase(Locale.ROOT) }
                            catch (_: SecurityException) { null }
                            catch (_: RuntimeException) { null }
                        if (BleAddressPolicy.isStablePublicAddress(
                                addressType = addressType,
                                publicAddressType = BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                                address = address
                            )
                        ) listOf(address!!) else emptyList()
                    } else {
                        // Older Android versions do not expose the advertiser address type.
                        // A MAC-only fallback would misclassify rotating private addresses.
                        emptyList()
                    }

                    val measuredAt = result.timestampNanos.takeIf { it > 0L }
                    synchronized(resultLock) {
                        if (!completed.get()) {
                            seenProtocols.addAll(protocolNames)
                            identifiers.forEach { identifier ->
                                val previous = seen[identifier]
                                if (previous == null || result.rssi > previous.rssi) {
                                    seen[identifier] = RadioSignal(
                                        "ble", identifier, result.rssi,
                                        observedAtElapsedRealtimeNanos = measuredAt
                                    )
                                }
                            }
                        }
                    }
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(0, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    DiagnosticLogStore.log(context, "RADIO_BLE", "BLE scan failed: Android errorCode=$errorCode")
                    handler.removeCallbacks(stopTask)
                    try { scanner.stopScan(this) } catch (_: SecurityException) { } catch (_: RuntimeException) { }
                    finish(snapshot())
                }
            }
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
                handler.postDelayed(stopTask, BLE_SCAN_MS)
            } catch (error: SecurityException) {
                DiagnosticLogStore.log(context, "RADIO_BLE", "BLE scan запрещён Android: ${error.message ?: "SecurityException"}", "ERROR")
                finish(BleScanPayload(emptyList(), emptySet()))
            } catch (error: RuntimeException) {
                DiagnosticLogStore.log(context, "RADIO_BLE", "Ошибка запуска BLE scan: ${error.javaClass.simpleName}", "ERROR")
                finish(BleScanPayload(emptyList(), emptySet()))
            }
            continuation.invokeOnCancellation {
                completed.set(true)
                handler.removeCallbacks(stopTask)
                try { scanner.stopScan(callback) } catch (_: SecurityException) { } catch (_: RuntimeException) { }
            }
        }

        val finishedAt = SystemClock.elapsedRealtime()
        if (freshScan.signals.isNotEmpty()) {
            cachedBleSignals = freshScan.signals
            cachedBleProtocols = freshScan.protocols
            lastBleScanFinishedAtElapsedMs = finishedAt
            return TimedRadioSignals(freshScan.signals, 0L, freshScan.protocols)
        }
        // If a BLE scan failed or returned nothing, a recent snapshot can bridge this one miss.
        // The age penalty and hard cache limit stop that old data from looking continuously fresh.
        val ageAfterAttempt = if (lastBleScanFinishedAtElapsedMs > 0L &&
            finishedAt >= lastBleScanFinishedAtElapsedMs) {
            finishedAt - lastBleScanFinishedAtElapsedMs
        } else Long.MAX_VALUE
        if (cachedBleSignals.isNotEmpty() && ageAfterAttempt <= RadioFixReliability.BLE_MAX_CACHE_AGE_MS) {
            return TimedRadioSignals(cachedBleSignals, ageAfterAttempt, cachedBleProtocols + freshScan.protocols)
        }
        cachedBleSignals = emptyList()
        cachedBleProtocols = emptySet()
        lastBleScanFinishedAtElapsedMs = 0L
        return TimedRadioSignals(emptyList(), null, freshScan.protocols)
    }

    companion object {
        private val scanMutex = Mutex()
        private val AUTO_REFRESH_LOCK = Any()
        private var lastAutomaticDatabaseSnapshotElapsedMs = 0L
        private const val AUTO_REFRESH_PREFS = "external_radio_database_auto_refresh"
        private const val KEY_BEACON_IDENTITY = "beacondb_identity_sha256"
        private const val KEY_BEACON_ATTEMPT_AT = "beacondb_last_attempt_at"
        private const val KEY_BEACON_SUCCESS_AT = "beacondb_last_success_at"
        private var lastWifiScanRequestAtElapsedMs = 0L
        private var lastCellInfoRefreshRequestedAtElapsedMs = 0L
        private var lastBleScanStartedAtElapsedMs = 0L
        private var lastBleScanFinishedAtElapsedMs = 0L
        private var cachedBleSignals: List<RadioSignal> = emptyList()
        private var cachedBleProtocols: Set<String> = emptySet()
        private const val WIFI_SCAN_WAIT_MS = 3_000L
        private const val WIFI_SCAN_POLL_MS = 250L
        private const val BLE_SCAN_MS = 2_500L
        private const val MAX_WIFI = 64
        private const val MAX_CELLS = 16
        private val MAC_REGEX = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        fun loadLastEstimate(context: Context): RadioEstimate? {
            val p = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            val lat = p.getString(ClearGpsPreferences.KEY_LAST_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = p.getString(ClearGpsPreferences.KEY_LAST_LON, null)?.toDoubleOrNull() ?: return null
            val accuracy = p.getString(ClearGpsPreferences.KEY_LAST_ACCURACY, null)?.toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                lon !in -180.0..180.0 || !accuracy.isFinite() || accuracy <= 0.0) return null
            return RadioEstimate(
                latitude = lat,
                longitude = lon,
                accuracyMeters = accuracy,
                updatedAt = p.getLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, 0L),
                fingerprint = p.getString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, "") ?: "",
                fallbackUsed = true,
                matchedCount = p.getInt(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT, 0),
                contributingSources = (p.getString(ClearGpsPreferences.KEY_LAST_CONTRIBUTING_SOURCES, "") ?: "")
                    .split(',').filter { it.isNotBlank() }.toSet(),
                sourceConflictDetected = p.getBoolean(ClearGpsPreferences.KEY_LAST_SOURCE_CONFLICT, false)
            )
        }

        fun saveEstimate(context: Context, estimate: RadioEstimate) {
            context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE).edit()
                .putString(ClearGpsPreferences.KEY_LAST_LAT, estimate.latitude.toString())
                .putString(ClearGpsPreferences.KEY_LAST_LON, estimate.longitude.toString())
                .putString(ClearGpsPreferences.KEY_LAST_ACCURACY, estimate.accuracyMeters.toString())
                .putLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, estimate.updatedAt)
                .putString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, estimate.fingerprint)
                .putInt(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT, estimate.matchedCount)
                .putString(ClearGpsPreferences.KEY_LAST_CONTRIBUTING_SOURCES, estimate.contributingSources.sorted().joinToString(","))
                .putBoolean(ClearGpsPreferences.KEY_LAST_SOURCE_CONFLICT, estimate.sourceConflictDetected)
                .apply()
        }
    }
}
