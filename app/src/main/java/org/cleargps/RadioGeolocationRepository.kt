package org.cleargps

import android.Manifest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
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

private data class TimedRadioSignals(val signals: List<RadioSignal>, val ageMillis: Long?)

private data class ScanSnapshot(
    val signals: List<RadioSignal>,
    val wifiCount: Int,
    val cellCount: Int,
    val bleCount: Int,
    val wifiAgeMillis: Long?,
    val cellAgeMillis: Long?,
    val bleAgeMillis: Long?
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
        val estimate = withContext(Dispatchers.IO) {
            database.estimatePosition(snapshot.signals, useWifi, useCells, useBle)
        }?.let { candidate ->
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
            val totalStalePenalty = staleWifiPenalty + staleCellPenalty + staleBlePenalty
            // updatedAt denotes the age of the evidence, not the time we finished calculating.
            // Otherwise repeatedly reading the same Android cache refreshes the fix timestamp
            // forever and the UI can incorrectly label a stale radio result as fresh.
            val oldestEvidenceAgeMillis = listOfNotNull(
                snapshot.wifiAgeMillis.takeIf { snapshot.wifiCount > 0 },
                snapshot.cellAgeMillis.takeIf { snapshot.cellCount > 0 },
                snapshot.bleAgeMillis.takeIf { snapshot.bleCount > 0 }
            ).maxOrNull()?.coerceAtLeast(0L) ?: 0L
            val evidenceTimestamp = (System.currentTimeMillis() - oldestEvidenceAgeMillis).coerceAtLeast(0L)
            candidate.copy(
                updatedAt = evidenceTimestamp,
                accuracyMeters = (candidate.accuracyMeters + totalStalePenalty).coerceAtMost(50_000.0)
            )
        }
        val matchCounts = withContext(Dispatchers.IO) {
            database.countMatchingPoints(snapshot.signals, useWifi, useCells, useBle)
        }
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

        val summary = buildList {
            if (snapshot.wifiCount > 0) add("${snapshot.wifiCount} Wi‑Fi")
            if (snapshot.cellCount > 0) add("${snapshot.cellCount} сот")
            if (snapshot.bleCount > 0) add("${snapshot.bleCount} BLE")
        }.joinToString(", ").ifBlank { "радиосигналы не обнаружены" }
        val message = when {
            snapshot.signals.isEmpty() ->
                "Сканирование завершено, но Android не вернул доступных радиосигналов. Проверь разрешения, включённые радио, системную геолокацию и ограничения Android."
            estimate != null -> {
                val method = if (estimate.fallbackUsed) "по координатам локальных радиоточек" else "по похожему историческому радиоснимку"
                "Сканирование завершено: $summary. Метод расчёта: $method. Совпадений: ${estimate.matchedCount}; оценка приблизительная, радиус ±${estimate.accuracyMeters.toInt()} м."
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
                (estimate?.let { "оценка есть, совпадений=${it.matchedCount}, точность≈${it.accuracyMeters.toInt()} м, fallback=${it.fallbackUsed}" }
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
     * Requests one cell-only estimate from BeaconDB. The preference is checked here as well as
     * in the UI so no caller can accidentally contact the service when permission is off.
     * This path reads Android's current cellular snapshot only; it does not use or modify
     * LocalRadioDatabase and never submits contribution records.
     */
    suspend fun lookupBeaconDbCellsOnly(): BeaconDbLookupResult {
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

        val signals = withContext(Dispatchers.Main.immediate) { collectCells().signals }
        val parsedCells = signals.mapNotNull { signal -> BeaconDbProtocol.cellFromSignal(signal) }
            .distinctBy { it.identifier }
        val unsupportedCount = (signals.size - parsedCells.size).coerceAtLeast(0)

        // Recheck immediately before networking in case the user disabled BeaconDB while
        // Android's telephony snapshot was being collected.
        if (!prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)) {
            return BeaconDbLookupResult(
                cells = emptyList(),
                unsupportedCellCount = unsupportedCount,
                message = "Запросы BeaconDB были выключены до отправки. Сетевого обращения не было."
            )
        }
        if (parsedCells.isEmpty()) {
            val detail = if (unsupportedCount > 0) {
                " Android сообщил только неподдерживаемые типы ячеек; сетевой запрос не выполнялся."
            } else {
                " Android не предоставил актуальных сотовых ячеек; сетевой запрос не выполнялся."
            }
            return BeaconDbLookupResult(
                cells = emptyList(),
                unsupportedCellCount = unsupportedCount,
                message = "Нет подходящих данных сотовой сети." + detail
            )
        }

        return BeaconDbClient.lookup(parsedCells, unsupportedCount)
    }

    private suspend fun collectSnapshot(
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean
    ): ScanSnapshot = coroutineScope {
        // Only collect sources the user enabled. Wi-Fi and BLE run concurrently, while cellular
        // information is read synchronously from Android's current telephony snapshot.
        val wifiTask = async {
            if (useWifi) collectWifi() else TimedRadioSignals(emptyList(), null)
        }
        val bleTask = async {
            if (useBle) collectBluetooth() else TimedRadioSignals(emptyList(), null)
        }
        val cells = if (useCells) collectCells() else TimedRadioSignals(emptyList(), null)
        val wifi = wifiTask.await()
        val ble = bleTask.await()
        ScanSnapshot(
            signals = wifi.signals + cells.signals + ble.signals,
            wifiCount = wifi.signals.size,
            cellCount = cells.signals.size,
            bleCount = ble.signals.size,
            wifiAgeMillis = wifi.ageMillis,
            cellAgeMillis = cells.ageMillis,
            bleAgeMillis = ble.ageMillis
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
    private fun collectCells(): TimedRadioSignals {
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
            val allCellInfo = telephony.allCellInfo.orEmpty()
            val nowElapsedMs = SystemClock.elapsedRealtime()
            fun timestampMillis(info: CellInfo): Long =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.timestampMillis
                else info.timeStamp / 1_000_000L

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
                "CellInfo от Android: всего=${allCellInfo.size}, актуальных=${freshCellInfo.size}, " +
                    "используется=${parsed.size}, возраст самого старого=${ageMillis?.div(1_000) ?: "unknown"}s, " +
                    "просрочено/некорректно=${allCellInfo.size - freshCellInfo.size}"
            )
            TimedRadioSignals(parsed.map { (info, observation) ->
                observation.toSignal().copy(observedAtElapsedRealtimeNanos = info.timeStamp.takeIf { it > 0L })
            }, ageMillis)
        } catch (error: SecurityException) {
            DiagnosticLogStore.log(context, "RADIO_CELL", "Чтение CellInfo запрещено: ${error.message ?: "SecurityException"}", "ERROR")
            TimedRadioSignals(emptyList(), null)
        } catch (error: RuntimeException) {
            DiagnosticLogStore.log(context, "RADIO_CELL", "Ошибка чтения CellInfo: ${error.javaClass.simpleName}", "ERROR")
            TimedRadioSignals(emptyList(), null)
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
                TimedRadioSignals(cachedBleSignals, cachedAgeMs)
            } else {
                TimedRadioSignals(emptyList(), null)
            }
        }

        lastBleScanStartedAtElapsedMs = nowElapsedMs
        val freshSignals = suspendCancellableCoroutine<List<RadioSignal>> { continuation ->
            val seen = linkedMapOf<String, RadioSignal>()
            val resultLock = Any()
            val completed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            lateinit var callback: ScanCallback

            fun snapshot(): List<RadioSignal> = synchronized(resultLock) { seen.values.toList() }

            // Scan failure and timeout can race. Only the first completion may resume the
            // coroutine, and callbacks cannot mutate results after the scan has completed.
            fun finish(results: List<RadioSignal>) {
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
                    if (completed.get()) return
                    val address = try { result.device?.address?.lowercase(Locale.ROOT) } catch (_: SecurityException) { null }
                    if (address.isNullOrBlank() || !address.matches(MAC_REGEX) || result.rssi !in -115..0) return
                    // Do not read or store Bluetooth device names or advertisement payload contents.
                    synchronized(resultLock) {
                        if (!completed.get()) seen[address] = RadioSignal(
                            "ble", address, result.rssi,
                            observedAtElapsedRealtimeNanos = result.timestampNanos.takeIf { it > 0L }
                        )
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
                finish(emptyList())
            } catch (error: RuntimeException) {
                DiagnosticLogStore.log(context, "RADIO_BLE", "Ошибка запуска BLE scan: ${error.javaClass.simpleName}", "ERROR")
                finish(emptyList())
            }
            continuation.invokeOnCancellation {
                completed.set(true)
                handler.removeCallbacks(stopTask)
                try { scanner.stopScan(callback) } catch (_: SecurityException) { } catch (_: RuntimeException) { }
            }
        }

        val finishedAt = SystemClock.elapsedRealtime()
        if (freshSignals.isNotEmpty()) {
            cachedBleSignals = freshSignals
            lastBleScanFinishedAtElapsedMs = finishedAt
            return TimedRadioSignals(freshSignals, 0L)
        }
        // If a BLE scan failed or returned nothing, a recent snapshot can bridge this one miss.
        // The age penalty and hard cache limit stop that old data from looking continuously fresh.
        val ageAfterAttempt = if (lastBleScanFinishedAtElapsedMs > 0L &&
            finishedAt >= lastBleScanFinishedAtElapsedMs) {
            finishedAt - lastBleScanFinishedAtElapsedMs
        } else Long.MAX_VALUE
        if (cachedBleSignals.isNotEmpty() && ageAfterAttempt <= RadioFixReliability.BLE_MAX_CACHE_AGE_MS) {
            return TimedRadioSignals(cachedBleSignals, ageAfterAttempt)
        }
        cachedBleSignals = emptyList()
        lastBleScanFinishedAtElapsedMs = 0L
        return TimedRadioSignals(emptyList(), null)
    }

    companion object {
        private val scanMutex = Mutex()
        private var lastWifiScanRequestAtElapsedMs = 0L
        private var lastBleScanStartedAtElapsedMs = 0L
        private var lastBleScanFinishedAtElapsedMs = 0L
        private var cachedBleSignals: List<RadioSignal> = emptyList()
        private const val WIFI_SCAN_WAIT_MS = 3_000L
        private const val WIFI_SCAN_POLL_MS = 250L
        private const val BLE_SCAN_MS = 2_500L
        private const val MAX_WIFI = 32
        private const val MAX_CELLS = 16
        private val MAC_REGEX = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        fun loadLastEstimate(context: Context): RadioEstimate? {
            val p = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            val lat = p.getString(ClearGpsPreferences.KEY_LAST_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = p.getString(ClearGpsPreferences.KEY_LAST_LON, null)?.toDoubleOrNull() ?: return null
            val accuracy = p.getString(ClearGpsPreferences.KEY_LAST_ACCURACY, null)?.toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                lon !in -180.0..180.0 || !accuracy.isFinite() || accuracy <= 0.0) return null
            return RadioEstimate(lat, lon, accuracy,
                p.getLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, 0L),
                p.getString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, "") ?: "", true,
                p.getInt(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT, 0))
        }

        fun saveEstimate(context: Context, estimate: RadioEstimate) {
            context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE).edit()
                .putString(ClearGpsPreferences.KEY_LAST_LAT, estimate.latitude.toString())
                .putString(ClearGpsPreferences.KEY_LAST_LON, estimate.longitude.toString())
                .putString(ClearGpsPreferences.KEY_LAST_ACCURACY, estimate.accuracyMeters.toString())
                .putLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, estimate.updatedAt)
                .putString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, estimate.fingerprint)
                .putInt(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT, estimate.matchedCount)
                .apply()
        }
    }
}
