package org.cleargps

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class RadioEstimate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val updatedAt: Long,
    val fingerprint: String,
    val fallbackUsed: Boolean = false
)

data class RadioLookupResult(
    val estimate: RadioEstimate?,
    val wifiCount: Int,
    val cellCount: Int,
    val beaconCount: Int,
    val fromCache: Boolean,
    val message: String
)

data class ManualAnchor(val latitude: Double, val longitude: Double)

private data class CellObservation(
    val radioType: String,
    val mcc: Int,
    val mnc: Int,
    val area: Int,
    val cellId: Long,
    val signalDbm: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("radioType", radioType)
        .put("mobileCountryCode", mcc)
        .put("mobileNetworkCode", mnc)
        .put(if (radioType == "lte" || radioType == "nr") "trackingAreaCode" else "locationAreaCode", area)
        .put(if (radioType == "nr") "newRadioCellId" else "cellId", cellId)
        .put("signalStrength", signalDbm)
}

private data class RadioSnapshot(
    val payload: JSONObject,
    val wifiCount: Int,
    val cellCount: Int,
    val beaconCount: Int,
    val observedAt: Long
) {
    val fingerprint: String = stableRadioFingerprint(payload)
}

object ClearGpsPreferences {
    const val PREFS = "clear_gps"
    const val KEY_RADIO_CONSENT = "radio_lookup_consent"
    const val KEY_DAILY_REFRESH = "daily_radio_refresh"
    const val KEY_LAST_PAYLOAD = "radio_last_payload"
    const val KEY_LAST_FINGERPRINT = "radio_last_fingerprint"
    const val KEY_LAST_PAYLOAD_FINGERPRINT = "radio_last_payload_fingerprint"
    const val KEY_LAST_PAYLOAD_CAPTURED_AT = "radio_last_payload_captured_at"
    const val KEY_LAST_REFRESH_AT = "radio_last_refresh_at"
    const val KEY_LAST_LAT = "radio_last_lat"
    const val KEY_LAST_LON = "radio_last_lon"
    const val KEY_LAST_ACCURACY = "radio_last_accuracy"
    const val KEY_LAST_ESTIMATE_AT = "radio_last_estimate_at"
    const val KEY_LAST_FALLBACK = "radio_last_fallback"
    const val KEY_MANUAL_LAT = "manual_anchor_lat"
    const val KEY_MANUAL_LON = "manual_anchor_lon"
    const val KEY_MANUAL_SET_AT = "manual_anchor_set_at"
    const val KEY_OPEN_CELL_ID = "open_cell_id_token"
}

