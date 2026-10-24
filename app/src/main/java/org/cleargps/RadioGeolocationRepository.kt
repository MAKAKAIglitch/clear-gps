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
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

private data class CellObservation(
    val type: String, val mcc: Int, val mnc: Int, val area: Int, val cellId: Long, val rssi: Int
) {
    fun toSignal() = RadioSignal("cell", "$type:$mcc:$mnc:$area:$cellId", rssi)
}

private data class ScanSnapshot(val signals: List<RadioSignal>, val wifiCount: Int, val cellCount: Int, val bleCount: Int)

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
    ): RadioLookupResult {
        val snapshot = withContext(Dispatchers.Main.immediate) { collectSnapshot() }
        val estimate = withContext(Dispatchers.IO) {
            database.estimatePosition(snapshot.signals, useWifi, useCells, useBle)
        }
        val recorded = withContext(Dispatchers.IO) {
            database.recordScan(snapshot.signals, reference, estimate, mode,
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
                "Сканирование завершено, но Android не вернул доступных радиосигналов. Проверь разрешения, включённые радио и системные ограничения."
            estimate != null ->
                "Сканирование завершено: $summary. Совпадений в локальной базе: ${estimate.matchedCount}; оценка приблизительная, радиус ±${estimate.accuracyMeters.toInt()} м."
            else ->
                "Сканирование завершено: $summary. Для этих сигналов нет сохранённых координат. Запиши точки на месте с известной позицией или отредактируй координаты вручную."
        }
        return RadioLookupResult(estimate, snapshot.wifiCount, snapshot.cellCount, snapshot.bleCount,
            false, message, estimate?.matchedCount ?: 0, recorded)
    }

    private suspend fun collectSnapshot(): ScanSnapshot {
        val wifi = collectWifi()
        val cells = collectCells()
        val ble = collectBluetooth()
        return ScanSnapshot(wifi + cells + ble, wifi.size, cells.size, ble.size)
    }

    @Suppress("DEPRECATION")
    private suspend fun collectWifi(): List<RadioSignal> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        if (!manager.isWifiEnabled) return emptyList()
        try {
            manager.startScan()
            delay(WIFI_SCAN_WAIT_MS)
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
        return try {
            manager.scanResults.asSequence()
                .filter { result ->
                    val ssidForOptOutOnly = result.SSID.orEmpty()
                    // Respect the common Wi-Fi location opt-out suffix; never store the SSID.
                    !ssidForOptOutOnly.endsWith("_nomap", ignoreCase = true) &&
                        result.BSSID.isNotBlank() && result.BSSID.matches(MAC_REGEX) && result.level in -110..0
                }
                .distinctBy { it.BSSID.lowercase(Locale.ROOT) }
                .sortedByDescending { it.level }
                .take(MAX_WIFI)
                .map { RadioSignal("wifi", it.BSSID.lowercase(Locale.ROOT), it.level, it.frequency) }
                .toList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectCells(): List<RadioSignal> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
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

    private suspend fun collectBluetooth(): List<RadioSignal> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val scanner = try { manager.adapter?.bluetoothLeScanner } catch (_: SecurityException) { null } ?: return emptyList()
        return suspendCancellableCoroutine { continuation ->
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
    }

    companion object {
        private const val WIFI_SCAN_WAIT_MS = 1_500L
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
