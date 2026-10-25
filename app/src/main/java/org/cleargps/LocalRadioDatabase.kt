package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RadioSignal(
    val type: String, val identifier: String, val rssi: Int,
    val frequency: Int = 0, val ssid: String = ""
)

data class RadioPointRecord(
    val id: Long, val type: String, val identifier: String, val label: String, val ssid: String, val notes: String,
    val latitude: Double?, val longitude: Double?, val accuracyMeters: Double?,
    val rssi: Int, val frequency: Int, val firstSeen: Long, val lastSeen: Long,
    val sightings: Int, val positionSource: String
)

data class RadioScanHistoryItem(
    val id: Long, val time: Long, val wifiCount: Int, val cellCount: Int, val bleCount: Int,
    val matchedCount: Int, val latitude: Double?, val longitude: Double?,
    val accuracyMeters: Double?, val mode: String, val recorded: Boolean
)

data class RadioEstimate(
    val latitude: Double, val longitude: Double, val accuracyMeters: Double, val updatedAt: Long,
    val fingerprint: String, val fallbackUsed: Boolean = false, val matchedCount: Int = 0
)

data class RadioLookupResult(
    val estimate: RadioEstimate?, val wifiCount: Int, val cellCount: Int, val beaconCount: Int,
    val fromCache: Boolean, val message: String, val matchedCount: Int = 0, val recordedCount: Int = 0
)

data class ManualAnchor(val latitude: Double, val longitude: Double)
data class RadioReference(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val source: String)

object ClearGpsPreferences {
    const val PREFS = "clear_gps"
    const val KEY_SCAN_INTERVAL_SECONDS = "scan_interval_seconds"
    const val KEY_ADAPTIVE_SCAN = "adaptive_scan_enabled"
    const val KEY_MAP_SOURCE = "map_source_mode"
    const val KEY_LAST_LAT = "radio_last_lat"
    const val KEY_LAST_LON = "radio_last_lon"
    const val KEY_LAST_ACCURACY = "radio_last_accuracy"
    const val KEY_LAST_ESTIMATE_AT = "radio_last_estimate_at"
    const val KEY_LAST_FINGERPRINT = "radio_last_fingerprint"
    const val KEY_LAST_MATCHED_COUNT = "radio_last_matched_count"
    const val KEY_LAST_WIFI_COUNT = "radio_last_wifi_count"
    const val KEY_LAST_CELL_COUNT = "radio_last_cell_count"
    const val KEY_LAST_BLE_COUNT = "radio_last_ble_count"
    const val KEY_LAST_SCAN_AT = "radio_last_scan_at"
    const val KEY_LAST_RECORDED_COUNT = "radio_last_recorded_count"
    const val KEY_MANUAL_LAT = "manual_anchor_lat"
    const val KEY_MANUAL_LON = "manual_anchor_lon"
    const val KEY_MANUAL_SET_AT = "manual_anchor_set_at"
    const val KEY_MANUAL_FALLBACK_ENABLED = "manual_anchor_fallback_enabled"
    const val KEY_POSITION_MODE = "position_mode"
    const val KEY_RECORDING = "radio_recording"
    const val KEY_USE_WIFI = "radio_use_wifi"
    const val KEY_USE_CELLS = "radio_use_cells"
    const val KEY_USE_BLE = "radio_use_ble"
    const val KEY_MAP_STYLE = "map_style"
}