object ManualLocationStore {
    fun load(context: Context): ManualAnchor? {
        val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString(ClearGpsPreferences.KEY_MANUAL_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(ClearGpsPreferences.KEY_MANUAL_LON, null)?.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return ManualAnchor(lat, lon)
    }

    fun save(context: Context, anchor: ManualAnchor?) {
        val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (anchor == null) {
            edit.remove(ClearGpsPreferences.KEY_MANUAL_LAT)
                .remove(ClearGpsPreferences.KEY_MANUAL_LON)
                .remove(ClearGpsPreferences.KEY_MANUAL_SET_AT)
        } else {
            require(anchor.latitude.isFinite() && anchor.latitude in -90.0..90.0)
            require(anchor.longitude.isFinite() && anchor.longitude in -180.0..180.0)
            edit.putString(ClearGpsPreferences.KEY_MANUAL_LAT, anchor.latitude.toString())
                .putString(ClearGpsPreferences.KEY_MANUAL_LON, anchor.longitude.toString())
                .putLong(ClearGpsPreferences.KEY_MANUAL_SET_AT, System.currentTimeMillis())
        }
        edit.apply()
    }
}

/**
 * Radio identifiers are stored in app-private preferences only after the user accepts the
 * radio-location disclosure. They are sent to BeaconDB over HTTPS when a lookup is requested.
 * The app does not send SSIDs, IMEI, contacts, or the device's GPS coordinates.
 */
class RadioGeolocationRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)

    suspend fun locate(forceRefresh: Boolean): RadioLookupResult {
        val snapshot = withContext(Dispatchers.Main.immediate) { collectSnapshot() }
        val wifi = snapshot.wifiCount
        val cells = snapshot.cellCount
        val beacons = snapshot.beaconCount
        if (cells == 0 && wifi + beacons < 2) {
            val previousEstimate = loadLastEstimate(context)
            val detail = "Нужно минимум две Wi‑Fi/Bluetooth-точки или данные мобильной соты. Проверь разрешения и доступность сканирования."
            return RadioLookupResult(
                previousEstimate, wifi, cells, beacons, previousEstimate != null,
                detail + if (previousEstimate != null) " Показываю последнюю сохранённую оценку." else ""
            )
        }

        val now = System.currentTimeMillis()
        val savedFingerprint = prefs.getString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, null)
        val lastRefresh = prefs.getLong(ClearGpsPreferences.KEY_LAST_REFRESH_AT, 0L)
        val cached = loadLastEstimate(context)
        if (!forceRefresh && savedFingerprint == snapshot.fingerprint &&
            cached?.fingerprint == snapshot.fingerprint && now - lastRefresh in 0 until DAY_MS) {
            return RadioLookupResult(cached, wifi, cells, beacons, true, "Используется локальный кэш; обновление ещё не требуется.")
        }

        prefs.edit()
            .putString(ClearGpsPreferences.KEY_LAST_PAYLOAD, snapshot.payload.toString())
            .putString(ClearGpsPreferences.KEY_LAST_PAYLOAD_FINGERPRINT, snapshot.fingerprint)
            .putLong(ClearGpsPreferences.KEY_LAST_PAYLOAD_CAPTURED_AT, snapshot.observedAt)
            .apply()

        val response = withContext(Dispatchers.IO) {
            requestBeaconDb(snapshot.payload, snapshot.fingerprint, snapshot.observedAt)
        }
        if (response.estimate != null) {
            saveEstimate(context, response.estimate)
            prefs.edit().putLong(ClearGpsPreferences.KEY_LAST_REFRESH_AT, System.currentTimeMillis()).apply()
            scheduleDailyRefresh(context)
            return RadioLookupResult(response.estimate, wifi, cells, beacons, false, response.message)
        }
        val previousEstimate = loadLastEstimate(context)
        return RadioLookupResult(
            previousEstimate, wifi, cells, beacons, previousEstimate != null,
            response.message + if (previousEstimate != null) " Предыдущая сохранённая оценка оставлена без изменений." else ""
        )
    }

    private suspend fun collectSnapshot(): RadioSnapshot {
        val wifi = collectWifi()
        val cells = collectCells()
        val beacons = scanBluetoothBeacons()
        val payload = JSONObject()
            .put("considerIp", false)
            .put("fallbacks", JSONObject().put("ipf", false).put("lacf", false))

        if (wifi.isNotEmpty()) {
            val array = JSONArray()
            wifi.sortedBy { it.optString("macAddress") }.forEach(array::put)
            payload.put("wifiAccessPoints", array)
        }
        if (cells.isNotEmpty()) {
            val array = JSONArray()
            cells.distinctBy { "${it.radioType}:${it.mcc}:${it.mnc}:${it.area}:${it.cellId}" }
                .sortedWith(compareBy<CellObservation> { it.radioType }.thenBy { it.cellId })
                .take(MAX_CELLS).forEach { array.put(it.toJson()) }
            payload.put("cellTowers", array)
        }
        if (beacons.isNotEmpty()) {
            val array = JSONArray()
            beacons.sortedBy { it.optString("macAddress") }.forEach(array::put)
            payload.put("bluetoothBeacons", array)
        }
        return RadioSnapshot(payload, wifi.size, cells.size, beacons.size, System.currentTimeMillis())
    }

    @Suppress("DEPRECATION")
    private suspend fun collectWifi(): List<JSONObject> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        if (!manager.isWifiEnabled) return emptyList()
        try {
            // Android can throttle scans. Cached scan results are still used when a new scan is refused.
            manager.startScan()
            delay(1_800L)
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
        return try {
            manager.scanResults.asSequence()
                .filter { result ->
                    val ssid = result.SSID.orEmpty()
                    ssid.isNotBlank() && !ssid.endsWith("_nomap", ignoreCase = true) &&
                        isPublicMac(result.BSSID) && result.level in -110..0
                }
                .distinctBy { it.BSSID.lowercase(Locale.ROOT) }
                .sortedByDescending { it.level }
                .take(MAX_WIFI)
                .map { result ->
                    val ageMs = ((android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000L) -
                        result.timestamp / 1_000L).coerceAtLeast(0L)
                    JSONObject()
                        .put("macAddress", result.BSSID.lowercase(Locale.ROOT))
                        .put("signalStrength", result.level)
                        .put("frequency", result.frequency)
                        .put("age", ageMs)
                }.toList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectCells(): List<CellObservation> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
        return try {
            telephony.allCellInfo.orEmpty().mapNotNull(::parseCell)
                .distinctBy { "${it.radioType}:${it.mcc}:${it.mnc}:${it.area}:${it.cellId}" }
                .sortedByDescending { it.signalDbm }
                .take(MAX_CELLS)
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

    private fun cell(radio: String, mcc: Int?, mnc: Int?, area: Int, cid: Long, dbm: Int): CellObservation? {
        if (mcc == null || mnc == null || mcc !in 1..999 || mnc !in 0..999 ||
            area < 0 || cid <= 0L || dbm !in -160..0) return null
        if (radio != "nr" && cid > Int.MAX_VALUE.toLong()) return null
        return CellObservation(radio, mcc, mnc, area, cid, dbm)
    }

    private suspend fun scanBluetoothBeacons(): List<JSONObject> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val scanner = try { manager.adapter?.bluetoothLeScanner } catch (_: SecurityException) { null } ?: return emptyList()

        return suspendCancellableCoroutine { continuation ->
            val seen = linkedMapOf<String, JSONObject>()
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
                    val device = result.device ?: return
                    val address = device.address?.lowercase(Locale.ROOT) ?: return
                    if (!isPublicMac(address) || result.rssi !in -115..0) return
                    val name = try { result.scanRecord?.deviceName ?: device.name } catch (_: SecurityException) { null }
                    val hasServiceUuids = !result.scanRecord?.serviceUuids.isNullOrEmpty()
                    // Keep named/service-advertising beacons; avoid uploading randomised phone IDs.
                    if (name.isNullOrBlank() && !hasServiceUuids) return
                    // Device names can contain personal identifiers; never send them to the lookup service.
                    seen[address] = JSONObject()
                        .put("macAddress", address)
                        .put("signalStrength", result.rssi)
                        .put("age", 0)
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

    private data class LookupResponse(val estimate: RadioEstimate?, val code: Int, val message: String)
    private data class HttpBody(val code: Int, val body: String?)

    private fun requestOnce(client: OkHttpClient, payload: JSONObject): HttpBody {
        val request = Request.Builder()
            .url(BEACONDB_ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response -> HttpBody(response.code, response.body?.string()) }
    }

    private fun requestBeaconDb(payload: JSONObject, fingerprint: String, observedAt: Long): LookupResponse {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
        var response: HttpBody
        try {
            response = requestOnce(client, payload)
            // Compatibility fallback for deployments that do not yet support NR cell IDs.
            if (response.code == 400) {
                val cells = payload.optJSONArray("cellTowers")
                if (cells != null && (0 until cells.length()).any { cells.optJSONObject(it)?.optString("radioType") == "nr" }) {
                    val retryPayload = JSONObject(payload.toString())
                    val filtered = JSONArray()
                    for (i in 0 until cells.length()) {
                        val cell = cells.optJSONObject(i) ?: continue
                        if (cell.optString("radioType") != "nr") filtered.put(cell)
                    }
                    if (filtered.length() > 0) {
                        retryPayload.put("cellTowers", filtered)
                        response = requestOnce(client, retryPayload)
                    }
                }
            }
        } catch (_: IOException) {
            return LookupResponse(null, 0, "Не удалось подключиться к BeaconDB. Проверь интернет или повтори позже.")
        }

        if (response.code !in 200..299) {
            val message = when (response.code) {
                400 -> "База отклонила часть радиоданных. Попробуй обновить источники позже."
                404 -> "В базе пока нет совпадений для этих сетей. Это не значит, что местоположения нет."
                429 -> "Слишком много запросов к базе. Попробуй позже."
                in 500..599 -> "Сервер базы временно недоступен (${response.code})."
                else -> "BeaconDB вернула HTTP ${response.code}."
            }
            return LookupResponse(null, response.code, message)
        }
        val json = try { JSONObject(response.body.orEmpty()) } catch (_: Exception) {
            return LookupResponse(null, response.code, "Не удалось разобрать ответ сервера.")
        }
        val point = json.optJSONObject("location")
            ?: return LookupResponse(null, response.code, "Для этих сетей база не вернула координаты.")
        val lat = point.optDouble("lat", Double.NaN)
        val lon = point.optDouble("lng", Double.NaN)
        val accuracy = json.optDouble("accuracy", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0 || !accuracy.isFinite() || accuracy <= 0.0) {
            return LookupResponse(null, response.code, "Сервер вернул некорректные координаты.")
        }
        val fallbackUsed = json.has("fallback") || json.has("fallbacks")
        val estimate = RadioEstimate(lat, lon, accuracy.coerceAtMost(100_000.0),
            observedAt, fingerprint, fallbackUsed)
        val message = if (fallbackUsed) {
            "Получена резервная оценка; ориентируйся на радиус точности."
        } else {
            "BeaconDB рассчитала примерное положение по доступным радиосетям."
        }
        return LookupResponse(estimate, response.code, message)
    }

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val BLE_SCAN_MS = 3_000L
        private const val MAX_WIFI = 24
        private const val MAX_CELLS = 12
        private const val BEACONDB_ENDPOINT = "https://api.beacondb.net/v1/geolocate"
        private const val USER_AGENT = "ClearGPS/0.2 (https://github.com/MAKAKAIglitch/clear-gps)"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DAILY_WORK_NAME = "clear-gps-radio-db-refresh"
        private const val DAILY_WORK_TAG = "clear-gps-daily-radio-refresh"

        fun loadLastEstimate(context: Context): RadioEstimate? {
            val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            val lat = prefs.getString(ClearGpsPreferences.KEY_LAST_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString(ClearGpsPreferences.KEY_LAST_LON, null)?.toDoubleOrNull() ?: return null
            val accuracy = prefs.getString(ClearGpsPreferences.KEY_LAST_ACCURACY, null)?.toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                lon !in -180.0..180.0 || !accuracy.isFinite() || accuracy <= 0) return null
            return RadioEstimate(lat, lon, accuracy,
                prefs.getLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, 0L),
                prefs.getString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, "") ?: "",
                prefs.getBoolean(ClearGpsPreferences.KEY_LAST_FALLBACK, false))
        }

        private fun saveEstimate(context: Context, estimate: RadioEstimate) {
            context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE).edit()
                .putString(ClearGpsPreferences.KEY_LAST_LAT, estimate.latitude.toString())
                .putString(ClearGpsPreferences.KEY_LAST_LON, estimate.longitude.toString())
                .putString(ClearGpsPreferences.KEY_LAST_ACCURACY, estimate.accuracyMeters.toString())
                .putLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, estimate.updatedAt)
                .putString(ClearGpsPreferences.KEY_LAST_FINGERPRINT, estimate.fingerprint)
                .putBoolean(ClearGpsPreferences.KEY_LAST_FALLBACK, estimate.fallbackUsed)
                .apply()
        }

        fun scheduleDailyRefresh(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<DailyBeaconRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints).addTag(DAILY_WORK_TAG).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelDailyRefresh(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK_NAME)
        }

        suspend fun refreshSavedObservation(context: Context): Boolean {
            val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(ClearGpsPreferences.KEY_LAST_PAYLOAD, null) ?: return false
            val fingerprint = prefs.getString(ClearGpsPreferences.KEY_LAST_PAYLOAD_FINGERPRINT, null) ?: return false
            val observedAt = prefs.getLong(ClearGpsPreferences.KEY_LAST_PAYLOAD_CAPTURED_AT, 0L)
            if (observedAt <= 0L) return false
            if (!prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false) ||
                !prefs.getBoolean(ClearGpsPreferences.KEY_DAILY_REFRESH, true)) return false

            val repository = RadioGeolocationRepository(context)
            val response = withContext(Dispatchers.IO) {
                repository.requestSaved(JSONObject(raw), fingerprint, observedAt)
            }
            if (response.estimate != null) {
                saveEstimate(context, response.estimate)
                prefs.edit().putLong(ClearGpsPreferences.KEY_LAST_REFRESH_AT, System.currentTimeMillis()).apply()
                return true
            }
            return false
        }

        private fun isPublicMac(address: String): Boolean {
            val pieces = address.split(':')
            if (pieces.size != 6 || pieces.any { it.length != 2 || it.toIntOrNull(16) == null }) return false
            val first = pieces.first().toInt(16)
            return (first and 0x01) == 0 && (first and 0x02) == 0
        }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun requestSaved(payload: JSONObject, fingerprint: String, observedAt: Long): LookupResponse =
        requestBeaconDb(payload, fingerprint, observedAt)
}

class DailyBeaconRefreshWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : androidx.work.CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false) ||
            !prefs.getBoolean(ClearGpsPreferences.KEY_DAILY_REFRESH, true) ||
            prefs.getString(ClearGpsPreferences.KEY_LAST_PAYLOAD, null) == null) return Result.success()
        return try {
            if (RadioGeolocationRepository.refreshSavedObservation(applicationContext)) Result.success() else Result.retry()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun isPublicMac(address: String): Boolean {
    val pieces = address.split(':')
    if (pieces.size != 6 || pieces.any { it.length != 2 || it.toIntOrNull(16) == null }) return false
    val first = pieces.first().toInt(16)
    return (first and 0x01) == 0 && (first and 0x02) == 0
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

private fun stableRadioFingerprint(payload: JSONObject): String {
    val canonical = JSONObject(payload.toString())
    for (key in arrayOf("wifiAccessPoints", "cellTowers", "bluetoothBeacons")) {
        val source = canonical.optJSONArray(key) ?: continue
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.remove("age")
        }
    }
    return digest(canonical.toString())
}
