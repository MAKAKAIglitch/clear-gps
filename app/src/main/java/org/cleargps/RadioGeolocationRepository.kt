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
        // The UI, recording loop and foreground service can all request a scan. Serialize
        // them process-wide so concurrent Wi-Fi/BLE scans do not cancel or throttle each other.
        val fineLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val precisePermissionHint = if (RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(
                sdkInt = Build.VERSION.SDK_INT,
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
        val estimate = withContext(Dispatchers.IO) {
            database.estimatePosition(snapshot.signals, useWifi, useCells, useBle)
        }?.let { candidate ->
            val staleWifiPenalty = RadioFixReliability.wifiAgePenaltyMeters(
                ageMillis = snapshot.wifiAgeMillis,
                wifiSignalCount = snapshot.wifiCount,
                totalSignalCount = snapshot.signals.size
            )
            val staleBlePenalty = RadioFixReliability.bleAgePenaltyMeters(
                ageMillis = snapshot.bleAgeMillis,
                bleSignalCount = snapshot.bleCount,
                totalSignalCount = snapshot.signals.size
            )
            val totalStalePenalty = staleWifiPenalty + staleBlePenalty
            if (totalStalePenalty <= 0.0) candidate
            else candidate.copy(
                accuracyMeters = (candidate.accuracyMeters + totalStalePenalty).coerceAtMost(50_000.0)
            )
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
                "Сканирование завершено: $summary. Радиосигналы получены, но надёжная координата не подтверждена: подходящих записей с координатами мало, совпадения неоднозначны или противоречат друг другу."
        }
        val cacheAgeHints = buildList {
            snapshot.wifiAgeMillis
                ?.takeIf { it > RadioFixReliability.WIFI_FRESH_AGE_MS }
                ?.let { add("Wi‑Fi: кэш ${it / 1_000} с") }
            snapshot.bleAgeMillis
                ?.takeIf { it > RadioFixReliability.BLE_FRESH_AGE_MS }
                ?.let { add("Bluetooth: кэш ${it / 1_000} с") }
        }.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " Используется кэш радиосигналов (", postfix = "); погрешность увеличена.")
            .orEmpty()
        val fullMessage = message + cacheAgeHints + precisePermissionHint
        prefs.edit()
            .putString(ClearGpsPreferences.KEY_LAST_SCAN_MESSAGE, fullMessage)
            .putBoolean(ClearGpsPreferences.KEY_LAST_SCAN_HAS_ESTIMATE, estimate != null)
            .apply()
        RadioLookupResult(
            estimate, snapshot.wifiCount, snapshot.cellCount, snapshot.bleCount,
            snapshot.wifiAgeMillis?.let { it > RadioFixReliability.WIFI_FRESH_AGE_MS } == true ||
                snapshot.bleAgeMillis?.let { it > RadioFixReliability.BLE_FRESH_AGE_MS } == true,
            fullMessage, estimate?.matchedCount ?: 0, recorded
        )
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
        val cells = if (useCells) collectCells() else emptyList()
        val wifi = wifiTask.await()
        val ble = bleTask.await()
        ScanSnapshot(
            signals = wifi.signals + cells + ble.signals,
            wifiCount = wifi.signals.size,
            cellCount = cells.size,
            bleCount = ble.signals.size,
            wifiAgeMillis = wifi.ageMillis,
            bleAgeMillis = ble.ageMillis
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun collectWifi(): TimedRadioSignals {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!RadioPermissionPolicy.canReadWifiScan(Build.VERSION.SDK_INT, fineGranted, coarseGranted)) {
            return TimedRadioSignals(emptyList(), null)
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return TimedRadioSignals(emptyList(), null)
        if (!manager.isWifiEnabled) return TimedRadioSignals(emptyList(), null)

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
            val ageMillis = validResults.minOfOrNull { (nowMicros - it.timestamp) / 1_000L }
            val signals = validResults.asSequence()
                .distinctBy { it.BSSID.lowercase(Locale.ROOT) }
                .sortedByDescending { it.level }
                .take(MAX_WIFI)
                .map {
                    val ssid = it.SSID.orEmpty().takeUnless { name ->
                        name == WifiManager.UNKNOWN_SSID || name.equals("<unknown ssid>", ignoreCase = true)
                    }.orEmpty().take(100)
                    RadioSignal("wifi", it.BSSID.lowercase(Locale.ROOT), it.level, it.frequency, ssid)
                }
                .toList()
            TimedRadioSignals(signals, ageMillis)
        } catch (_: SecurityException) {
            TimedRadioSignals(emptyList(), null)
        } catch (_: RuntimeException) {
            TimedRadioSignals(emptyList(), null)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectCells(): List<RadioSignal> {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!RadioPermissionPolicy.canReadCellInfo(fineGranted)) return emptyList()
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
        return try {
            telephony.allCellInfo.orEmpty().mapNotNull(::parseCell)
                .distinctBy { "${it.type}:${it.mcc}:${it.mnc}:${it.area}:${it.cellId}" }
                .sortedByDescending { it.rssi }
                .take(MAX_CELLS)
                .map { it.toSignal() }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
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
            return TimedRadioSignals(emptyList(), null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return TimedRadioSignals(emptyList(), null)
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return TimedRadioSignals(emptyList(), null)
        val adapter = try { manager.adapter } catch (_: SecurityException) { null }
            catch (_: RuntimeException) { null } ?: return TimedRadioSignals(emptyList(), null)
        val scanner = try { adapter.bluetoothLeScanner } catch (_: SecurityException) { null }
            catch (_: RuntimeException) { null } ?: return TimedRadioSignals(emptyList(), null)
        val bluetoothEnabled = try { adapter.isEnabled }
            catch (_: SecurityException) { false }
            catch (_: RuntimeException) { false }
        if (!bluetoothEnabled) return TimedRadioSignals(emptyList(), null)

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
            val handler = Handler(Looper.getMainLooper())
            lateinit var callback: ScanCallback
            val stopTask = object : Runnable {
                override fun run() {
                    try { scanner.stopScan(callback) } catch (_: SecurityException) { } catch (_: RuntimeException) { }
                    if (continuation.isActive) continuation.resume(seen.values.toList())
                }
            }
            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val address = try { result.device?.address?.lowercase(Locale.ROOT) } catch (_: SecurityException) { null }
                    if (address.isNullOrBlank() || !address.matches(MAC_REGEX) || result.rssi !in -115..0) return
                    // Do not read or store Bluetooth device names or advertisement payload contents.
                    seen[address] = RadioSignal("ble", address, result.rssi)
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(0, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.removeCallbacks(stopTask)
                    try { scanner.stopScan(this) } catch (_: SecurityException) { } catch (_: RuntimeException) { }
                    if (continuation.isActive) continuation.resume(seen.values.toList())
                }
            }
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
                handler.postDelayed(stopTask, BLE_SCAN_MS)
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(emptyList())
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resume(emptyList())
            }
            continuation.invokeOnCancellation {
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