object ManualLocationStore {
    fun load(context: Context): ManualAnchor? {
        val p = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        val lat = p.getString(ClearGpsPreferences.KEY_MANUAL_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(ClearGpsPreferences.KEY_MANUAL_LON, null)?.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return ManualAnchor(lat, lon)
    }

    fun save(context: Context, anchor: ManualAnchor?) {
        val p = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        if (anchor == null) {
            e.remove(ClearGpsPreferences.KEY_MANUAL_LAT).remove(ClearGpsPreferences.KEY_MANUAL_LON)
                .remove(ClearGpsPreferences.KEY_MANUAL_SET_AT)
        } else {
            require(anchor.latitude.isFinite() && anchor.latitude in -90.0..90.0)
            require(anchor.longitude.isFinite() && anchor.longitude in -180.0..180.0)
            e.putString(ClearGpsPreferences.KEY_MANUAL_LAT, anchor.latitude.toString())
                .putString(ClearGpsPreferences.KEY_MANUAL_LON, anchor.longitude.toString())
                .putLong(ClearGpsPreferences.KEY_MANUAL_SET_AT, System.currentTimeMillis())
        }
        e.apply()
    }
}

/**
 * Radio scans, matched coordinates, point aliases and history stay in app-private SQLite.
 * This database class has no HTTP client and never sends wireless identifiers to a service.
 */
class LocalRadioDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE radio_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                radio_type TEXT NOT NULL, identifier TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '', ssid TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
                latitude REAL, longitude REAL, accuracy REAL,
                last_rssi INTEGER NOT NULL DEFAULT -100, frequency INTEGER NOT NULL DEFAULT 0,
                first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                sightings INTEGER NOT NULL DEFAULT 1, position_source TEXT NOT NULL DEFAULT 'unknown',
                UNIQUE(radio_type, identifier)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX radio_points_last_seen ON radio_points(last_seen DESC)")
        db.execSQL("""
            CREATE TABLE radio_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                radio_type TEXT NOT NULL, identifier TEXT NOT NULL,
                latitude REAL, longitude REAL, accuracy REAL,
                rssi INTEGER NOT NULL, frequency INTEGER NOT NULL,
                observed_at INTEGER NOT NULL, position_source TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX radio_obs_time ON radio_observations(observed_at DESC)")
        db.execSQL("""
            CREATE TABLE scan_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scanned_at INTEGER NOT NULL, wifi_count INTEGER NOT NULL, cell_count INTEGER NOT NULL,
                ble_count INTEGER NOT NULL, matched_count INTEGER NOT NULL,
                latitude REAL, longitude REAL, accuracy REAL, mode TEXT NOT NULL, recorded INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX scan_history_time ON scan_history(scanned_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migration preserves points, observations and scan history.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE radio_points ADD COLUMN ssid TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE radio_points SET ssid = label WHERE radio_type = 'wifi' AND position_source = 'wigle-import' AND ssid = ''")
        }
    }

    fun recordScan(
        signals: List<RadioSignal>, reference: RadioReference?, estimate: RadioEstimate?,
        mode: String, wifiCount: Int, cellCount: Int, bleCount: Int, shouldRecord: Boolean
    ): Int {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val unique = signals.filter { it.identifier.isNotBlank() && it.type in VALID_TYPES }
            .distinctBy { it.type + "|" + it.identifier }
        var recorded = 0
        db.beginTransaction()
        try {
            if (shouldRecord) {
                unique.forEach { signal ->
                    val old = findPoint(db, signal.type, signal.identifier)
                    val values = ContentValues().apply {
                        put("last_rssi", signal.rssi)
                        put("frequency", signal.frequency)
                        if (signal.ssid.isNotBlank()) put("ssid", signal.ssid.take(100))
                        put("last_seen", now)
                        put("sightings", (old?.sightings ?: 0) + 1)
                    }
                    if (old == null) {
                        values.put("radio_type", signal.type)
                        values.put("identifier", signal.identifier)
                        values.put("first_seen", now)
                        values.put("position_source", reference?.source ?: "unlocated")
                        if (reference != null && validReference(reference)) {
                            values.put("latitude", reference.latitude)
                            values.put("longitude", reference.longitude)
                            values.put("accuracy", reference.accuracyMeters)
                        }
                        db.insertOrThrow("radio_points", null, values)
                    } else {
                        if (reference != null && validReference(reference) && shouldReplacePosition(old, reference, signal)) {
                            values.put("latitude", reference.latitude)
                            values.put("longitude", reference.longitude)
                            values.put("accuracy", reference.accuracyMeters)
                            values.put("position_source", reference.source)
                        }
                        db.update("radio_points", values, "id = ?", arrayOf(old.id.toString()))
                    }
                    val obs = ContentValues().apply {
                        put("radio_type", signal.type)
                        put("identifier", signal.identifier)
                        put("rssi", signal.rssi)
                        put("frequency", signal.frequency)
                        put("observed_at", now)
                        put("position_source", reference?.source ?: "unlocated")
                        if (reference != null && validReference(reference)) {
                            put("latitude", reference.latitude)
                            put("longitude", reference.longitude)
                            put("accuracy", reference.accuracyMeters)
                        }
                    }
                    db.insert("radio_observations", null, obs)
                    recorded++
                }
                // Bound detailed history to avoid unbounded storage use.
                db.execSQL("DELETE FROM radio_observations WHERE id NOT IN (SELECT id FROM radio_observations ORDER BY observed_at DESC LIMIT 20000)")
            }

            val history = ContentValues().apply {
                put("scanned_at", now); put("wifi_count", wifiCount); put("cell_count", cellCount)
                put("ble_count", bleCount); put("matched_count", estimate?.matchedCount ?: 0)
                put("mode", mode); put("recorded", if (shouldRecord) 1 else 0)
                if (estimate != null) {
                    put("latitude", estimate.latitude); put("longitude", estimate.longitude); put("accuracy", estimate.accuracyMeters)
                }
            }
            db.insert("scan_history", null, history)
            db.execSQL("DELETE FROM scan_history WHERE id NOT IN (SELECT id FROM scan_history ORDER BY scanned_at DESC LIMIT 500)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return recorded
    }

    fun estimatePosition(
        signals: List<RadioSignal>, useWifi: Boolean, useCells: Boolean, useBle: Boolean
    ): RadioEstimate? {
        val enabledTypes = buildSet {
            if (useWifi) add("wifi")
            if (useCells) add("cell")
            if (useBle) add("ble")
        }
        if (enabledTypes.isEmpty()) return null
        val matches = ArrayList<Pair<RadioSignal, RadioPointRecord>>()
        val db = readableDatabase
        signals.asSequence().filter { it.type in enabledTypes }
            .distinctBy { it.type + "|" + it.identifier }
            .forEach { signal ->
                val point = findPoint(db, signal.type, signal.identifier)
                if (point != null && point.latitude != null && point.longitude != null &&
                    point.latitude.isFinite() && point.longitude.isFinite() &&
                    (System.currentTimeMillis() - point.lastSeen) in 0..MAX_POINT_AGE_MS) {
                    matches.add(signal to point)
                }
            }
        if (matches.isEmpty()) return null

        val weighted = matches.map { (signal, point) ->
            val acc = (point.accuracyMeters ?: DEFAULT_IMPORTED_ACCURACY_METERS).coerceIn(20.0, 50_000.0)
            val signalWeight = 10.0.pow(((signal.rssi.coerceIn(-110, -25) + 80) / 20.0)).coerceIn(0.02, 100.0)
            Triple(point, signalWeight / (acc * acc), acc)
        }
        val totalWeight = weighted.sumOf { it.second }.coerceAtLeast(1e-18)
        val lat = weighted.sumOf { it.first.latitude!! * it.second } / totalWeight
        val lon = weighted.sumOf { it.first.longitude!! * it.second } / totalWeight
        val rms = sqrt(weighted.sumOf {
            val distance = haversineMeters(lat, lon, it.first.latitude!!, it.first.longitude!!)
            it.second * distance * distance
        } / totalWeight)
        val observationError = sqrt(weighted.sumOf { it.second * it.third * it.third } / totalWeight)
        val meanRssi = matches.map { it.first.rssi }.average()
        val sparsePenalty = max(0, 3 - matches.size) * 120.0
        val weakPenalty = ((-65.0 - meanRssi).coerceAtLeast(0.0) * 4.0)
        return RadioEstimate(
            lat.coerceIn(-90.0, 90.0), lon.coerceIn(-180.0, 180.0),
            (rms + observationError + sparsePenalty + weakPenalty).coerceIn(80.0, 50_000.0),
            System.currentTimeMillis(), hashSignals(matches.map { it.first }), true, matches.size
        )
    }

    fun listPoints(limit: Int = 2_000): List<RadioPointRecord> {
        val result = ArrayList<RadioPointRecord>()
        readableDatabase.rawQuery("SELECT * FROM radio_points ORDER BY last_seen DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 5_000).toString())).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toRadioPoint())
        }
        return result
    }

    fun findPoint(id: Long): RadioPointRecord? = readableDatabase.rawQuery(
        "SELECT * FROM radio_points WHERE id = ?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.toRadioPoint() else null }

    /** Fetch geolocated points only; unlocated rows cannot consume the map marker limit. */
    fun listMapPoints(limit: Int = 1_500): List<RadioPointRecord> {
        val result = ArrayList<RadioPointRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM radio_points WHERE latitude IS NOT NULL AND longitude IS NOT NULL " +
                "AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 " +
                "ORDER BY last_seen DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 4_000).toString())
        ).use { cursor -> while (cursor.moveToNext()) result.add(cursor.toRadioPoint()) }
        return result
    }

    /** Local search across SSID, BSSID/MAC, labels and notes. */
    fun searchPoints(query: String, limit: Int = 500): List<RadioPointRecord> {
        val term = query.trim()
        if (term.isBlank()) return listPoints(limit.coerceIn(1, 5_000))
        val pattern = "%" + term.take(120) + "%"
        val result = ArrayList<RadioPointRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM radio_points WHERE label LIKE ? OR ssid LIKE ? OR identifier LIKE ? OR notes LIKE ? " +
                "ORDER BY last_seen DESC LIMIT ?",
            arrayOf(pattern, pattern, pattern, pattern, limit.coerceIn(1, 5_000).toString())
        ).use { cursor -> while (cursor.moveToNext()) result.add(cursor.toRadioPoint()) }
        return result
    }

    fun pointCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM radio_points", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun observationCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM radio_observations", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun listHistory(limit: Int = 200): List<RadioScanHistoryItem> {
        val result = ArrayList<RadioScanHistoryItem>()
        readableDatabase.rawQuery("SELECT * FROM scan_history ORDER BY scanned_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toHistory())
        }
        return result
    }

    fun updatePoint(
        id: Long, label: String, ssid: String, notes: String,
        latitude: Double?, longitude: Double?, accuracy: Double?
    ): Boolean {
        if ((latitude == null) != (longitude == null)) return false
        if (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0)) return false
        if (accuracy != null && (!accuracy.isFinite() || accuracy !in 1.0..100_000.0)) return false
        val values = ContentValues().apply {
            put("label", label.trim().take(100)); put("ssid", ssid.trim().take(100))
            put("notes", notes.trim().take(1_000))
            if (latitude == null || longitude == null) {
                putNull("latitude"); putNull("longitude"); putNull("accuracy")
                put("position_source", "unlocated")
            } else {
                put("latitude", latitude); put("longitude", longitude)
                put("accuracy", (accuracy ?: DEFAULT_IMPORTED_ACCURACY_METERS).coerceIn(1.0, 100_000.0))
                put("position_source", "manual-edit")
            }
        }
        return writableDatabase.update("radio_points", values, "id = ?", arrayOf(id.toString())) > 0
    }

    fun deletePoint(id: Long): Boolean = writableDatabase.delete("radio_points", "id = ?", arrayOf(id.toString())) > 0

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("radio_observations", null, null)
            db.delete("radio_points", null, null)
            db.delete("scan_history", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun exportWigleCsv(): String {
        val rows = listPoints(5_000)
        val out = StringBuilder()
        out.append("WigleWifi-1.6,appRelease=Clear GPS 0.3,model=Android,device=Clear GPS,brand=Android\n")
        out.append("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type\n")
        rows.forEach { p ->
            val type = when (p.type) { "ble" -> "BTLE"; "cell" -> "BTS"; else -> "WIFI" }
            val values = listOf(
                p.identifier, p.ssid.ifBlank { p.label }, "", UTC_FORMAT.format(Date(p.firstSeen)),
                frequencyToChannel(p.frequency).toString(), p.frequency.toString(), p.rssi.toString(),
                p.latitude?.toString().orEmpty(), p.longitude?.toString().orEmpty(), "",
                p.accuracyMeters?.toString().orEmpty(), "", "", type
            )
            out.append(values.joinToString(",") { csvEscape(it) }).append('\n')
        }
        return out.toString()
    }

    fun importWigleCsv(input: InputStream): Int {
        val raw = input.use { readBounded(it, MAX_IMPORT_BYTES) }
        val decoded = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { readBounded(it, MAX_IMPORT_BYTES) }
        } else raw
        val rows = parseCsv(String(decoded, Charsets.UTF_8).removePrefix("\uFEFF"))
        val headerIndex = rows.indexOfFirst { row ->
            val header = row.map { it.trim().removePrefix("\uFEFF").uppercase(Locale.ROOT) }
            "MAC" in header && "CURRENTLATITUDE" in header && "CURRENTLONGITUDE" in header
        }
        if (headerIndex < 0) throw IllegalArgumentException("Не найден формат WiGLE CSV: нужны столбцы MAC, CurrentLatitude и CurrentLongitude.")
        val headers = rows[headerIndex].mapIndexed { i, value ->
            value.trim().removePrefix("\uFEFF").uppercase(Locale.ROOT) to i
        }.toMap()
        fun field(row: List<String>, key: String) = headers[key]?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()

        var imported = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.drop(headerIndex + 1).forEach { row ->
                val identifier = field(row, "MAC")
                if (identifier.isBlank()) return@forEach
                val lat = field(row, "CURRENTLATITUDE").toDoubleOrNull()
                val lon = field(row, "CURRENTLONGITUDE").toDoubleOrNull()
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
                val typeHint = (field(row, "TYPE") + " " + field(row, "AUTHMODE")).uppercase(Locale.ROOT)
                val type = when {
                    typeHint.contains("BTLE") || typeHint.contains("BLUETOOTH") -> "ble"
                    typeHint.contains("BTS") || typeHint.contains("CELL") || typeHint.contains("GSM") -> "cell"
                    else -> "wifi"
                }
                val rssi = field(row, "RSSI").toIntOrNull()?.coerceIn(-120, 0) ?: -100
                val frequency = field(row, "FREQUENCY").toIntOrNull()?.coerceAtLeast(0) ?: 0
                val accuracy = field(row, "ACCURACYMETERS").toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(100_000.0) ?: DEFAULT_IMPORTED_ACCURACY_METERS
                val label = field(row, "SSID").take(100)
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("radio_type", type); put("identifier", identifier); put("label", label); put("ssid", label)
                    put("latitude", lat); put("longitude", lon); put("accuracy", accuracy)
                    put("last_rssi", rssi); put("frequency", frequency)
                    put("first_seen", now); put("last_seen", now); put("sightings", 1)
                    put("position_source", "wigle-import")
                }
                val inserted = db.insertWithOnConflict("radio_points", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) {
                    val old = findPoint(db, type, identifier)
                    val update = ContentValues().apply {
                        put("last_rssi", rssi); put("frequency", frequency); put("last_seen", now)
                        put("sightings", (old?.sightings ?: 0) + 1)
                        if (old?.latitude == null || old.longitude == null) {
                            put("latitude", lat); put("longitude", lon); put("accuracy", accuracy)
                            put("position_source", "wigle-import")
                        }
                        if (old != null && old.ssid.isBlank() && label.isNotBlank()) put("ssid", label)
                        if (old != null && old.label.isBlank() && label.isNotBlank()) put("label", label)
                    }
                    db.update("radio_points", update, "radio_type = ? AND identifier = ?", arrayOf(type, identifier))
                }
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    private fun findPoint(db: SQLiteDatabase, type: String, identifier: String): RadioPointRecord? =
        db.rawQuery("SELECT * FROM radio_points WHERE radio_type = ? AND identifier = ?",
            arrayOf(type, identifier)).use { if (it.moveToFirst()) it.toRadioPoint() else null }

    private fun shouldReplacePosition(old: RadioPointRecord, reference: RadioReference, signal: RadioSignal): Boolean {
        val accuracy = old.accuracyMeters
        if (old.latitude == null || old.longitude == null || accuracy == null) return true
        if (reference.accuracyMeters < accuracy * 0.8) return true
        return signal.rssi >= old.rssi + 8 && reference.accuracyMeters <= max(accuracy * 1.25, 150.0)
    }

    private fun validReference(r: RadioReference) =
        r.latitude.isFinite() && r.latitude in -90.0..90.0 &&
            r.longitude.isFinite() && r.longitude in -180.0..180.0 &&
            r.accuracyMeters.isFinite() && r.accuracyMeters in 1.0..100_000.0

    private fun Cursor.toRadioPoint() = RadioPointRecord(
        getLong(getColumnIndexOrThrow("id")),
        getString(getColumnIndexOrThrow("radio_type")),
        getString(getColumnIndexOrThrow("identifier")),
        getString(getColumnIndexOrThrow("label")).orEmpty(),
        getString(getColumnIndexOrThrow("ssid")).orEmpty(),
        getString(getColumnIndexOrThrow("notes")).orEmpty(),
        if (isNull(getColumnIndexOrThrow("latitude"))) null else getDouble(getColumnIndexOrThrow("latitude")),
        if (isNull(getColumnIndexOrThrow("longitude"))) null else getDouble(getColumnIndexOrThrow("longitude")),
        if (isNull(getColumnIndexOrThrow("accuracy"))) null else getDouble(getColumnIndexOrThrow("accuracy")),
        getInt(getColumnIndexOrThrow("last_rssi")),
        getInt(getColumnIndexOrThrow("frequency")),
        getLong(getColumnIndexOrThrow("first_seen")),
        getLong(getColumnIndexOrThrow("last_seen")),
        getInt(getColumnIndexOrThrow("sightings")),
        getString(getColumnIndexOrThrow("position_source")).orEmpty()
    )

    private fun Cursor.toHistory() = RadioScanHistoryItem(
        getLong(getColumnIndexOrThrow("id")),
        getLong(getColumnIndexOrThrow("scanned_at")),
        getInt(getColumnIndexOrThrow("wifi_count")),
        getInt(getColumnIndexOrThrow("cell_count")),
        getInt(getColumnIndexOrThrow("ble_count")),
        getInt(getColumnIndexOrThrow("matched_count")),
        if (isNull(getColumnIndexOrThrow("latitude"))) null else getDouble(getColumnIndexOrThrow("latitude")),
        if (isNull(getColumnIndexOrThrow("longitude"))) null else getDouble(getColumnIndexOrThrow("longitude")),
        if (isNull(getColumnIndexOrThrow("accuracy"))) null else getDouble(getColumnIndexOrThrow("accuracy")),
        getString(getColumnIndexOrThrow("mode")).orEmpty(),
        getInt(getColumnIndexOrThrow("recorded")) != 0
    )

    companion object {
        private const val DB_NAME = "clear_gps_radio.sqlite"
        private const val DB_VERSION = 2
        private const val DEFAULT_IMPORTED_ACCURACY_METERS = 250.0
        private const val MAX_POINT_AGE_MS = 365L * 24 * 60 * 60 * 1_000L
        private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
        private val VALID_TYPES = setOf("wifi", "ble", "cell")
        private val UTC_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IllegalArgumentException("Файл больше 32 МБ. Разбей WiGLE-экспорт на части и импортируй отдельно.")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }

        private fun csvEscape(value: String): String {
            if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
            return "\"" + value.replace("\"", "\"\"") + "\""
        }

        private fun parseCsv(input: String): List<List<String>> {
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                when {
                    quoted && ch == '"' && i + 1 < input.length && input[i + 1] == '"' -> { field.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == ',' && !quoted -> { row.add(field.toString()); field.setLength(0) }
                    (ch == '\n' || ch == '\r') && !quoted -> {
                        if (ch == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                        row.add(field.toString()); field.setLength(0)
                        if (row.any { it.isNotBlank() }) rows.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(ch)
                }
                i++
            }
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
            return rows
        }

        private fun hashSignals(signals: List<RadioSignal>): String {
            val input = signals.sortedWith(compareBy<RadioSignal> { it.type }.thenBy { it.identifier })
                .joinToString("|") { "${it.type}:${it.identifier}" }
            return java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }

        private fun frequencyToChannel(frequency: Int): Int = when {
            frequency in 2412..2472 -> (frequency - 2407) / 5
            frequency == 2484 -> 14
            frequency in 5000..5895 -> (frequency - 5000) / 5
            frequency in 5955..7115 -> (frequency - 5950) / 5
            else -> 0
        }

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 6_371_000.0 * 2 * kotlin.math.asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
