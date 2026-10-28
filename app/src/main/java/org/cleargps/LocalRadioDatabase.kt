package org.cleargps

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RadioSignal(
    val type: String, val identifier: String, val rssi: Int,
    val frequency: Int = 0, val ssid: String = "",
    /** Monotonic measurement timestamp when exposed by Android; never persisted. */
    val observedAtElapsedRealtimeNanos: Long? = null
)

data class RadioPointRecord(
    val id: Long, val type: String, val identifier: String, val label: String, val ssid: String, val notes: String,
    val latitude: Double?, val longitude: Double?, val accuracyMeters: Double?,
    val rssi: Int, val peakRssi: Int, val frequency: Int, val firstSeen: Long, val lastSeen: Long,
    val sightings: Int, val positionSource: String, val sourceId: Long? = null,
    val positionObservedAt: Long? = null
)

data class RadioScanHistoryItem(
    val id: Long, val time: Long, val wifiCount: Int, val cellCount: Int, val bleCount: Int,
    val matchedCount: Int, val latitude: Double?, val longitude: Double?,
    val accuracyMeters: Double?, val mode: String, val recorded: Boolean
)

data class RadioImportResult(
    val observationsImported: Int,
    val uniqueRadios: Int,
    val newRadios: Int,
    val format: String,
    val sourceId: Long? = null
)

data class RadioEstimate(
    val latitude: Double, val longitude: Double, val accuracyMeters: Double, val updatedAt: Long,
    val fingerprint: String, val fallbackUsed: Boolean = false, val matchedCount: Int = 0,
    /** Radio types that actually contributed matched identifiers to this estimate. */
    val matchedRadioTypes: Set<String> = emptySet(),
    /** Ephemeral type-qualified keys (radioType|identifier) matched by this scan; not persisted. */
    val matchedRadioIdentifiers: Set<String> = emptySet(),
    /**
     * Monotonic timestamp of the oldest matched live radio observation, when available.
     * This is process/boot-local evidence only: it is never persisted or reconstructed from wall time.
     */
    val measurementElapsedRealtimeNanos: Long? = null
)

data class RadioPointObservation(
    val id: Long,
    val observedAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val rssi: Int,
    val frequency: Int,
    val positionSource: String,
    val sourceId: Long? = null,
    val sourceName: String? = null
)

data class RadioSourceRecord(
    val id: Long, val kind: String, val name: String,
    val createdAt: Long, val lastSeen: Long, val enabled: Boolean,
    val trustWeight: Double, val observationCount: Int, val radioCount: Int
)

data class RadioSourceDayRecord(
    val sourceId: Long,
    val dayKey: String,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val observationCount: Int,
    val radioCount: Int,
    val wifiObservations: Int,
    val cellObservations: Int,
    val bleObservations: Int
)

data class RadioSourceDayDeleteResult(
    val observationsDeleted: Int,
    val scanHistoryDeleted: Int,
    val radiosRecalculated: Int,
    val undoToken: String? = null
)

data class RadioSourceSegmentRecord(
    val sourceId: Long,
    val dayKey: String,
    val radioType: String,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val observationCount: Int,
    val radioCount: Int,
    val minRssi: Int,
    val maxRssi: Int,
    val enabled: Boolean,
    val trustWeight: Double
)

data class RadioSourceSegmentDeleteResult(
    val observationsDeleted: Int,
    val radiosRecalculated: Int,
    val undoToken: String? = null
)

data class RadioPointCenter(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val sampleCount: Int
)

/**
 * Estimates the centre of recorded observation locations, not the physical transmitter position.
 * More accurate fixes contribute more weight; spherical averaging also handles the date line.
 */
fun estimateObservationCenter(observations: List<RadioPointObservation>): RadioPointCenter? {
    val located = observations.mapNotNull { observation ->
        val lat = observation.latitude
        val lon = observation.longitude
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        val accuracy = observation.accuracyMeters
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(10.0, 100_000.0) ?: 250.0
        Triple(observation, accuracy, 1.0 / (accuracy * accuracy))
    }
    if (located.isEmpty()) return null

    var x = 0.0
    var y = 0.0
    var z = 0.0
    var totalWeight = 0.0
    located.forEach { (observation, _, weight) ->
        val lat = Math.toRadians(observation.latitude!!)
        val lon = Math.toRadians(observation.longitude!!)
        x += kotlin.math.cos(lat) * kotlin.math.cos(lon) * weight
        y += kotlin.math.cos(lat) * kotlin.math.sin(lon) * weight
        z += kotlin.math.sin(lat) * weight
        totalWeight += weight
    }
    if (totalWeight <= 0.0) return null
    val horizontal = kotlin.math.sqrt(x * x + y * y)
    if (!horizontal.isFinite() || (horizontal < 1e-12 && abs(z) < 1e-12)) return null
    val latitude = Math.toDegrees(kotlin.math.atan2(z, horizontal)).coerceIn(-90.0, 90.0)
    val longitude = Math.toDegrees(kotlin.math.atan2(y, x))
    val lat0 = Math.toRadians(latitude)
    val lon0 = Math.toRadians(longitude)
    val uncertaintySquared = located.sumOf { (observation, accuracy, weight) ->
        val lat = Math.toRadians(observation.latitude!!)
        val lon = Math.toRadians(observation.longitude!!)
        val dLat = lat - lat0
        val dLon = lon - lon0
        val a = (kotlin.math.sin(dLat / 2).pow(2) +
            kotlin.math.cos(lat0) * kotlin.math.cos(lat) * kotlin.math.sin(dLon / 2).pow(2)).coerceIn(0.0, 1.0)
        val distance = 2.0 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a))
        weight * (distance * distance + accuracy * accuracy)
    } / totalWeight
    return RadioPointCenter(
        latitude, longitude,
        kotlin.math.sqrt(uncertaintySquared).coerceIn(10.0, 100_000.0),
        located.size
    )
}

data class RadioLookupResult(
    val estimate: RadioEstimate?, val wifiCount: Int, val cellCount: Int, val beaconCount: Int,
    val fromCache: Boolean, val message: String, val matchedCount: Int = 0, val recordedCount: Int = 0,
    /** Signals present in the local DB, including records without usable coordinates. */
    val knownRadioCount: Int = 0,
    /** Known signals with usable coordinates and a non-synthetic position source. */
    val coordinateRadioCount: Int = 0
)

data class RadioMatchCounts(val knownRadios: Int, val usableCoordinateRadios: Int)

/**
 * Normalize MAC-style identifiers from scanners and third-party CSV/SQLite exports.
 * This keeps colon, hyphen, dotted and compact MAC formats from becoming different radios.
 * Non-MAC identifiers (including cell keys) are preserved apart from case/whitespace.
 */
internal fun canonicalRadioIdentifier(value: String): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
    val compact = normalized.filterNot { it == ':' || it == '-' || it == '.' }
    val hexOnly = normalized.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '-' || it == '.' }
    return if (hexOnly && compact.length == 12 && compact.all { it in '0'..'9' || it in 'a'..'f' }) {
        compact.chunked(2).joinToString(":")
    } else {
        normalized
    }
}

private fun compactRadioMacIdentifier(value: String): String? {
    val canonical = canonicalRadioIdentifier(value)
    if (!canonical.matches(Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))) return null
    return canonical.replace(":", "")
}

data class ManualAnchor(val latitude: Double, val longitude: Double)
data class RadioReference(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val source: String)

/**
 * Signal strength alone cannot triangulate a transmitter. This only promotes a recorded sample
 * as the approximate strongest-reception location when its position fix is also reasonably good.
 */
internal fun isBetterSignalSample(rssi: Int, peakRssi: Int, accuracyMeters: Double): Boolean =
    rssi >= peakRssi + 3 && accuracyMeters.isFinite() && accuracyMeters in 1.0..50.0

object ClearGpsPreferences {
    const val PREFS = "clear_gps"
    const val KEY_SCAN_INTERVAL_SECONDS = "scan_interval_seconds"
    const val KEY_ADAPTIVE_SCAN = "adaptive_scan_enabled"
    const val KEY_BATTERY_PROFILE = "battery_profile"
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
    const val KEY_LAST_SCAN_MESSAGE = "radio_last_scan_message"
    const val KEY_LAST_SCAN_HAS_ESTIMATE = "radio_last_scan_has_estimate"
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
    const val KEY_DATABASE_VIEW_SOURCE = "database_view_source"
    const val KEY_BEACONDB_LOOKUP_ENABLED = "beacondb_cell_lookup_enabled"
    const val KEY_RADIO_OBSERVATION_LIMIT = "radio_observation_retention_limit"
    const val KEY_WIFI_SSID_BLACKLIST = "wifi_ssid_blacklist"
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
 * One geolocated radio observation, grouped with other observations captured by the same scan.
 * A recorded scan is a fingerprint of the radio environment at one known place.
 */
internal data class RadioFingerprintSample(
    val radioType: String,
    val identifier: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val rssi: Int,
    val observedAt: Long,
    val positionSource: String
)

internal data class RadioFingerprintLookup(val estimate: RadioEstimate?, val ambiguous: Boolean = false)

private data class WeightedRadioLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val weight: Double
)

private fun radioDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = (sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2))
        .coerceIn(0.0, 1.0)
    return 6_371_000.0 * 2.0 * kotlin.math.asin(sqrt(a))
}

private fun weightedRadioCenter(points: List<WeightedRadioLocation>): Pair<Double, Double>? {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var total = 0.0
    points.forEach { point ->
        if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0 ||
            !point.weight.isFinite() || point.weight <= 0.0) return@forEach
        val lat = Math.toRadians(point.latitude)
        val lon = Math.toRadians(point.longitude)
        x += cos(lat) * cos(lon) * point.weight
        y += cos(lat) * sin(lon) * point.weight
        z += sin(lat) * point.weight
        total += point.weight
    }
    if (total <= 0.0) return null
    val horizontal = sqrt(x * x + y * y)
    if (!horizontal.isFinite() || (horizontal < 1e-12 && kotlin.math.abs(z) < 1e-12)) return null
    return Math.toDegrees(atan2(z, horizontal)).coerceIn(-90.0, 90.0) to
        Math.toDegrees(atan2(y, x)).coerceIn(-180.0, 180.0)
}

private fun radioSignalHash(signals: List<RadioSignal>): String {
    val input = signals.distinctBy { it.type + "|" + it.identifier }
        .sortedWith(compareBy<RadioSignal> { it.type }.thenBy { it.identifier })
        .joinToString("|") { it.type + ":" + it.identifier }
    return java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

private fun radioTypeWeight(type: String): Double = when (type) {
    "wifi" -> 1.0
    "cell" -> 0.62
    "ble" -> 0.72
    else -> 0.0
}

private const val RADIO_POINT_DAY_MS = 24L * 60L * 60L * 1_000L
private const val MAX_WIFI_OR_CELL_POINT_AGE_MS = 5L * 365L * RADIO_POINT_DAY_MS
private const val MAX_BLE_POINT_AGE_MS = 180L * RADIO_POINT_DAY_MS

internal fun radioAnchorMaxAgeMillis(type: String): Long = when (type) {
    "wifi", "cell" -> MAX_WIFI_OR_CELL_POINT_AGE_MS
    "ble" -> MAX_BLE_POINT_AGE_MS
    else -> 0L
}

/**
 * Old imported/known coordinates remain usable for a bounded time, but their age reduces
 * their weight and inflates uncertainty. BLE identifiers are less stable, so they expire sooner.
 */
internal fun radioAnchorAgePenaltyMeters(type: String, ageMillis: Long): Double {
    val ageDays = ageMillis.coerceAtLeast(0L) / RADIO_POINT_DAY_MS.toDouble()
    return when (type) {
        "wifi" -> (ageDays * 0.35).coerceAtMost(750.0)
        "cell" -> (ageDays * 0.18).coerceAtMost(500.0)
        "ble" -> (ageDays * 1.5).coerceAtMost(300.0)
        else -> 50_000.0
    }
}

private fun fingerprintAccuracy(sample: RadioFingerprintSample): Double =
    sample.accuracyMeters?.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(10.0, 50_000.0) ?: 250.0

/**
 * Find a previously recorded scan with a similar local radio fingerprint.
 * This tends to be stronger evidence than averaging the coordinates of transmitters,
 * because every sample records where the phone was when those signals were observed.
 */
internal fun lookupRadioFingerprintPosition(
    signals: List<RadioSignal>,
    history: List<RadioFingerprintSample>,
    now: Long = System.currentTimeMillis()
): RadioFingerprintLookup {
    val currentSignals = signals.asSequence()
        .map { it.copy(identifier = canonicalRadioIdentifier(it.identifier)) }
        .filter { it.radioTypeIsUsable() && it.identifier.isNotBlank() && it.rssi in -115..0 }
        .distinctBy { it.type + "|" + it.identifier }
        .groupBy { it.type }
        .flatMap { (type, values) ->
            val limit = when (type) {
                "wifi" -> 32
                "cell" -> 16
                else -> 32
            }
            values.sortedByDescending { it.rssi }.take(limit)
        }
    val currentByType = currentSignals.groupBy { it.type }
    if (currentSignals.size < 2) return RadioFingerprintLookup(null)
    val currentByKey = currentSignals.associateBy { it.type + "|" + it.identifier }
    val activeTypeWeight = currentByType.keys.sumOf(::radioTypeWeight).coerceAtLeast(1e-9)
    val historyMaxAge = 90L * 24 * 60 * 60 * 1_000L

    data class Candidate(
        val observedAt: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val score: Double,
        val matchedSignals: List<RadioSignal>
    )

    val candidates = history.asSequence()
        .map { sample -> sample.copy(identifier = canonicalRadioIdentifier(sample.identifier)) }
        .filter { sample ->
            val age = now - sample.observedAt
            age in 0..historyMaxAge &&
                sample.radioType in setOf("wifi", "cell", "ble") &&
                sample.identifier.isNotBlank() &&
                sample.latitude != null && sample.longitude != null &&
                sample.latitude.isFinite() && sample.latitude in -90.0..90.0 &&
                sample.longitude.isFinite() && sample.longitude in -180.0..180.0 &&
                !sample.positionSource.equals("unlocated", ignoreCase = true) &&
                !sample.positionSource.equals("local-radio-estimate", ignoreCase = true) &&
                !sample.positionSource.equals("radio-estimate", ignoreCase = true)
        }
        .groupBy { it.observedAt }
        .mapNotNull { (observedAt, rows) ->
            val historical = rows.distinctBy { it.radioType + "|" + it.identifier }
            val historicalByType = historical.groupBy { it.radioType }
            val overlaps = historical.mapNotNull { sample ->
                currentByKey[sample.radioType + "|" + sample.identifier]?.let { it to sample }
            }
            if (overlaps.size < 2) return@mapNotNull null

            var weightedSimilarity = 0.0
            currentByType.forEach { (type, currentOfType) ->
                val oldOfType = historicalByType[type].orEmpty()
                val overlapOfType = overlaps.filter { it.first.type == type }
                val coverage = if (oldOfType.isEmpty() || currentOfType.isEmpty()) 0.0 else {
                    (overlapOfType.size / sqrt(currentOfType.size.toDouble() * oldOfType.size.toDouble()))
                        .coerceIn(0.0, 1.0)
                }
                val rssiSimilarity = overlapOfType.map { (current, old) ->
                    exp(-abs(current.rssi - old.rssi).toDouble() / 18.0)
                }.average().takeIf { overlapOfType.isNotEmpty() } ?: 0.0
                weightedSimilarity += radioTypeWeight(type) * coverage * (0.55 + 0.45 * rssiSimilarity)
            }
            val score = weightedSimilarity / activeTypeWeight
            if (!score.isFinite() || score < 0.12) return@mapNotNull null

            // A scan's rows normally share one fix. Use its best reported accuracy if a
            // timestamp collision or a partial historical record gives slightly different fixes.
            val locationSample = historical.asSequence()
                .filter { it.latitude != null && it.longitude != null }
                .minByOrNull(::fingerprintAccuracy) ?: return@mapNotNull null
            Candidate(
                observedAt = observedAt,
                latitude = locationSample.latitude!!,
                longitude = locationSample.longitude!!,
                accuracyMeters = fingerprintAccuracy(locationSample),
                score = score,
                matchedSignals = overlaps.map { it.first }.distinctBy { it.type + "|" + it.identifier }
            )
        }
        .sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.matchedSignals.size }
                .thenByDescending { it.observedAt }
        )
    if (candidates.isEmpty()) return RadioFingerprintLookup(null)
    val best = candidates.first()

    fun clusterRadius(a: Candidate, b: Candidate): Double =
        (2.0 * (a.accuracyMeters + b.accuracyMeters) + 50.0).coerceIn(100.0, 1_500.0)

    // Similar fingerprints can occur in multiple places (for example, apartment blocks
    // or cloned SSIDs). Keep the best-ranked local cluster instead of suppressing the fix;
    // enlarge its reported uncertainty to reflect the distance to competing candidates.
    val ambiguousCandidates = candidates.drop(1).filter { candidate ->
        candidate.score >= best.score * 0.88 &&
            radioDistanceMeters(best.latitude, best.longitude, candidate.latitude, candidate.longitude) >
                clusterRadius(best, candidate)
    }
    val ambiguous = ambiguousCandidates.isNotEmpty()

    val selected = candidates.asSequence()
        .filter { it.score >= maxOf(0.12, best.score * 0.60) }
        .filter {
            radioDistanceMeters(best.latitude, best.longitude, it.latitude, it.longitude) <= clusterRadius(best, it)
        }
        .take(12)
        .toList()
    val weightedLocations = selected.map { candidate ->
        val ageMs = now - candidate.observedAt
        val recency = exp(-ageMs.toDouble() / (45.0 * 24 * 60 * 60 * 1_000L)).coerceAtLeast(0.12)
        val accuracyFactor = (100.0 / candidate.accuracyMeters).pow(1.1).coerceIn(0.05, 8.0)
        val weight = candidate.score.pow(4.0) * recency * accuracyFactor
        WeightedRadioLocation(candidate.latitude, candidate.longitude, candidate.accuracyMeters, weight)
    }.filter { it.weight.isFinite() && it.weight > 0.0 }
    val center = weightedRadioCenter(weightedLocations) ?: return RadioFingerprintLookup(null)
    val totalWeight = weightedLocations.sumOf { it.weight }.coerceAtLeast(1e-12)
    val spreadSquared = weightedLocations.sumOf {
        it.weight * radioDistanceMeters(center.first, center.second, it.latitude, it.longitude).pow(2.0)
    } / totalWeight
    val fixErrorSquared = weightedLocations.sumOf { it.weight * it.accuracyMeters.pow(2.0) } / totalWeight
    val sparsePenalty = max(0, 3 - best.matchedSignals.size) * 180.0
    val matchPenalty = (1.0 - best.score).coerceIn(0.0, 1.0) * 650.0
    // An equally plausible match far away must be reflected in the uncertainty radius.
    // Keep the best candidate as the point estimate, but make the ambiguity visible on the map.
    val ambiguityPenalty = ambiguousCandidates.maxOfOrNull { candidate ->
        radioDistanceMeters(best.latitude, best.longitude, candidate.latitude, candidate.longitude) * 0.5
    } ?: 0.0
    val accuracy = (
        sqrt(spreadSquared + fixErrorSquared) + sparsePenalty + matchPenalty + ambiguityPenalty
    ).coerceIn(30.0, 50_000.0)

    return RadioFingerprintLookup(
        estimate = RadioEstimate(
            latitude = center.first,
            longitude = center.second,
            accuracyMeters = accuracy,
            updatedAt = now,
            fingerprint = radioSignalHash(best.matchedSignals),
            fallbackUsed = false,
            matchedCount = best.matchedSignals.size,
            matchedRadioTypes = best.matchedSignals.map { it.type }.toSet(),
            matchedRadioIdentifiers = best.matchedSignals.map {
                "${it.type}|${canonicalRadioIdentifier(it.identifier)}"
            }.toSet()
        ),
        ambiguous = ambiguous
    )
}

internal fun estimateRadioFingerprintPosition(
    signals: List<RadioSignal>,
    history: List<RadioFingerprintSample>,
    now: Long = System.currentTimeMillis()
): RadioEstimate? = lookupRadioFingerprintPosition(signals, history, now).estimate

private fun RadioSignal.radioTypeIsUsable(): Boolean = radioTypeWeight(type) > 0.0

private data class WeightedRadioAnchor(
    val signal: RadioSignal,
    val point: RadioPointRecord,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val weight: Double
)

/**
 * Conservative fallback used for imported/manual radio-point coordinates when no matching
 * recorded scan fingerprint exists. RSSI only nudges the weight; it is not treated as distance.
 */
internal fun estimateRadioAnchorPosition(
    matches: List<Pair<RadioSignal, RadioPointRecord>>,
    now: Long = System.currentTimeMillis()
): RadioEstimate? {
    val candidates = matches.asSequence()
        .distinctBy { it.first.type + "|" + it.first.identifier }
        .mapNotNull { (signal, point) ->
            val lat = point.latitude ?: return@mapNotNull null
            val lon = point.longitude ?: return@mapNotNull null
            val age = now - point.lastSeen
            if (signal.type !in setOf("wifi", "cell", "ble") ||
                !lat.isFinite() || lat !in -90.0..90.0 ||
                !lon.isFinite() || lon !in -180.0..180.0 ||
                age !in 0..radioAnchorMaxAgeMillis(signal.type) ||
                point.positionSource.equals("local-radio-estimate", ignoreCase = true) ||
                point.positionSource.equals("radio-estimate", ignoreCase = true)) return@mapNotNull null
            val baseAccuracy = (point.accuracyMeters ?: 250.0).takeIf { it.isFinite() && it > 0.0 }
                ?.coerceIn(20.0, 50_000.0) ?: 250.0
            val accuracy = (baseAccuracy + radioAnchorAgePenaltyMeters(signal.type, age))
                .coerceIn(20.0, 50_000.0)
            val accuracyFactor = (100.0 / accuracy).pow(0.8).coerceIn(0.12, 3.5)
            val rssiFactor = exp((signal.rssi.coerceIn(-110, -25) + 70.0) / 35.0).coerceIn(0.55, 1.8)
            val typeFactor = radioTypeWeight(signal.type)
            val sourceFactor = when (point.positionSource.lowercase(Locale.ROOT)) {
                "manual-edit" -> 1.2
                "wigle-import" -> 0.85
                "strongest-signal-sample" -> 0.95
                else -> 1.0
            }
            val weight = accuracyFactor * rssiFactor * typeFactor * sourceFactor
            WeightedRadioAnchor(signal, point, lat, lon, accuracy, weight)
        }.toList()
    if (candidates.isEmpty()) return null

    val typeCaps = mapOf("wifi" to 6.0, "cell" to 2.5, "ble" to 3.0)
    val rawTypeWeights = candidates.groupBy { it.signal.type }.mapValues { (_, group) ->
        group.sumOf { it.weight }
    }
    val normalized = candidates.map { candidate ->
        val totalForType = rawTypeWeights[candidate.signal.type] ?: candidate.weight
        val cap = typeCaps[candidate.signal.type] ?: 1.0
        candidate.copy(weight = candidate.weight * (cap / totalForType).coerceAtMost(1.0))
    }

    fun neighborhoodRadius(a: WeightedRadioAnchor, b: WeightedRadioAnchor): Double {
        val combinedAccuracy = a.accuracyMeters + b.accuracyMeters
        return when {
            a.signal.type == "cell" || b.signal.type == "cell" ->
                (combinedAccuracy * 2.0 + 400.0).coerceIn(1_500.0, 5_000.0)
            a.signal.type == "ble" && b.signal.type == "ble" ->
                (combinedAccuracy * 1.2 + 100.0).coerceIn(150.0, 450.0)
            a.signal.type == "ble" || b.signal.type == "ble" ->
                (combinedAccuracy * 1.5 + 100.0).coerceIn(250.0, 1_000.0)
            else -> (combinedAccuracy * 1.5 + 100.0).coerceIn(250.0, 1_500.0)
        }
    }

    val bestSeed = normalized.maxByOrNull { seed ->
        val neighbors = normalized.filter { candidate ->
            radioDistanceMeters(seed.latitude, seed.longitude, candidate.latitude, candidate.longitude) <=
                neighborhoodRadius(seed, candidate)
        }
        val support = neighbors.sumOf { candidate ->
            val distance = radioDistanceMeters(seed.latitude, seed.longitude, candidate.latitude, candidate.longitude)
            candidate.weight * (1.0 - 0.30 * distance / neighborhoodRadius(seed, candidate))
        }
        support + neighbors.size * 0.8
    } ?: return null
    val inliers = normalized.filter { candidate ->
        radioDistanceMeters(bestSeed.latitude, bestSeed.longitude, candidate.latitude, candidate.longitude) <=
            neighborhoodRadius(bestSeed, candidate)
    }
    if (normalized.size > 1 && inliers.size < 2) return null
    val allWeight = normalized.sumOf { it.weight }.coerceAtLeast(1e-12)
    val inlierWeight = inliers.sumOf { it.weight }
    if (normalized.size > 1 && inlierWeight / allWeight < 0.30) return null

    val weightedLocations = inliers.map {
        WeightedRadioLocation(it.latitude, it.longitude, it.accuracyMeters, it.weight)
    }
    val center = weightedRadioCenter(weightedLocations) ?: return null
    val totalWeight = weightedLocations.sumOf { it.weight }.coerceAtLeast(1e-12)
    val spreadSquared = weightedLocations.sumOf {
        it.weight * radioDistanceMeters(center.first, center.second, it.latitude, it.longitude).pow(2.0)
    } / totalWeight
    val fixErrorSquared = weightedLocations.sumOf { it.weight * it.accuracyMeters.pow(2.0) } / totalWeight
    val averageRssi = inliers.map { it.signal.rssi.toDouble() }.average()
    val sparsePenalty = max(0, 3 - inliers.size) * 180.0
    val weakPenalty = ((-75.0 - averageRssi).coerceAtLeast(0.0) * 7.0)
    val discardedPenalty = (normalized.size - inliers.size) * 100.0
    val accuracy = (sqrt(spreadSquared + fixErrorSquared) + sparsePenalty + weakPenalty + discardedPenalty)
        .coerceIn(80.0, 50_000.0)

    return RadioEstimate(
        latitude = center.first,
        longitude = center.second,
        accuracyMeters = accuracy,
        updatedAt = now,
        fingerprint = radioSignalHash(inliers.map { it.signal }),
        fallbackUsed = true,
        matchedCount = inliers.size,
        matchedRadioTypes = inliers.map { it.signal.type }.toSet(),
        matchedRadioIdentifiers = inliers.map {
            "${it.signal.type}|${canonicalRadioIdentifier(it.signal.identifier)}"
        }.toSet()
    )
}

/**
 * Radio scans, matched coordinates, point aliases and history stay in app-private SQLite.
 * This database class has no HTTP client and never sends wireless identifiers to a service.
 */
class LocalRadioDatabase(context: Context, databaseName: String = DB_NAME) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DB_VERSION) {
    private val appContext = context.applicationContext

    init {
        // WAL allows readers to keep working while the serialized scan writer commits updates.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) purgeRadioDeletionUndo(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createRadioSourcesTable(db)
        createRadioSourceSegmentSettingsTable(db)
        ensureLocalSource(db)
        db.execSQL("""
            CREATE TABLE radio_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                radio_type TEXT NOT NULL, identifier TEXT NOT NULL,
                label TEXT NOT NULL DEFAULT '', ssid TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
                latitude REAL, longitude REAL, accuracy REAL,
                last_rssi INTEGER NOT NULL DEFAULT -100, peak_rssi INTEGER NOT NULL DEFAULT -120, frequency INTEGER NOT NULL DEFAULT 0,
                first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                sightings INTEGER NOT NULL DEFAULT 1, position_source TEXT NOT NULL DEFAULT 'unknown',
                source_id INTEGER REFERENCES radio_sources(id) ON DELETE SET NULL,
                position_observed_at INTEGER,
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
                observed_at INTEGER NOT NULL, position_source TEXT NOT NULL,
                source_id INTEGER NOT NULL REFERENCES radio_sources(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX radio_obs_time ON radio_observations(observed_at DESC)")
        db.execSQL("CREATE INDEX radio_obs_source_time ON radio_observations(source_id, observed_at DESC)")
        db.execSQL("CREATE INDEX radio_obs_radio_time ON radio_observations(radio_type, identifier, observed_at DESC)")
        db.execSQL("CREATE INDEX radio_points_source ON radio_points(source_id)")
        db.execSQL("""
            CREATE TABLE scan_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scanned_at INTEGER NOT NULL, wifi_count INTEGER NOT NULL, cell_count INTEGER NOT NULL,
                ble_count INTEGER NOT NULL, matched_count INTEGER NOT NULL,
                latitude REAL, longitude REAL, accuracy REAL, mode TEXT NOT NULL, recorded INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX scan_history_time ON scan_history(scanned_at DESC)")
        createRadioDeletionUndoTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migration preserves points, observations and scan history.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE radio_points ADD COLUMN ssid TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE radio_points SET ssid = label WHERE radio_type = 'wifi' AND position_source = 'wigle-import' AND ssid = ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE radio_points ADD COLUMN peak_rssi INTEGER NOT NULL DEFAULT -120")
            db.execSQL("UPDATE radio_points SET peak_rssi = last_rssi WHERE peak_rssi = -120")
        }
        if (oldVersion < 4) {
            normalizeIdentifiersAndMergeDuplicates(db)
        }
        if (oldVersion < 5) {
            createRadioSourcesTable(db)
            val localId = ensureLocalSource(db)
            val oldWigleBounds = db.rawQuery(
                "SELECT MIN(observed_at), MAX(observed_at) FROM radio_observations WHERE LOWER(position_source) LIKE 'wigle%'", null
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).coerceAtLeast(0L) to cursor.getLong(1).coerceAtLeast(0L) else null
            }
            val hasLegacyWiglePoints = db.rawQuery(
                "SELECT 1 FROM radio_points WHERE LOWER(position_source) LIKE 'wigle%' LIMIT 1", null
            ).use { it.moveToFirst() }
            val importedId = if (oldWigleBounds != null || hasLegacyWiglePoints) {
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("kind", "wigle"); put("name", "WiGLE — импорт до версии 0.5")
                    put("created_at", oldWigleBounds?.first ?: now)
                    put("last_seen", oldWigleBounds?.second ?: now)
                    put("enabled", 1); put("trust_weight", 1.0)
                }
                db.insert("radio_sources", null, values).takeIf { it > 0L }
            } else null
            db.execSQL("ALTER TABLE radio_points ADD COLUMN source_id INTEGER REFERENCES radio_sources(id) ON DELETE SET NULL")
            db.execSQL("ALTER TABLE radio_observations ADD COLUMN source_id INTEGER REFERENCES radio_sources(id) ON DELETE CASCADE")
            val legacyImportId = importedId ?: localId
            db.execSQL("UPDATE radio_points SET source_id = CASE WHEN position_source = 'manual-edit' THEN NULL " +
                "WHEN LOWER(position_source) LIKE 'wigle%' THEN $legacyImportId ELSE $localId END")
            db.execSQL("UPDATE radio_observations SET source_id = CASE WHEN LOWER(position_source) LIKE 'wigle%' " +
                "THEN $legacyImportId ELSE $localId END")
            db.execSQL("CREATE INDEX radio_obs_source_time ON radio_observations(source_id, observed_at DESC)")
            db.execSQL("CREATE INDEX radio_points_source ON radio_points(source_id)")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE radio_points ADD COLUMN position_observed_at INTEGER")
            // Existing selected coordinates predate per-coordinate provenance. last_seen is the safest
            // available timestamp; future writes record the actual position-source observation time.
            db.execSQL("UPDATE radio_points SET position_observed_at = last_seen " +
                "WHERE source_id IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL " +
                "AND position_source <> 'manual-edit'")
        }
        if (oldVersion < 7) {
            createRadioSourceSegmentSettingsTable(db)
        }
        if (oldVersion < 8) {
            createRadioDeletionUndoTables(db)
        }
        if (oldVersion < 9) {
            // Supports radio-key lookups plus the observation-time window used by fingerprints.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS radio_obs_radio_time " +
                    "ON radio_observations(radio_type, identifier, observed_at DESC)"
            )
        }
    }

    /**
     * Identifiers are case-insensitive MAC/network IDs in our collector. WiGLE exports can
     * use uppercase MACs while Android's scanner emits lowercase ones; merge these rows
     * before canonicalizing because radio_points has a UNIQUE(type, identifier) constraint.
     */
    private fun normalizeIdentifiersAndMergeDuplicates(db: SQLiteDatabase) {
        val groups = ArrayList<Pair<String, String>>()
        db.rawQuery(
            "SELECT LOWER(radio_type), LOWER(identifier) FROM radio_points " +
                "GROUP BY LOWER(radio_type), LOWER(identifier) HAVING COUNT(*) > 1",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) groups.add(cursor.getString(0) to cursor.getString(1))
        }

        groups.forEach { (type, identifier) ->
            val records = ArrayList<RadioPointRecord>()
            db.rawQuery(
                "SELECT * FROM radio_points WHERE LOWER(radio_type) = ? AND LOWER(identifier) = ? " +
                    "ORDER BY CASE WHEN position_source = 'manual-edit' THEN 0 " +
                    "WHEN latitude IS NOT NULL AND longitude IS NOT NULL THEN 1 ELSE 2 END, last_seen DESC",
                arrayOf(type, identifier)
            ).use { cursor -> while (cursor.moveToNext()) records.add(cursor.toRadioPoint()) }
            if (records.isEmpty()) return@forEach
            val keeper = records.minByOrNull { it.id } ?: return@forEach
            val newest = records.maxByOrNull { it.lastSeen } ?: keeper
            val position = records.filter {
                it.latitude != null && it.longitude != null &&
                    it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }.minWithOrNull(
                compareBy<RadioPointRecord>(
                    { if (it.positionSource == "manual-edit") 0 else if (it.positionSource.startsWith("wigle")) 1 else 2 },
                    { it.accuracyMeters ?: Double.MAX_VALUE },
                    { -it.peakRssi }
                )
            )
            val notes = records.map { it.notes.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n")
            val merged = ContentValues().apply {
                put("radio_type", type)
                put("identifier", identifier)
                put("label", records.firstOrNull { it.label.isNotBlank() }?.label.orEmpty())
                put("ssid", records.firstOrNull { it.ssid.isNotBlank() }?.ssid.orEmpty())
                put("notes", notes.take(4_000))
                put("latitude", position?.latitude)
                put("longitude", position?.longitude)
                put("accuracy", position?.accuracyMeters)
                put("last_rssi", newest.rssi)
                put("peak_rssi", records.maxOfOrNull { it.peakRssi } ?: newest.peakRssi)
                put("frequency", records.firstOrNull { it.id == newest.id }?.frequency ?: newest.frequency)
                put("first_seen", records.minOfOrNull { it.firstSeen } ?: keeper.firstSeen)
                put("last_seen", records.maxOfOrNull { it.lastSeen } ?: newest.lastSeen)
                put("sightings", records.sumOf { it.sightings }.coerceAtLeast(1))
                put("position_source", position?.positionSource ?: newest.positionSource)
            }
            records.filter { it.id != keeper.id }.forEach { duplicate ->
                db.delete("radio_points", "id = ?", arrayOf(duplicate.id.toString()))
            }
            db.update("radio_points", merged, "id = ?", arrayOf(keeper.id.toString()))
        }
        db.execSQL("UPDATE radio_points SET radio_type = LOWER(radio_type), identifier = LOWER(identifier)")
        db.execSQL("UPDATE radio_observations SET radio_type = LOWER(radio_type), identifier = LOWER(identifier)")
    }

    private fun applyWifiSsidFilter(signals: List<RadioSignal>): List<RadioSignal> {
        val preferences = appContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        val configured = preferences.getString(
            ClearGpsPreferences.KEY_WIFI_SSID_BLACKLIST, RadioSsidFilter.DEFAULT_PATTERNS
        ) ?: RadioSsidFilter.DEFAULT_PATTERNS
        val patterns = RadioSsidFilter.compilePatterns(configured)
        if (patterns.isEmpty()) return signals
        return signals.filterNot { signal -> RadioSsidFilter.excludes(signal.type, signal.ssid, patterns) }
    }

    fun recordScan(
        signals: List<RadioSignal>, reference: RadioReference?, estimate: RadioEstimate?,
        mode: String, wifiCount: Int, cellCount: Int, bleCount: Int, shouldRecord: Boolean
    ): Int {
        val db = writableDatabase
        clearAllRadioDeletionUndo(db)
        val now = System.currentTimeMillis()
        val localSourceId = ensureLocalSource(db)
        db.update("radio_sources", ContentValues().apply { put("last_seen", now) }, "id = ?", arrayOf(localSourceId.toString()))
        val unique = applyWifiSsidFilter(signals).filter { it.identifier.isNotBlank() && it.type in VALID_TYPES }
            .distinctBy { it.type + "|" + it.identifier }
        var recorded = 0
        db.beginTransaction()
        try {
            if (shouldRecord) {
                unique.forEach { signal ->
                    val old = findPoint(db, signal.type, signal.identifier)
                    val values = ContentValues().apply {
                        put("last_rssi", signal.rssi)
                        put("peak_rssi", maxOf(old?.peakRssi ?: -120, signal.rssi))
                        put("frequency", signal.frequency)
                        if (signal.ssid.isNotBlank()) put("ssid", signal.ssid.take(100))
                        put("last_seen", now)
                        put("sightings", (old?.sightings ?: 0) + 1)
                    }
                    if (old == null) {
                        values.put("radio_type", signal.type)
                        values.put("peak_rssi", signal.rssi)
                        values.put("identifier", signal.identifier)
                        values.put("first_seen", now)
                        values.put("position_source", reference?.source ?: "unlocated")
                        if (reference != null && validReference(reference)) values.put("source_id", localSourceId)
                        if (reference != null && validReference(reference)) {
                            values.put("latitude", reference.latitude)
                            values.put("longitude", reference.longitude)
                            values.put("accuracy", reference.accuracyMeters)
                            values.put("position_observed_at", now)
                        }
                        db.insertOrThrow("radio_points", null, values)
                    } else {
                        if (reference != null && validReference(reference) &&
                            shouldReplacePosition(old, reference, signal)) {
                            val newPeakSample = isBetterSignalSample(signal.rssi, old.peakRssi, reference.accuracyMeters)
                            values.put("latitude", reference.latitude)
                            values.put("longitude", reference.longitude)
                            values.put("source_id", localSourceId)
                            values.put("position_observed_at", now)
                            values.put("accuracy", if (newPeakSample) maxOf(reference.accuracyMeters, MIN_SIGNAL_POINT_ACCURACY_METERS)
                                else reference.accuracyMeters)
                            values.put("position_source", if (newPeakSample) "strongest-signal-sample" else reference.source)
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
                        put("source_id", localSourceId)
                        if (reference != null && validReference(reference)) {
                            put("latitude", reference.latitude)
                            put("longitude", reference.longitude)
                            put("accuracy", reference.accuracyMeters)
                        }
                    }
                    db.insert("radio_observations", null, obs)
                    recorded++
                }
                // Keep only recent detail rows at the user's configured cap. Summary points and
                // coordinates remain intact after their detailed observation history is trimmed.
                pruneObservations(db, observationRetentionLimit())
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

    /**
     * Explain why an estimate was rejected without conflating "radio exists in DB" with
     * "radio has a recent, trustworthy coordinate". This is diagnostic only and never creates
     * an estimate from an unlocated or synthetic point.
     */
    fun countMatchingPoints(
        signals: List<RadioSignal>,
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean,
        now: Long = System.currentTimeMillis()
    ): RadioMatchCounts {
        val enabledTypes = buildSet {
            if (useWifi) add("wifi")
            if (useCells) add("cell")
            if (useBle) add("ble")
        }
        val current = applyWifiSsidFilter(signals).asSequence()
            .filter { it.type in enabledTypes && it.identifier.isNotBlank() }
            .distinctBy { it.type + "|" + canonicalIdentifier(it.identifier) }
            .groupBy { it.type }
            .flatMap { (type, values) ->
                val limit = when (type) {
                    "wifi" -> 32
                    "cell" -> 16
                    else -> 32
                }
                values.sortedByDescending { it.rssi }.take(limit)
            }
        val db = readableDatabase
        var known = 0
        var located = 0
        current.forEach { signal ->
            val point = findPoint(db, signal.type, signal.identifier) ?: return@forEach
            known++
            val age = now - point.lastSeen
            val hasCoordinates = point.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                point.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
            val trustworthySource = !point.positionSource.equals("local-radio-estimate", ignoreCase = true) &&
                !point.positionSource.equals("radio-estimate", ignoreCase = true)
            val weight = point.sourceId?.let {
                sourceTrust(db, it, point.type, point.positionObservedAt ?: point.lastSeen) ?: 0.0
            } ?: 1.0
            if (hasCoordinates && trustworthySource && weight > 0.0 && age in 0..radioAnchorMaxAgeMillis(signal.type)) {
                located++
            }
        }
        return RadioMatchCounts(known, located)
    }

    fun estimatePosition(
        signals: List<RadioSignal>, useWifi: Boolean, useCells: Boolean, useBle: Boolean
    ): RadioEstimate? {
        val enabledTypes = buildSet {
            if (useWifi) add("wifi")
            if (useCells) add("cell")
            if (useBle) add("ble")
        }
        val current = applyWifiSsidFilter(signals).asSequence()
            .filter { it.type in enabledTypes && it.identifier.isNotBlank() }
            .distinctBy { it.type + "|" + it.identifier }
            .groupBy { it.type }
            .flatMap { (type, values) ->
                val limit = when (type) {
                    "wifi" -> 32
                    "cell" -> 16
                    else -> 32
                }
                values.sortedByDescending { it.rssi }.take(limit)
            }
        if (current.size < 2) {
            DiagnosticLogStore.log(
                appContext, "RADIO_DB",
                "Расчёт пропущен: обнаружено только ${current.size} разных активных сигналов; требуется минимум 2"
            )
            return null
        }

        val now = System.currentTimeMillis()
        // Prefer a past scan fingerprint: its coordinates are where the phone was,
        // not an average of the physical positions of nearby transmitters.
        val fingerprintLookup = lookupRadioFingerprintPosition(
            current,
            loadFingerprintSamples(current, now),
            now
        )
        if (fingerprintLookup.estimate != null) {
            val estimate = fingerprintLookup.estimate
            DiagnosticLogStore.log(
                appContext, "RADIO_DB",
                if (fingerprintLookup.ambiguous) {
                    "Исторические радиоснимки неоднозначны: выбран лучший кандидат; погрешность увеличена до ±${estimate.accuracyMeters.toInt()} м"
                } else {
                    "Найден исторический радиоснимок: совпадений=${estimate.matchedCount}, " +
                        "погрешность≈${estimate.accuracyMeters.toInt()} м"
                },
                if (fingerprintLookup.ambiguous) "WARN" else "INFO"
            )
            return estimate
        }

        // Fallback for locally imported WiGLE points or manually geolocated radios.
        val matches = ArrayList<Pair<RadioSignal, RadioPointRecord>>()
        val db = readableDatabase
        current.forEach { signal ->
            val point = findPoint(db, signal.type, signal.identifier)
            if (point != null && point.latitude != null && point.longitude != null &&
                point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
                (now - point.lastSeen) in 0..radioAnchorMaxAgeMillis(signal.type)) {
                val weight = point.sourceId?.let {
                sourceTrust(db, it, point.type, point.positionObservedAt ?: point.lastSeen) ?: 0.0
            } ?: 1.0
                if (weight > 0.0) {
                    val rawAccuracy = point.accuracyMeters ?: DEFAULT_IMPORTED_ACCURACY_METERS
                    matches.add(signal to point.copy(accuracyMeters = RadioSourceTrustPolicy.effectiveAccuracy(rawAccuracy, weight)))
                }
            }
        }
        val anchorEstimate = estimateRadioAnchorPosition(matches, now)
        DiagnosticLogStore.log(
            appContext, "RADIO_DB",
            "Сопоставление с координатами базы: услышано сигналов=${current.size}, координатных записей совпало=${matches.size}, " +
                (anchorEstimate?.let {
                    "оценка есть, совпадений в оценке=${it.matchedCount}, погрешность≈${it.accuracyMeters.toInt()} м"
                } ?: "достоверная оценка не построена")
        )
        return anchorEstimate
    }

    /**
     * Only inspect historical scans containing at least one currently visible radio key.
     * The second query expands those scan timestamps to complete fingerprints, so the match
     * score accounts for both shared and missing radios without loading the full history per scan.
     */
    private fun loadFingerprintSamples(signals: List<RadioSignal>, now: Long): List<RadioFingerprintSample> {
        val keys = signals.asSequence()
            .filter { it.type in VALID_TYPES && it.identifier.isNotBlank() }
            .distinctBy { it.type + "|" + it.identifier }
            .toList()
            .take(MAX_FINGERPRINT_KEYS)
        if (keys.size < 2) return emptyList()

        val startTime = now - FINGERPRINT_HISTORY_AGE_MS
        val keyPredicate = keys.joinToString(" OR ") { signal ->
            val compactMac = if (signal.type == "wifi" || signal.type == "ble")
                compactRadioMacIdentifier(signal.identifier) else null
            if (compactMac != null) {
                "(o.radio_type = ? AND replace(replace(replace(lower(o.identifier), ':', ''), '-', ''), '.', '') = ?)"
            } else {
                "(o.radio_type = ? AND o.identifier = ?)"
            }
        }
        val timestampArguments = buildList {
            add(startTime.toString())
            add(now.toString())
            add("unlocated")
            add("local-radio-estimate")
            add("radio-estimate")
            keys.forEach { signal ->
                add(signal.type)
                add(
                    if (signal.type == "wifi" || signal.type == "ble")
                        compactRadioMacIdentifier(signal.identifier) ?: signal.identifier
                    else signal.identifier
                )
            }
            add(MAX_FINGERPRINT_SCANS.toString())
        }.toTypedArray()
        val timestamps = readableDatabase.rawQuery(
            """
            SELECT o.observed_at
            FROM radio_observations o
            JOIN radio_sources s ON s.id = o.source_id
            LEFT JOIN radio_source_segment_settings sg ON sg.source_id = o.source_id
              AND sg.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime')
              AND sg.radio_type = o.radio_type
            WHERE s.enabled = 1 AND s.trust_weight > 0
              AND (sg.enabled IS NULL OR sg.enabled = 1) AND (sg.trust_weight IS NULL OR sg.trust_weight > 0)
              AND o.observed_at >= ? AND o.observed_at <= ?
              AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
              AND o.position_source NOT IN (?, ?, ?)
              AND ($keyPredicate)
            GROUP BY o.observed_at
            ORDER BY COUNT(DISTINCT o.radio_type || ':' || o.identifier) DESC, o.observed_at DESC
            LIMIT ?
            """.trimIndent(),
            timestampArguments
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        if (timestamps.isEmpty()) return emptyList()

        val placeholders = timestamps.joinToString(",") { "?" }
        val arguments = (timestamps.map(Long::toString) + listOf(
            "unlocated", "local-radio-estimate", "radio-estimate"
        )).toTypedArray()
        return readableDatabase.rawQuery(
            """
            SELECT o.radio_type, o.identifier, o.latitude, o.longitude,
                   o.accuracy / MAX(s.trust_weight * COALESCE(sg.trust_weight, 1.0), 0.01) AS effective_accuracy,
                   o.rssi, o.observed_at, o.position_source
            FROM radio_observations o JOIN radio_sources s ON s.id = o.source_id
            LEFT JOIN radio_source_segment_settings sg ON sg.source_id = o.source_id
              AND sg.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime')
              AND sg.radio_type = o.radio_type
            WHERE s.enabled = 1 AND s.trust_weight > 0
              AND (sg.enabled IS NULL OR sg.enabled = 1) AND (sg.trust_weight IS NULL OR sg.trust_weight > 0)
              AND o.observed_at IN ($placeholders)
              AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
              AND o.position_source NOT IN (?, ?, ?)
            ORDER BY observed_at DESC
            LIMIT 20000
            """.trimIndent(),
            arguments
        ).use { cursor ->
            val result = ArrayList<RadioFingerprintSample>()
            while (cursor.moveToNext()) {
                val latIndex = cursor.getColumnIndexOrThrow("latitude")
                val lonIndex = cursor.getColumnIndexOrThrow("longitude")
                result.add(
                    RadioFingerprintSample(
                        radioType = cursor.getString(cursor.getColumnIndexOrThrow("radio_type")).orEmpty(),
                        identifier = canonicalIdentifier(cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty()),
                        latitude = if (cursor.isNull(latIndex)) null else cursor.getDouble(latIndex),
                        longitude = if (cursor.isNull(lonIndex)) null else cursor.getDouble(lonIndex),
                        accuracyMeters = cursor.getDouble(cursor.getColumnIndexOrThrow("effective_accuracy"))
                            .takeUnless { cursor.isNull(cursor.getColumnIndexOrThrow("effective_accuracy")) },
                        rssi = cursor.getInt(cursor.getColumnIndexOrThrow("rssi")),
                        observedAt = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")),
                        positionSource = cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty()
                    )
                )
            }
            result
        }
    }


    private fun createRadioSourcesTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE radio_sources (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind TEXT NOT NULL CHECK(kind IN ('local','wigle','beacondb','custom')), " +
                "name TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, " +
                "enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)), " +
                "trust_weight REAL NOT NULL DEFAULT 1.0 CHECK(trust_weight >= 0.0 AND trust_weight <= 1.0), " +
                "fingerprint TEXT UNIQUE)"
        )
        db.execSQL("CREATE INDEX radio_sources_created ON radio_sources(created_at DESC, id DESC)")
    }

    private fun createRadioSourceSegmentSettingsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE radio_source_segment_settings (" +
                "source_id INTEGER NOT NULL REFERENCES radio_sources(id) ON DELETE CASCADE, " +
                "day_key TEXT NOT NULL, " +
                "radio_type TEXT NOT NULL CHECK(radio_type IN ('wifi','cell','ble')), " +
                "enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)), " +
                "trust_weight REAL NOT NULL DEFAULT 1.0 CHECK(trust_weight >= 0.0 AND trust_weight <= 1.0), " +
                "PRIMARY KEY(source_id, day_key, radio_type))"
        )
        db.execSQL("CREATE INDEX radio_source_segment_enabled ON radio_source_segment_settings(enabled, source_id, day_key)")
    }

    private fun ensureLocalSource(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT id FROM radio_sources WHERE kind = 'local' ORDER BY id LIMIT 1", null).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("kind", "local"); put("name", "Локальная запись")
            put("created_at", now); put("last_seen", now); put("enabled", 1); put("trust_weight", 1.0)
        }
        return db.insertOrThrow("radio_sources", null, values)
    }

    private fun createOrGetImportedSource(
        db: SQLiteDatabase, kind: String, name: String, fingerprint: String, now: Long
    ): Long {
        require(kind == "wigle" || kind == "custom") { "Недопустимый вид источника импорта." }
        val key = fingerprint.take(100)
        db.rawQuery("SELECT id FROM radio_sources WHERE fingerprint = ?", arrayOf(key)).use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                db.update("radio_sources", ContentValues().apply { put("last_seen", now) },
                    "id = ?", arrayOf(id.toString()))
                return id
            }
        }
        val values = ContentValues().apply {
            put("kind", kind); put("name", name.take(120))
            put("created_at", now); put("last_seen", now)
            put("enabled", 1); put("trust_weight", 1.0); put("fingerprint", key)
        }
        val inserted = db.insertWithOnConflict("radio_sources", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted > 0L) return inserted
        db.rawQuery("SELECT id FROM radio_sources WHERE fingerprint = ?", arrayOf(key)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        throw IllegalStateException("Не удалось создать источник импорта.")
    }


    private fun isValidSourceDayKey(dayKey: String): Boolean =
        dayKey.length == 10 &&
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(dayKey) &&
            runCatching { java.time.LocalDate.parse(dayKey) }.isSuccess

    /** Groups detailed observations by local calendar day, per source. */
    fun listRadioSourceDays(): List<RadioSourceDayRecord> {
        val result = ArrayList<RadioSourceDayRecord>()
        readableDatabase.rawQuery(
            "SELECT source_id, strftime('%Y-%m-%d', observed_at / 1000, 'unixepoch', 'localtime') AS day_key, " +
                "MIN(observed_at) AS first_observed, MAX(observed_at) AS last_observed, COUNT(*) AS observations, " +
                "COUNT(DISTINCT radio_type || ':' || identifier) AS radios, " +
                "SUM(CASE WHEN radio_type = 'wifi' THEN 1 ELSE 0 END) AS wifi_count, " +
                "SUM(CASE WHEN radio_type = 'cell' THEN 1 ELSE 0 END) AS cell_count, " +
                "SUM(CASE WHEN radio_type = 'ble' THEN 1 ELSE 0 END) AS ble_count " +
                "FROM radio_observations GROUP BY source_id, day_key ORDER BY day_key DESC, source_id ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dayKey = cursor.getString(cursor.getColumnIndexOrThrow("day_key")).orEmpty()
                if (dayKey.isBlank() || !isValidSourceDayKey(dayKey)) continue
                result.add(
                    RadioSourceDayRecord(
                        sourceId = cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                        dayKey = dayKey,
                        firstObservedAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_observed")),
                        lastObservedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_observed")),
                        observationCount = cursor.getInt(cursor.getColumnIndexOrThrow("observations")),
                        radioCount = cursor.getInt(cursor.getColumnIndexOrThrow("radios")),
                        wifiObservations = cursor.getInt(cursor.getColumnIndexOrThrow("wifi_count")),
                        cellObservations = cursor.getInt(cursor.getColumnIndexOrThrow("cell_count")),
                        bleObservations = cursor.getInt(cursor.getColumnIndexOrThrow("ble_count"))
                    )
                )
            }
        }
        return result
    }

    /**
     * Undo payloads are short-lived snapshots. They are scoped to the current process so a
     * process restart cannot leave a previous snackbar capable of restoring deleted data.
     */
    private fun createRadioDeletionUndoTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS radio_deletion_undo (" +
                "undo_token TEXT PRIMARY KEY, source_id INTEGER NOT NULL, day_key TEXT NOT NULL, " +
                "radio_type TEXT, action TEXT NOT NULL CHECK(action IN ('day','segment')), " +
                "created_at INTEGER NOT NULL, process_session TEXT NOT NULL, source_last_seen INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS radio_deletion_undo_keys (" +
                "undo_token TEXT NOT NULL, radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "PRIMARY KEY(undo_token, radio_type, identifier))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS radio_deletion_undo_points (" +
                "undo_token TEXT NOT NULL, id INTEGER NOT NULL, radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "label TEXT NOT NULL, ssid TEXT NOT NULL, notes TEXT NOT NULL, latitude REAL, longitude REAL, accuracy REAL, " +
                "last_rssi INTEGER NOT NULL, peak_rssi INTEGER NOT NULL, frequency INTEGER NOT NULL, " +
                "first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, sightings INTEGER NOT NULL, " +
                "position_source TEXT NOT NULL, source_id INTEGER, position_observed_at INTEGER, " +
                "PRIMARY KEY(undo_token, id))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS radio_deletion_undo_observations (" +
                "undo_token TEXT NOT NULL, id INTEGER NOT NULL, radio_type TEXT NOT NULL, identifier TEXT NOT NULL, " +
                "latitude REAL, longitude REAL, accuracy REAL, rssi INTEGER NOT NULL, frequency INTEGER NOT NULL, " +
                "observed_at INTEGER NOT NULL, position_source TEXT NOT NULL, source_id INTEGER NOT NULL, " +
                "PRIMARY KEY(undo_token, id))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS radio_deletion_undo_history (" +
                "undo_token TEXT NOT NULL, id INTEGER NOT NULL, scanned_at INTEGER NOT NULL, " +
                "wifi_count INTEGER NOT NULL, cell_count INTEGER NOT NULL, ble_count INTEGER NOT NULL, " +
                "matched_count INTEGER NOT NULL, latitude REAL, longitude REAL, accuracy REAL, " +
                "mode TEXT NOT NULL, recorded INTEGER NOT NULL, PRIMARY KEY(undo_token, id))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS radio_deletion_undo_segments (" +
                "undo_token TEXT NOT NULL, source_id INTEGER NOT NULL, day_key TEXT NOT NULL, radio_type TEXT NOT NULL, " +
                "enabled INTEGER NOT NULL, trust_weight REAL NOT NULL, PRIMARY KEY(undo_token, source_id, day_key, radio_type))"
        )
    }

    private fun clearRadioDeletionUndoToken(db: SQLiteDatabase, undoToken: String) {
        db.delete("radio_deletion_undo_keys", "undo_token = ?", arrayOf(undoToken))
        db.delete("radio_deletion_undo_points", "undo_token = ?", arrayOf(undoToken))
        db.delete("radio_deletion_undo_observations", "undo_token = ?", arrayOf(undoToken))
        db.delete("radio_deletion_undo_history", "undo_token = ?", arrayOf(undoToken))
        db.delete("radio_deletion_undo_segments", "undo_token = ?", arrayOf(undoToken))
        db.delete("radio_deletion_undo", "undo_token = ?", arrayOf(undoToken))
    }

    private fun clearAllRadioDeletionUndo(db: SQLiteDatabase) {
        db.delete("radio_deletion_undo_keys", null, null)
        db.delete("radio_deletion_undo_points", null, null)
        db.delete("radio_deletion_undo_observations", null, null)
        db.delete("radio_deletion_undo_history", null, null)
        db.delete("radio_deletion_undo_segments", null, null)
        db.delete("radio_deletion_undo", null, null)
    }

    private fun purgeRadioDeletionUndo(db: SQLiteDatabase) {
        val expiryBefore = System.currentTimeMillis() - RADIO_DELETION_UNDO_TTL_MS
        val staleTokens = ArrayList<String>()
        db.rawQuery(
            "SELECT undo_token FROM radio_deletion_undo WHERE process_session <> ? OR created_at < ?",
            arrayOf(PROCESS_UNDO_SESSION_ID, expiryBefore.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) staleTokens.add(cursor.getString(0).orEmpty())
        }
        staleTokens.forEach { clearRadioDeletionUndoToken(db, it) }
    }

    private fun createRadioDeletionUndoSnapshot(
        db: SQLiteDatabase,
        sourceId: Long,
        dayKey: String,
        radioType: String?,
        action: String,
        affected: Set<Pair<String, String>>,
        includeScanHistory: Boolean
    ): String {
        clearAllRadioDeletionUndo(db)
        val oldLastSeen = db.rawQuery(
            "SELECT last_seen FROM radio_sources WHERE id = ?",
            arrayOf(sourceId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else System.currentTimeMillis()
        }
        val token = UUID.randomUUID().toString()
        db.insertOrThrow(
            "radio_deletion_undo",
            null,
            ContentValues().apply {
                put("undo_token", token)
                put("source_id", sourceId)
                put("day_key", dayKey)
                if (radioType == null) putNull("radio_type") else put("radio_type", radioType)
                put("action", action)
                put("created_at", System.currentTimeMillis())
                put("process_session", PROCESS_UNDO_SESSION_ID)
                put("source_last_seen", oldLastSeen)
            }
        )
        affected.forEach { (type, identifier) ->
            db.insertOrThrow(
                "radio_deletion_undo_keys",
                null,
                ContentValues().apply {
                    put("undo_token", token)
                    put("radio_type", type)
                    put("identifier", identifier)
                }
            )
        }

        db.execSQL(
            "INSERT INTO radio_deletion_undo_points (" +
                "undo_token, id, radio_type, identifier, label, ssid, notes, latitude, longitude, accuracy, " +
                "last_rssi, peak_rssi, frequency, first_seen, last_seen, sightings, position_source, source_id, position_observed_at) " +
                "SELECT ?, p.id, p.radio_type, p.identifier, p.label, p.ssid, p.notes, p.latitude, p.longitude, p.accuracy, " +
                "p.last_rssi, p.peak_rssi, p.frequency, p.first_seen, p.last_seen, p.sightings, p.position_source, " +
                "p.source_id, p.position_observed_at FROM radio_points p WHERE EXISTS (" +
                "SELECT 1 FROM radio_deletion_undo_keys k WHERE k.undo_token = ? " +
                "AND k.radio_type = p.radio_type AND k.identifier = p.identifier)",
            arrayOf(token, token)
        )

        val observationFilter = buildString {
            append("source_id = ? AND strftime('%Y-%m-%d', observed_at / 1000, 'unixepoch', 'localtime') = ?")
            if (radioType != null) append(" AND radio_type = ?")
        }
        val observationArgs = mutableListOf(token, sourceId.toString(), dayKey)
        if (radioType != null) observationArgs.add(radioType)
        db.execSQL(
            "INSERT INTO radio_deletion_undo_observations (" +
                "undo_token, id, radio_type, identifier, latitude, longitude, accuracy, rssi, frequency, observed_at, position_source, source_id) " +
                "SELECT ?, id, radio_type, identifier, latitude, longitude, accuracy, rssi, frequency, observed_at, position_source, source_id " +
                "FROM radio_observations WHERE " + observationFilter,
            observationArgs.toTypedArray()
        )
        if (includeScanHistory) {
            db.execSQL(
                "INSERT INTO radio_deletion_undo_history (" +
                    "undo_token, id, scanned_at, wifi_count, cell_count, ble_count, matched_count, latitude, longitude, accuracy, mode, recorded) " +
                    "SELECT ?, id, scanned_at, wifi_count, cell_count, ble_count, matched_count, latitude, longitude, accuracy, mode, recorded " +
                    "FROM scan_history WHERE strftime('%Y-%m-%d', scanned_at / 1000, 'unixepoch', 'localtime') = ?",
                arrayOf(token, dayKey)
            )
        }
        val segmentSettingsFilter = if (radioType == null) {
            "source_id = ? AND day_key = ?"
        } else {
            "source_id = ? AND day_key = ? AND radio_type = ?"
        }
        val segmentSettingsArgs = mutableListOf(token, sourceId.toString(), dayKey)
        if (radioType != null) segmentSettingsArgs.add(radioType)
        db.execSQL(
            "INSERT INTO radio_deletion_undo_segments (undo_token, source_id, day_key, radio_type, enabled, trust_weight) " +
                "SELECT ?, source_id, day_key, radio_type, enabled, trust_weight FROM radio_source_segment_settings " +
                "WHERE " + segmentSettingsFilter,
            segmentSettingsArgs.toTypedArray()
        )
        return token
    }

    /**
     * Undo the latest source/day or source/day/type deletion during the snackbar window.
     * The original rows are restored transactionally; snapshots are discarded on success.
     */
    fun undoRadioSourceDeletion(undoToken: String): Boolean {
        if (undoToken.isBlank() || undoToken.length > 64) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            var sourceId: Long? = null
            var createdAt = 0L
            var processSession = ""
            var sourceLastSeen = 0L
            db.rawQuery(
                "SELECT source_id, created_at, process_session, source_last_seen FROM radio_deletion_undo WHERE undo_token = ?",
                arrayOf(undoToken)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    sourceId = cursor.getLong(0)
                    createdAt = cursor.getLong(1)
                    processSession = cursor.getString(2).orEmpty()
                    sourceLastSeen = cursor.getLong(3)
                }
            }
            val savedSourceId = sourceId
            if (savedSourceId == null) {
                db.setTransactionSuccessful()
                return false
            }
            val age = System.currentTimeMillis() - createdAt
            if (processSession != PROCESS_UNDO_SESSION_ID || age < 0L || age > RADIO_DELETION_UNDO_TTL_MS) {
                clearRadioDeletionUndoToken(db, undoToken)
                db.setTransactionSuccessful()
                return false
            }
            if (readSourceState(db, savedSourceId) == null) {
                clearRadioDeletionUndoToken(db, undoToken)
                db.setTransactionSuccessful()
                return false
            }

            db.execSQL(
                "INSERT OR REPLACE INTO radio_observations (" +
                    "id, radio_type, identifier, latitude, longitude, accuracy, rssi, frequency, observed_at, position_source, source_id) " +
                    "SELECT id, radio_type, identifier, latitude, longitude, accuracy, rssi, frequency, observed_at, position_source, source_id " +
                    "FROM radio_deletion_undo_observations WHERE undo_token = ?",
                arrayOf(undoToken)
            )
            db.execSQL(
                "INSERT OR REPLACE INTO radio_points (" +
                    "id, radio_type, identifier, label, ssid, notes, latitude, longitude, accuracy, last_rssi, peak_rssi, frequency, " +
                    "first_seen, last_seen, sightings, position_source, source_id, position_observed_at) " +
                    "SELECT b.id, b.radio_type, b.identifier, b.label, b.ssid, b.notes, b.latitude, b.longitude, b.accuracy, " +
                    "b.last_rssi, b.peak_rssi, b.frequency, b.first_seen, b.last_seen, b.sightings, b.position_source, " +
                    "b.source_id, b.position_observed_at FROM radio_deletion_undo_points b " +
                    "WHERE b.undo_token = ? AND (b.source_id IS NULL OR EXISTS " +
                    "(SELECT 1 FROM radio_sources s WHERE s.id = b.source_id))",
                arrayOf(undoToken)
            )
            db.execSQL(
                "INSERT OR REPLACE INTO scan_history (" +
                    "id, scanned_at, wifi_count, cell_count, ble_count, matched_count, latitude, longitude, accuracy, mode, recorded) " +
                    "SELECT id, scanned_at, wifi_count, cell_count, ble_count, matched_count, latitude, longitude, accuracy, mode, recorded " +
                    "FROM radio_deletion_undo_history WHERE undo_token = ?",
                arrayOf(undoToken)
            )
            db.execSQL(
                "INSERT OR REPLACE INTO radio_source_segment_settings (source_id, day_key, radio_type, enabled, trust_weight) " +
                    "SELECT b.source_id, b.day_key, b.radio_type, b.enabled, b.trust_weight " +
                    "FROM radio_deletion_undo_segments b WHERE b.undo_token = ? " +
                    "AND EXISTS (SELECT 1 FROM radio_sources s WHERE s.id = b.source_id)",
                arrayOf(undoToken)
            )
            db.update(
                "radio_sources",
                ContentValues().apply { put("last_seen", sourceLastSeen) },
                "id = ?",
                arrayOf(savedSourceId.toString())
            )
            clearRadioDeletionUndoToken(db, undoToken)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun discardRadioSourceDeletionUndo(undoToken: String) {
        if (undoToken.isBlank() || undoToken.length > 64) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearRadioDeletionUndoToken(db, undoToken)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Deletes detailed observations for one source/day, then recomputes affected summary positions.
     * The selected-coordinate timestamp prevents a deleted day from becoming a stale position candidate.
     */
    fun deleteRadioSourceDay(sourceId: Long, dayKey: String): RadioSourceDayDeleteResult {
        require(isValidSourceDayKey(dayKey)) { "Неверная дата источника." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sourceState = readSourceState(db, sourceId) ?: return RadioSourceDayDeleteResult(0, 0, 0)
            val affected = LinkedHashSet<Pair<String, String>>()
            db.rawQuery(
                "SELECT radio_type, identifier FROM radio_points WHERE source_id = ? AND position_observed_at IS NOT NULL " +
                    "AND strftime('%Y-%m-%d', position_observed_at / 1000, 'unixepoch', 'localtime') = ? " +
                    "UNION SELECT radio_type, identifier FROM radio_observations WHERE source_id = ? " +
                    "AND strftime('%Y-%m-%d', observed_at / 1000, 'unixepoch', 'localtime') = ?",
                arrayOf(sourceId.toString(), dayKey, sourceId.toString(), dayKey)
            ).use { cursor ->
                while (cursor.moveToNext()) affected.add(cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty())
            }

            val undoToken = createRadioDeletionUndoSnapshot(
                db, sourceId, dayKey, radioType = null, action = "day",
                affected = affected, includeScanHistory = sourceState.first == "local"
            )
            db.update(
                "radio_points",
                ContentValues().apply {
                    putNull("latitude"); putNull("longitude"); putNull("accuracy")
                    putNull("source_id"); putNull("position_observed_at")
                    put("position_source", "unlocated")
                },
                "source_id = ? AND position_observed_at IS NOT NULL " +
                    "AND strftime('%Y-%m-%d', position_observed_at / 1000, 'unixepoch', 'localtime') = ?",
                arrayOf(sourceId.toString(), dayKey)
            )
            val deleted = db.delete(
                "radio_observations",
                "source_id = ? AND strftime('%Y-%m-%d', observed_at / 1000, 'unixepoch', 'localtime') = ?",
                arrayOf(sourceId.toString(), dayKey)
            )
            val deletedHistory = if (sourceState.first == "local") {
                db.delete(
                    "scan_history",
                    "strftime('%Y-%m-%d', scanned_at / 1000, 'unixepoch', 'localtime') = ?",
                    arrayOf(dayKey)
                )
            } else 0
            val deletedSegmentSettings = db.delete(
                "radio_source_segment_settings",
                "source_id = ? AND day_key = ?",
                arrayOf(sourceId.toString(), dayKey)
            )

            db.execSQL(
                "UPDATE radio_sources SET last_seen = MAX(created_at, COALESCE(" +
                    "(SELECT MAX(observed_at) FROM radio_observations WHERE source_id = ?), created_at)) WHERE id = ?",
                arrayOf(sourceId.toString(), sourceId.toString())
            )
            affected.forEach { (type, identifier) -> rebuildRadioPointPosition(db, type, identifier) }
            val hasChanges = deleted > 0 || deletedHistory > 0 || deletedSegmentSettings > 0 || affected.isNotEmpty()
            if (!hasChanges) clearRadioDeletionUndoToken(db, undoToken)
            db.setTransactionSuccessful()
            return RadioSourceDayDeleteResult(
                deleted, deletedHistory, affected.size, undoToken.takeIf { hasChanges }
            )
        } finally {
            db.endTransaction()
        }
    }


    fun listRadioSourceSegments(sourceId: Long? = null): List<RadioSourceSegmentRecord> {
        val where = if (sourceId == null) "" else "WHERE o.source_id = ? "
        val args = sourceId?.let { arrayOf(it.toString()) } ?: emptyArray()
        val result = ArrayList<RadioSourceSegmentRecord>()
        readableDatabase.rawQuery(
            "SELECT o.source_id, strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime') AS day_key, " +
                "o.radio_type, MIN(o.observed_at) AS first_observed, MAX(o.observed_at) AS last_observed, " +
                "COUNT(*) AS observations, COUNT(DISTINCT o.identifier) AS radios, " +
                "MIN(o.rssi) AS min_rssi, MAX(o.rssi) AS max_rssi, " +
                "COALESCE(st.enabled, 1) AS segment_enabled, COALESCE(st.trust_weight, 1.0) AS segment_weight " +
                "FROM radio_observations o " +
                "LEFT JOIN radio_source_segment_settings st ON st.source_id = o.source_id " +
                "AND st.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime') " +
                "AND st.radio_type = o.radio_type " +
                where + "GROUP BY o.source_id, day_key, o.radio_type " +
                "ORDER BY day_key DESC, o.source_id ASC, o.radio_type ASC",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val dayKey = cursor.getString(cursor.getColumnIndexOrThrow("day_key")).orEmpty()
                if (!isValidSourceDayKey(dayKey)) continue
                result.add(
                    RadioSourceSegmentRecord(
                        sourceId = cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                        dayKey = dayKey,
                        radioType = cursor.getString(cursor.getColumnIndexOrThrow("radio_type")).orEmpty(),
                        firstObservedAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_observed")),
                        lastObservedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_observed")),
                        observationCount = cursor.getInt(cursor.getColumnIndexOrThrow("observations")),
                        radioCount = cursor.getInt(cursor.getColumnIndexOrThrow("radios")),
                        minRssi = cursor.getInt(cursor.getColumnIndexOrThrow("min_rssi")),
                        maxRssi = cursor.getInt(cursor.getColumnIndexOrThrow("max_rssi")),
                        enabled = cursor.getInt(cursor.getColumnIndexOrThrow("segment_enabled")) != 0,
                        trustWeight = RadioSourceTrustPolicy.normalizeWeight(cursor.getDouble(cursor.getColumnIndexOrThrow("segment_weight")))
                    )
                )
            }
        }
        return result
    }

    private fun affectedRadioKeysForSegment(
        db: SQLiteDatabase, sourceId: Long, dayKey: String, radioType: String
    ): Set<Pair<String, String>> {
        val result = LinkedHashSet<Pair<String, String>>()
        db.rawQuery(
            "SELECT radio_type, identifier FROM radio_points WHERE source_id = ? AND radio_type = ? " +
                "AND position_observed_at IS NOT NULL " +
                "AND strftime('%Y-%m-%d', position_observed_at / 1000, 'unixepoch', 'localtime') = ? " +
                "UNION SELECT radio_type, identifier FROM radio_observations WHERE source_id = ? AND radio_type = ? " +
                "AND strftime('%Y-%m-%d', observed_at / 1000, 'unixepoch', 'localtime') = ?",
            arrayOf(sourceId.toString(), radioType, dayKey, sourceId.toString(), radioType, dayKey)
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty())
        }
        return result
    }

    private fun saveRadioSourceSegmentSetting(
        db: SQLiteDatabase, sourceId: Long, dayKey: String, radioType: String,
        enabled: Boolean, weight: Double
    ) {
        val values = ContentValues().apply {
            put("source_id", sourceId); put("day_key", dayKey); put("radio_type", radioType)
            put("enabled", if (enabled) 1 else 0)
            put("trust_weight", RadioSourceTrustPolicy.normalizeWeight(weight))
        }
        db.insertWithOnConflict("radio_source_segment_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setRadioSourceSegmentEnabled(sourceId: Long, dayKey: String, radioType: String, enabled: Boolean): Boolean {
        require(isValidSourceDayKey(dayKey)) { "Неверная дата источника." }
        require(radioType in VALID_TYPES) { "Неизвестный тип радиосигнала." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            if (readSourceState(db, sourceId) == null) return false
            var weight = 1.0
            db.rawQuery(
                "SELECT trust_weight FROM radio_source_segment_settings WHERE source_id = ? AND day_key = ? AND radio_type = ?",
                arrayOf(sourceId.toString(), dayKey, radioType)
            ).use { if (it.moveToFirst()) weight = it.getDouble(0) }
            saveRadioSourceSegmentSetting(db, sourceId, dayKey, radioType, enabled, weight)
            affectedRadioKeysForSegment(db, sourceId, dayKey, radioType).forEach { (type, identifier) ->
                rebuildRadioPointPosition(db, type, identifier)
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun setRadioSourceSegmentTrustWeight(
        sourceId: Long, dayKey: String, radioType: String, requestedWeight: Double
    ): Boolean {
        require(isValidSourceDayKey(dayKey)) { "Неверная дата источника." }
        require(radioType in VALID_TYPES) { "Неизвестный тип радиосигнала." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            if (readSourceState(db, sourceId) == null) return false
            var enabled = true
            db.rawQuery(
                "SELECT enabled FROM radio_source_segment_settings WHERE source_id = ? AND day_key = ? AND radio_type = ?",
                arrayOf(sourceId.toString(), dayKey, radioType)
            ).use { if (it.moveToFirst()) enabled = it.getInt(0) != 0 }
            saveRadioSourceSegmentSetting(db, sourceId, dayKey, radioType, enabled, requestedWeight)
            affectedRadioKeysForSegment(db, sourceId, dayKey, radioType).forEach { (type, identifier) ->
                rebuildRadioPointPosition(db, type, identifier)
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun deleteRadioSourceSegment(sourceId: Long, dayKey: String, radioType: String): RadioSourceSegmentDeleteResult {
        require(isValidSourceDayKey(dayKey)) { "Неверная дата источника." }
        require(radioType in VALID_TYPES) { "Неизвестный тип радиосигнала." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (readSourceState(db, sourceId) == null) return RadioSourceSegmentDeleteResult(0, 0)
            val affected = affectedRadioKeysForSegment(db, sourceId, dayKey, radioType)
            val undoToken = createRadioDeletionUndoSnapshot(
                db, sourceId, dayKey, radioType = radioType, action = "segment",
                affected = affected, includeScanHistory = false
            )
            db.update(
                "radio_points",
                ContentValues().apply {
                    putNull("latitude"); putNull("longitude"); putNull("accuracy")
                    putNull("source_id"); putNull("position_observed_at")
                    put("position_source", "unlocated")
                },
                "source_id = ? AND radio_type = ? AND position_observed_at IS NOT NULL " +
                    "AND strftime('%Y-%m-%d', position_observed_at / 1000, 'unixepoch', 'localtime') = ?",
                arrayOf(sourceId.toString(), radioType, dayKey)
            )
            val deleted = db.delete(
                "radio_observations",
                "source_id = ? AND radio_type = ? AND strftime('%Y-%m-%d', observed_at / 1000, 'unixepoch', 'localtime') = ?",
                arrayOf(sourceId.toString(), radioType, dayKey)
            )
            val deletedSettings = db.delete(
                "radio_source_segment_settings",
                "source_id = ? AND day_key = ? AND radio_type = ?",
                arrayOf(sourceId.toString(), dayKey, radioType)
            )
            db.execSQL(
                "UPDATE radio_sources SET last_seen = MAX(created_at, COALESCE(" +
                    "(SELECT MAX(observed_at) FROM radio_observations WHERE source_id = ?), created_at)) WHERE id = ?",
                arrayOf(sourceId.toString(), sourceId.toString())
            )
            affected.forEach { (type, identifier) -> rebuildRadioPointPosition(db, type, identifier) }
            val hasChanges = deleted > 0 || deletedSettings > 0 || affected.isNotEmpty()
            if (!hasChanges) clearRadioDeletionUndoToken(db, undoToken)
            db.setTransactionSuccessful()
            return RadioSourceSegmentDeleteResult(
                deleted, affected.size, undoToken.takeIf { hasChanges }
            )
        } finally { db.endTransaction() }
    }

    fun listRadioSources(): List<RadioSourceRecord> {
        val result = ArrayList<RadioSourceRecord>()
        readableDatabase.rawQuery(
            "SELECT s.id, s.kind, s.name, s.created_at, s.last_seen, s.enabled, s.trust_weight, " +
                "(SELECT COUNT(*) FROM radio_observations o WHERE o.source_id = s.id) AS observations, " +
                "((SELECT COUNT(DISTINCT o.radio_type || ':' || o.identifier) FROM radio_observations o WHERE o.source_id = s.id) + " +
                "(SELECT COUNT(*) FROM radio_points p WHERE p.source_id = s.id AND NOT EXISTS " +
                "(SELECT 1 FROM radio_observations o WHERE o.source_id = s.id AND o.radio_type = p.radio_type AND o.identifier = p.identifier))) AS radios " +
                "FROM radio_sources s ORDER BY CASE WHEN s.kind = 'local' THEN 0 ELSE 1 END, s.created_at DESC, s.id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(
                RadioSourceRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")).orEmpty(),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")).orEmpty(),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen")),
                    enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0,
                    trustWeight = RadioSourceTrustPolicy.normalizeWeight(cursor.getDouble(cursor.getColumnIndexOrThrow("trust_weight"))),
                    observationCount = cursor.getInt(cursor.getColumnIndexOrThrow("observations")),
                    radioCount = cursor.getInt(cursor.getColumnIndexOrThrow("radios"))
                )
            )
        }
        return result
    }

    fun setRadioSourceEnabled(sourceId: Long, enabled: Boolean): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            val source = readSourceState(db, sourceId) ?: return false
            if (source.first == "local" && !enabled) return false
            val changed = db.update("radio_sources", ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
                "id = ?", arrayOf(sourceId.toString())) > 0
            if (changed) rebuildPositionsForSource(db, sourceId)
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    fun setRadioSourceTrustWeight(sourceId: Long, requestedWeight: Double): Boolean {
        val weight = RadioSourceTrustPolicy.normalizeWeight(requestedWeight)
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            val source = readSourceState(db, sourceId) ?: return false
            if (source.first == "local") return false
            val changed = db.update("radio_sources", ContentValues().apply { put("trust_weight", weight) },
                "id = ?", arrayOf(sourceId.toString())) > 0
            if (changed) rebuildPositionsForSource(db, sourceId)
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    fun deleteRadioSource(sourceId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            val source = readSourceState(db, sourceId) ?: return false
            if (source.first == "local") return false
            val affected = affectedRadioKeys(db, sourceId)
            if (db.delete("radio_sources", "id = ?", arrayOf(sourceId.toString())) == 0) return false
            affected.forEach { (type, identifier) -> rebuildRadioPointPosition(db, type, identifier) }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    private fun readSourceState(db: SQLiteDatabase, sourceId: Long): Pair<String, Pair<Boolean, Double>>? =
        db.rawQuery("SELECT kind, enabled, trust_weight FROM radio_sources WHERE id = ?", arrayOf(sourceId.toString())).use {
            if (!it.moveToFirst()) null else it.getString(0).orEmpty() to
                ((it.getInt(1) != 0) to RadioSourceTrustPolicy.normalizeWeight(it.getDouble(2)))
        }

    private fun sourceTrust(
        db: SQLiteDatabase,
        sourceId: Long,
        radioType: String? = null,
        observedAt: Long? = null
    ): Double? {
        val sourceWeight = db.rawQuery(
            "SELECT trust_weight FROM radio_sources WHERE id = ? AND enabled = 1",
            arrayOf(sourceId.toString())
        ).use {
            if (!it.moveToFirst()) return null
            RadioSourceTrustPolicy.normalizeWeight(it.getDouble(0))
        }
        if (radioType == null || observedAt == null || radioType !in VALID_TYPES) return sourceWeight
        val dayKey = db.rawQuery(
            "SELECT strftime('%Y-%m-%d', ? / 1000, 'unixepoch', 'localtime')",
            arrayOf(observedAt.toString())
        ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
        if (!isValidSourceDayKey(dayKey)) return sourceWeight
        return db.rawQuery(
            "SELECT enabled, trust_weight FROM radio_source_segment_settings " +
                "WHERE source_id = ? AND day_key = ? AND radio_type = ?",
            arrayOf(sourceId.toString(), dayKey, radioType)
        ).use { cursor ->
            if (!cursor.moveToFirst()) sourceWeight
            else if (cursor.getInt(0) == 0) null
            else sourceWeight * RadioSourceTrustPolicy.normalizeWeight(cursor.getDouble(1))
        }
    }

    private fun affectedRadioKeys(db: SQLiteDatabase, sourceId: Long): Set<Pair<String, String>> {
        val result = LinkedHashSet<Pair<String, String>>()
        db.rawQuery(
            "SELECT radio_type, identifier FROM radio_points WHERE source_id = ? " +
                "UNION SELECT radio_type, identifier FROM radio_observations WHERE source_id = ?",
            arrayOf(sourceId.toString(), sourceId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty())
        }
        return result
    }

    private data class PositionCandidate(
        val latitude: Double, val longitude: Double, val accuracy: Double?,
        val positionSource: String, val sourceId: Long, val observedAt: Long, val effectiveAccuracy: Double
    )

    private fun rebuildPositionsForSource(db: SQLiteDatabase, sourceId: Long) {
        affectedRadioKeys(db, sourceId).forEach { (type, identifier) -> rebuildRadioPointPosition(db, type, identifier) }
    }

    private fun rebuildRadioPointPosition(db: SQLiteDatabase, type: String, identifier: String) {
        val point = findPoint(db, type, identifier) ?: return
        if (point.positionSource == "manual-edit") return
        val candidates = ArrayList<PositionCandidate>()
        fun addCandidate(lat: Double?, lon: Double?, accuracy: Double?, sourceId: Long?, source: String, at: Long) {
            if (sourceId == null || !validCoordinates(lat, lon)) return
            val weight = sourceTrust(db, sourceId, type, at) ?: return
            if (weight <= 0.0) return
            val rawAccuracy = accuracy?.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_IMPORTED_ACCURACY_METERS
            candidates.add(PositionCandidate(lat!!, lon!!, rawAccuracy, source, sourceId, at,
                RadioSourceTrustPolicy.effectiveAccuracy(rawAccuracy, weight)))
        }
        addCandidate(point.latitude, point.longitude, point.accuracyMeters, point.sourceId, point.positionSource, point.positionObservedAt ?: point.lastSeen)
        db.rawQuery(
            "SELECT o.latitude, o.longitude, o.accuracy, o.position_source, o.source_id, o.observed_at " +
                "FROM radio_observations o JOIN radio_sources s ON s.id = o.source_id " +
                "LEFT JOIN radio_source_segment_settings sg ON sg.source_id = o.source_id " +
                "AND sg.day_key = strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime') " +
                "AND sg.radio_type = o.radio_type " +
                "WHERE o.radio_type = ? AND o.identifier = ? AND s.enabled = 1 AND s.trust_weight > 0 " +
                "AND (sg.enabled IS NULL OR sg.enabled = 1) AND (sg.trust_weight IS NULL OR sg.trust_weight > 0) " +
                "AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL " +
                "AND o.latitude BETWEEN -90 AND 90 AND o.longitude BETWEEN -180 AND 180 " +
                "AND o.position_source NOT IN ('unlocated','local-radio-estimate','radio-estimate')",
            arrayOf(type, identifier)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ai = cursor.getColumnIndexOrThrow("accuracy")
                addCandidate(cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                    if (cursor.isNull(ai)) null else cursor.getDouble(ai),
                    cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty(),
                    cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")))
            }
        }
        val best = candidates.minWithOrNull(
            compareBy<PositionCandidate> { it.effectiveAccuracy }.thenByDescending { it.observedAt }
        )
        if (best != null) {
            db.update("radio_points", ContentValues().apply {
                put("latitude", best.latitude); put("longitude", best.longitude)
                put("accuracy", best.accuracy ?: DEFAULT_IMPORTED_ACCURACY_METERS)
                put("position_source", best.positionSource); put("source_id", best.sourceId)
                put("position_observed_at", best.observedAt)
            }, "id = ?", arrayOf(point.id.toString()))
            return
        }
        val retained = db.rawQuery(
            "SELECT COUNT(*) FROM radio_observations WHERE radio_type = ? AND identifier = ?",
            arrayOf(type, identifier)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (retained == 0) db.delete("radio_points", "id = ?", arrayOf(point.id.toString()))
        else db.update("radio_points", ContentValues().apply {
            putNull("latitude"); putNull("longitude"); putNull("accuracy"); putNull("source_id"); putNull("position_observed_at")
            put("position_source", "unlocated")
        }, "id = ?", arrayOf(point.id.toString()))
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

    /** All retained observations for one radio point (the DB keeps at most 20,000). */
    fun listObservations(point: RadioPointRecord, limit: Int = 20_000): List<RadioPointObservation> {
        val result = ArrayList<RadioPointObservation>()
        readableDatabase.rawQuery(
            "SELECT o.id, o.observed_at, o.latitude, o.longitude, o.accuracy, o.rssi, o.frequency, o.position_source, o.source_id, s.name AS source_name " +
                "FROM radio_observations o LEFT JOIN radio_sources s ON s.id = o.source_id " +
                "WHERE o.radio_type = ? AND o.identifier = ? " +
                "ORDER BY observed_at DESC, id DESC LIMIT ?",
            arrayOf(point.type, point.identifier, limit.coerceIn(1, 20_000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val latIndex = cursor.getColumnIndexOrThrow("latitude")
                val lonIndex = cursor.getColumnIndexOrThrow("longitude")
                val accuracyIndex = cursor.getColumnIndexOrThrow("accuracy")
                result.add(RadioPointObservation(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    observedAt = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")),
                    latitude = if (cursor.isNull(latIndex)) null else cursor.getDouble(latIndex),
                    longitude = if (cursor.isNull(lonIndex)) null else cursor.getDouble(lonIndex),
                    accuracyMeters = if (cursor.isNull(accuracyIndex)) null else cursor.getDouble(accuracyIndex),
                    rssi = cursor.getInt(cursor.getColumnIndexOrThrow("rssi")),
                    frequency = cursor.getInt(cursor.getColumnIndexOrThrow("frequency")),
                    positionSource = cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty(),
                    sourceId = if (cursor.isNull(cursor.getColumnIndexOrThrow("source_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("source_id")),
                    sourceName = cursor.getString(cursor.getColumnIndexOrThrow("source_name"))
                ))
            }
        }
        return result
    }

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

    fun observationCount(): Int = countObservations(readableDatabase)

    fun observationRetentionLimit(): Int {
        val stored = appContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getInt(ClearGpsPreferences.KEY_RADIO_OBSERVATION_LIMIT, RadioDatabaseRetentionPolicy.DEFAULT_LIMIT)
        return RadioDatabaseRetentionPolicy.normalizeLimit(stored)
    }

    /** Saves the selected cap and immediately removes the oldest detailed rows if necessary. */
    fun setObservationRetentionLimit(requestedLimit: Int): Int {
        val limit = RadioDatabaseRetentionPolicy.normalizeLimit(requestedLimit)
        appContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit().putInt(ClearGpsPreferences.KEY_RADIO_OBSERVATION_LIMIT, limit).apply()
        return pruneToObservationLimit(limit)
    }

    private fun pruneToObservationLimit(limit: Int): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            val removed = pruneObservations(db, limit)
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    private fun pruneObservations(db: SQLiteDatabase, requestedLimit: Int): Int {
        val limit = RadioDatabaseRetentionPolicy.normalizeLimit(requestedLimit)
        val before = countObservations(db)
        if (RadioDatabaseRetentionPolicy.recordsToDelete(before, limit) == 0) return 0
        // The interpolated number is safe because normalizeLimit accepts only fixed choices.
        db.execSQL(
            "DELETE FROM radio_observations WHERE id NOT IN " +
                "(SELECT id FROM radio_observations ORDER BY observed_at DESC, id DESC LIMIT $limit)"
        )
        return (before - countObservations(db)).coerceAtLeast(0)
    }

    private fun countObservations(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM radio_observations", null)
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
                put("position_source", "unlocated"); putNull("source_id"); putNull("position_observed_at")
            } else {
                put("latitude", latitude); put("longitude", longitude)
                put("accuracy", (accuracy ?: DEFAULT_IMPORTED_ACCURACY_METERS).coerceIn(1.0, 100_000.0))
                put("position_source", "manual-edit"); putNull("source_id"); putNull("position_observed_at")
            }
        }
        val db = writableDatabase
        clearAllRadioDeletionUndo(db)
        return db.update("radio_points", values, "id = ?", arrayOf(id.toString())) > 0
    }

    fun deletePoint(id: Long): Boolean {
        val db = writableDatabase
        clearAllRadioDeletionUndo(db)
        return db.delete("radio_points", "id = ?", arrayOf(id.toString())) > 0
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllRadioDeletionUndo(db)
            db.delete("radio_observations", null, null)
            db.delete("radio_points", null, null)
            db.delete("scan_history", null, null)
            db.delete("radio_sources", "kind <> 'local'", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun exportWigleCsv(sourceId: Long? = null, dayKey: String? = null, radioType: String? = null): String {
        require(dayKey == null || sourceId != null) { "Для экспорта по дню нужно выбрать источник." }
        require(dayKey == null || isValidSourceDayKey(dayKey)) { "Неверная дата источника." }
        require(radioType == null || (sourceId != null && dayKey != null && radioType in VALID_TYPES)) {
            "Для экспорта отдельной записи нужны источник, дата и тип радиосигнала."
        }
        val observationClauses = ArrayList<String>()
        val filterArgs = ArrayList<String>()
        if (sourceId != null) { observationClauses.add("o.source_id = ?"); filterArgs.add(sourceId.toString()) }
        if (dayKey != null) {
            observationClauses.add("strftime('%Y-%m-%d', o.observed_at / 1000, 'unixepoch', 'localtime') = ?")
            filterArgs.add(dayKey)
        }
        if (radioType != null) {
            observationClauses.add("o.radio_type = ?")
            filterArgs.add(radioType)
        }
        val observationWhere = if (observationClauses.isEmpty()) "" else "WHERE " + observationClauses.joinToString(" AND ") + " "
        val pointClauses = ArrayList<String>()
        if (sourceId != null) pointClauses.add("p.source_id = ?")
        if (dayKey != null) pointClauses.add("strftime('%Y-%m-%d', p.position_observed_at / 1000, 'unixepoch', 'localtime') = ?")
        if (radioType != null) pointClauses.add("p.radio_type = ?")
        val pointFilters = if (pointClauses.isEmpty()) "" else " AND " + pointClauses.joinToString(" AND ")
        val args = filterArgs.toTypedArray()
        val db = readableDatabase
        val out = StringBuilder()
        out.append("WigleWifi-1.6,appRelease=Clear GPS 0.4,model=Android,device=Clear GPS,brand=Android\n")
        out.append("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type\n")

        fun appendRow(
            type: String, identifier: String, ssid: String, time: Long, frequency: Int,
            rssi: Int, latitude: Double?, longitude: Double?, accuracy: Double?
        ) {
            val wigleType = when (type) {
                "ble" -> "BTLE"
                "cell" -> "BTS"
                else -> "WIFI"
            }
            val values = listOf(
                identifier, ssid, "", UTC_FORMAT.format(Date(time)),
                frequencyToChannel(frequency).toString(), frequency.toString(), rssi.toString(),
                latitude?.toString().orEmpty(), longitude?.toString().orEmpty(), "",
                accuracy?.toString().orEmpty(), "", "", wigleType
            )
            out.append(values.joinToString(",") { csvEscape(it) }).append('\n')
        }

        // WiGLE CSV is an observation interchange format: preserve every sighting.
        db.rawQuery(
            "SELECT o.radio_type, o.identifier, COALESCE(NULLIF(p.ssid, ''), p.label, '') AS ssid, " +
                "o.observed_at, o.frequency, o.rssi, o.latitude, o.longitude, o.accuracy " +
                "FROM radio_observations o LEFT JOIN radio_points p " +
                "ON LOWER(p.radio_type) = LOWER(o.radio_type) AND LOWER(p.identifier) = LOWER(o.identifier) " +
                observationWhere + "ORDER BY o.observed_at ASC, o.id ASC",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                appendRow(
                    cursor.getString(cursor.getColumnIndexOrThrow("radio_type")).orEmpty(),
                    cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty(),
                    cursor.getString(cursor.getColumnIndexOrThrow("ssid")).orEmpty(),
                    cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("frequency")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("rssi")),
                    cursor.doubleOrNull("latitude"), cursor.doubleOrNull("longitude"), cursor.doubleOrNull("accuracy")
                )
            }
        }

        // Legacy/manual records without detailed history still get one representative row.
        db.rawQuery(
            "SELECT p.radio_type, p.identifier, COALESCE(NULLIF(p.ssid, ''), p.label, '') AS ssid, " +
                "p.first_seen AS observed_at, p.frequency, p.last_rssi AS rssi, p.latitude, p.longitude, p.accuracy " +
                "FROM radio_points p WHERE NOT EXISTS (" +
                "SELECT 1 FROM radio_observations o WHERE LOWER(o.radio_type) = LOWER(p.radio_type) " +
                "AND LOWER(o.identifier) = LOWER(p.identifier)) " +
                pointFilters + " ORDER BY p.first_seen ASC, p.id ASC",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                appendRow(
                    cursor.getString(cursor.getColumnIndexOrThrow("radio_type")).orEmpty(),
                    cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty(),
                    cursor.getString(cursor.getColumnIndexOrThrow("ssid")).orEmpty(),
                    cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("frequency")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("rssi")),
                    cursor.doubleOrNull("latitude"), cursor.doubleOrNull("longitude"), cursor.doubleOrNull("accuracy")
                )
            }
        }
        return out.toString()
    }

    fun importWigleCsv(input: InputStream): RadioImportResult {
        val raw = input.use { readBounded(it, MAX_IMPORT_BYTES) }
        val decoded = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { readBounded(it, MAX_IMPORT_BYTES) }
        } else raw
        val rows = parseCsv(String(decoded, Charsets.UTF_8).removePrefix("\uFEFF"))
        val headerIndex = rows.indexOfFirst { row ->
            val header = row.map { it.trim().removePrefix("\uFEFF").uppercase(Locale.ROOT) }
            "MAC" in header && "CURRENTLATITUDE" in header && "CURRENTLONGITUDE" in header
        }
        if (headerIndex < 0) throw IllegalArgumentException(
            "Не найден формат WiGLE CSV: нужны столбцы MAC, CurrentLatitude и CurrentLongitude."
        )
        val headers = rows[headerIndex].mapIndexed { i, value ->
            value.trim().removePrefix("\uFEFF").uppercase(Locale.ROOT) to i
        }.toMap()
        fun field(row: List<String>, key: String) =
            headers[key]?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()

        var importedObservations = 0
        var newRadios = 0
        var validRows = 0
        val touched = LinkedHashSet<String>()
        val db = writableDatabase
        val sourceFingerprint = "wigle-csv:" + sha256(decoded)
        val importTime = System.currentTimeMillis()
        var sourceId = 0L
        db.beginTransaction()
        try {
            // Failed imports roll back this invalidation together with source/row changes.
            clearAllRadioDeletionUndo(db)
            sourceId = createOrGetImportedSource(db, "wigle", "WiGLE CSV", sourceFingerprint, importTime)
            rows.drop(headerIndex + 1).forEach { row ->
                val rawIdentifier = field(row, "MAC")
                if (rawIdentifier.isBlank()) return@forEach
                val typeHint = (field(row, "TYPE") + " " + field(row, "AUTHMODE")).uppercase(Locale.ROOT)
                val type = wigleTypeToRadioType(typeHint)
                val identifier = canonicalIdentifier(rawIdentifier)
                if (identifier.isBlank() || identifier.length > 256) return@forEach

                val latText = field(row, "CURRENTLATITUDE")
                val lonText = field(row, "CURRENTLONGITUDE")
                val lat = latText.toDoubleOrNull()
                val lon = lonText.toDoubleOrNull()
                val hasNoCoordinate = latText.isBlank() && lonText.isBlank()
                if (!hasNoCoordinate && !validCoordinates(lat, lon)) return@forEach

                val rssi = field(row, "RSSI").toIntOrNull()?.coerceIn(-120, 0) ?: -100
                val frequency = field(row, "FREQUENCY").toIntOrNull()?.coerceAtLeast(0) ?: 0
                val accuracy = field(row, "ACCURACYMETERS").toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(100_000.0)
                    ?: DEFAULT_IMPORTED_ACCURACY_METERS
                val ssid = field(row, "SSID").take(100)
                val now = System.currentTimeMillis()
                val observedAt = parseWigleTimestamp(field(row, "FIRSTSEEN")) ?: now
                val coordinateLat = if (hasNoCoordinate) null else lat
                val coordinateLon = if (hasNoCoordinate) null else lon
                validRows++
                touched.add(type + "|" + identifier)

                val newObservation = insertObservationIfAbsent(
                    db, type, identifier, observedAt, coordinateLat, coordinateLon,
                    if (coordinateLat != null) accuracy else null, rssi, frequency, "wigle-import", sourceId
                )
                // A stale/manual deletion may have removed the summary row while leaving history.
                // Re-create that summary on a duplicate import, but don't increment sightings twice.
                val summaryMissing = findPoint(db, type, identifier) == null
                if (newObservation || summaryMissing) {
                    if (upsertImportedPoint(
                            db, type, identifier, ssid, coordinateLat, coordinateLon,
                            if (coordinateLat != null) accuracy else null, rssi, frequency, observedAt, "wigle-import", sourceId
                        )
                    ) newRadios++
                }
                if (newObservation) importedObservations++
            }
            if (validRows == 0) throw IllegalArgumentException("В CSV нет корректных строк с MAC/BSSID.")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pruneToObservationLimit(observationRetentionLimit())
        return RadioImportResult(importedObservations, touched.size, newRadios, "WiGLE CSV", sourceId)
    }

    /**
     * Accepts a Clear GPS backup or a native WiGLE SQLite database (network/location).
     * Source content is only read; importing merges records and never replaces local data.
     */
    fun importSqliteDatabase(input: InputStream): RadioImportResult {
        val tempFile = File.createTempFile("clear-gps-import-", ".sqlite", appContext.cacheDir)
        try {
            input.use { source ->
                FileOutputStream(tempFile).use { target -> copyBounded(source, target, MAX_SQLITE_IMPORT_BYTES) }
            }
            val sourceFingerprint = "sqlite:" + sha256File(tempFile)
            val sourceDb = try {
                SQLiteDatabase.openDatabase(
                    tempFile.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            } catch (error: Exception) {
                throw IllegalArgumentException("Файл не является корректной SQLite-базой.", error)
            }
            try {
                val integrity = try {
                    sourceDb.rawQuery("PRAGMA quick_check", null).use {
                        if (it.moveToFirst()) it.getString(0) else "no result"
                    }
                } catch (error: Exception) {
                    throw IllegalArgumentException("SQLite-база повреждена или не читается.", error)
                }
                if (!integrity.equals("ok", ignoreCase = true)) {
                    throw IllegalArgumentException("SQLite-база повреждена: " + integrity)
                }
                val tables = tableNames(sourceDb)
                val imported = when {
                    setOf("radio_points", "radio_observations", "scan_history").all { it in tables } ->
                        importClearGpsSqlite(sourceDb, sourceFingerprint)
                    "network" in tables && "location" in tables ->
                        importWigleSqlite(sourceDb, sourceFingerprint)
                    else -> throw IllegalArgumentException(
                        "Неизвестная SQLite-схема. Поддерживаются Clear GPS и WiGLE (таблицы network/location)."
                    )
                }
                pruneToObservationLimit(observationRetentionLimit())
                return imported
            } finally {
                sourceDb.close()
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Exports a WiGLE-shaped SQLite database: one network summary per radio and
     * one location row per geolocated historical sighting. This is separate from
     * exportSqliteBackup(), which preserves Clear GPS-only metadata and all history.
     */
    fun exportWigleSqlite(output: OutputStream) {
        val tempFile = File.createTempFile("clear-gps-wigle-", ".sqlite", appContext.cacheDir)
        var exportDb: SQLiteDatabase? = null
        try {
            exportDb = SQLiteDatabase.openOrCreateDatabase(tempFile.absolutePath, null)
            exportDb.execSQL(
                "CREATE TABLE network (" +
                    "bssid TEXT NOT NULL, ssid TEXT NOT NULL DEFAULT '', " +
                    "frequency INTEGER NOT NULL DEFAULT 0, capabilities TEXT NOT NULL DEFAULT '', " +
                    "lasttime INTEGER NOT NULL DEFAULT 0, lastlat REAL NOT NULL DEFAULT 0, " +
                    "lastlon REAL NOT NULL DEFAULT 0, type TEXT NOT NULL DEFAULT 'WIFI', " +
                    "bestlevel INTEGER NOT NULL DEFAULT -100, bestlat REAL NOT NULL DEFAULT 0, " +
                    "bestlon REAL NOT NULL DEFAULT 0, PRIMARY KEY(bssid, type))"
            )
            exportDb.execSQL(
                "CREATE TABLE location (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, bssid TEXT NOT NULL, level INTEGER NOT NULL, " +
                    "lat REAL NOT NULL, lon REAL NOT NULL, altitude REAL NOT NULL DEFAULT 0, " +
                    "accuracy REAL NOT NULL DEFAULT 250, time INTEGER NOT NULL)"
            )
            exportDb.execSQL("CREATE INDEX network_bssid ON network(bssid)")
            exportDb.execSQL("CREATE INDEX location_bssid_time ON location(bssid, time)")

            val source = readableDatabase
            exportDb.beginTransaction()
            try {
                source.rawQuery("SELECT * FROM radio_points ORDER BY radio_type, identifier", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val point = cursor.toRadioPoint()
                        val type = when (point.type) {
                            "ble" -> "BTLE"
                            "cell" -> "BTS"
                            else -> "WIFI"
                        }
                        val lat = point.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
                        val lon = point.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
                        val located = lat != null && lon != null
                        val values = ContentValues().apply {
                            put("bssid", canonicalIdentifier(point.identifier))
                            put("ssid", point.ssid.ifBlank { point.label }.take(100))
                            put("frequency", point.frequency.coerceAtLeast(0))
                            put("capabilities", "")
                            put("lasttime", point.lastSeen)
                            put("lastlat", if (located) lat else 0.0)
                            put("lastlon", if (located) lon else 0.0)
                            put("type", type)
                            put("bestlevel", point.peakRssi.coerceIn(-120, 0))
                            put("bestlat", if (located) lat else 0.0)
                            put("bestlon", if (located) lon else 0.0)
                        }
                        exportDb.insertOrThrow("network", null, values)
                    }
                }

                source.rawQuery(
                    "SELECT radio_type, identifier, latitude, longitude, accuracy, rssi, frequency, observed_at " +
                        "FROM radio_observations ORDER BY observed_at, id",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val lat = cursor.doubleOrNull("latitude")
                        val lon = cursor.doubleOrNull("longitude")
                        if (!validCoordinates(lat, lon)) continue
                        val values = ContentValues().apply {
                            put("bssid", canonicalIdentifier(cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty()))
                            put("level", cursor.getInt(cursor.getColumnIndexOrThrow("rssi")).coerceIn(-120, 0))
                            put("lat", lat!!)
                            put("lon", lon!!)
                            put("altitude", 0.0)
                            put("accuracy", cursor.doubleOrNull("accuracy")?.takeIf { it > 0.0 } ?: DEFAULT_IMPORTED_ACCURACY_METERS)
                            put("time", cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")))
                        }
                        exportDb.insertOrThrow("location", null, values)
                    }
                }
                exportDb.setTransactionSuccessful()
            } finally {
                exportDb.endTransaction()
            }

            exportDb.close()
            exportDb = null
            FileInputStream(tempFile).use { sourceFile -> sourceFile.copyTo(output) }
            output.flush()
        } finally {
            try { exportDb?.close() } catch (_: Exception) { }
            tempFile.delete()
        }
    }

    /** Creates a standalone Clear GPS database backup using SQLite's own schema and data. */
    fun exportSqliteBackup(output: OutputStream) {
        val tempFile = File.createTempFile("clear-gps-export-", ".sqlite", appContext.cacheDir)
        var exportDb: SQLiteDatabase? = null
        try {
            exportDb = SQLiteDatabase.openOrCreateDatabase(tempFile.absolutePath, null)
            exportDb.execSQL("ATTACH DATABASE ? AS source", arrayOf(appContext.getDatabasePath(DB_NAME).absolutePath))
            val schema = exportDb.rawQuery(
                "SELECT type, name, sql FROM source.sqlite_master " +
                    "WHERE sql IS NOT NULL AND type IN ('table','index') " +
                    "AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' " +
                    "ORDER BY CASE WHEN type = 'table' THEN 0 ELSE 1 END, name",
                null
            ).use { cursor ->
                val result = ArrayList<Triple<String, String, String>>()
                while (cursor.moveToNext()) result.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                result
            }
            exportDb.beginTransaction()
            try {
                schema.filter { it.first == "table" }.forEach { (_, _, sql) -> exportDb.execSQL(sql) }
                schema.filter { it.first == "table" }.forEach { (_, name, _) ->
                    require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Недопустимое имя таблицы SQLite." }
                    exportDb.execSQL("INSERT INTO \"" + name + "\" SELECT * FROM source.\"" + name + "\"")
                }
                schema.filter { it.first == "index" }.forEach { (_, _, sql) -> exportDb.execSQL(sql) }
                exportDb.version = readableDatabase.version
                exportDb.setTransactionSuccessful()
            } finally {
                exportDb.endTransaction()
            }
            exportDb.execSQL("DETACH DATABASE source")
            exportDb.close()
            exportDb = null
            FileInputStream(tempFile).use { source -> source.copyTo(output) }
            output.flush()
        } finally {
            try { exportDb?.close() } catch (_: Exception) { }
            tempFile.delete()
        }
    }

    private data class WigleNetworkSummary(
        val type: String, val identifier: String, val ssid: String, val frequency: Int,
        val time: Long, val rssi: Int, val latitude: Double?, val longitude: Double?
    )

    private fun importClearGpsSqlite(sourceDb: SQLiteDatabase, fingerprint: String): RadioImportResult {
        val pointColumns = tableColumns(sourceDb, "radio_points")
        val observationColumns = tableColumns(sourceDb, "radio_observations")
        val historyColumns = tableColumns(sourceDb, "scan_history")
        val pointRequired = setOf(
            "radio_type", "identifier", "label", "latitude", "longitude", "accuracy",
            "last_rssi", "frequency", "first_seen", "last_seen", "sightings", "position_source"
        )
        val observationRequired = setOf(
            "radio_type", "identifier", "latitude", "longitude", "accuracy",
            "rssi", "frequency", "observed_at", "position_source"
        )
        if (!pointColumns.containsAll(pointRequired) || !observationColumns.containsAll(observationRequired) ||
            !historyColumns.containsAll(setOf("scanned_at", "wifi_count", "cell_count", "ble_count", "matched_count", "mode", "recorded"))) {
            throw IllegalArgumentException("SQLite-файл похож на Clear GPS, но структура таблиц неполная.")
        }

        val target = writableDatabase
        var observationsImported = 0
        var newRadios = 0
        val touched = LinkedHashSet<String>()
        var sourceId = 0L
        target.beginTransaction()
        try {
            clearAllRadioDeletionUndo(target)
            sourceId = createOrGetImportedSource(target, "custom", "Clear GPS SQLite", "clear-gps:" + fingerprint, System.currentTimeMillis())
            sourceDb.rawQuery("SELECT * FROM radio_points", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("radio_type")).orEmpty().lowercase(Locale.ROOT)
                    val identifier = canonicalIdentifier(cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty())
                    if (type !in VALID_TYPES || identifier.isBlank() || identifier.length > 256) continue
                    val label = cursor.getString(cursor.getColumnIndexOrThrow("label")).orEmpty()
                    val ssid = cursor.getColumnIndex("ssid").takeIf { it >= 0 }?.let { cursor.getString(it).orEmpty() }.orEmpty()
                    val notes = cursor.getColumnIndex("notes").takeIf { it >= 0 }?.let { cursor.getString(it).orEmpty() }.orEmpty()
                    val lat = cursor.doubleOrNull("latitude")
                    val lon = cursor.doubleOrNull("longitude")
                    val accuracy = cursor.doubleOrNull("accuracy")?.takeIf { it in 1.0..100_000.0 }
                    val rssi = cursor.getInt(cursor.getColumnIndexOrThrow("last_rssi")).coerceIn(-120, 0)
                    val peak = (cursor.getColumnIndex("peak_rssi").takeIf { it >= 0 }
                        ?.let { cursor.getInt(it) } ?: rssi).coerceIn(-120, 0)
                    val frequency = cursor.getInt(cursor.getColumnIndexOrThrow("frequency")).coerceAtLeast(0)
                    val firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")).coerceAtLeast(0L)
                    val lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen")).coerceAtLeast(firstSeen)
                    val sightings = cursor.getInt(cursor.getColumnIndexOrThrow("sightings")).coerceAtLeast(1)
                    val originalPositionSource = cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty()
                    val positionSource = if (originalPositionSource == "manual-edit") "imported-manual-edit" else originalPositionSource
                    if (upsertNativePoint(
                            target, type, identifier, label, ssid, notes, lat, lon, accuracy,
                            rssi, peak, frequency, firstSeen, lastSeen, sightings, positionSource, sourceId
                        )
                    ) newRadios++
                    touched.add(type + "|" + identifier)
                }
            }

            sourceDb.rawQuery("SELECT * FROM radio_observations ORDER BY observed_at, id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("radio_type")).orEmpty().lowercase(Locale.ROOT)
                    val identifier = canonicalIdentifier(cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty())
                    if (type !in VALID_TYPES || identifier.isBlank() || identifier.length > 256) continue
                    val lat = cursor.doubleOrNull("latitude")
                    val lon = cursor.doubleOrNull("longitude")
                    if ((lat == null) != (lon == null) || (lat != null && !validCoordinates(lat, lon))) continue
                    val observedAt = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at"))
                    if (observedAt <= 0L) continue
                    val accuracy = cursor.doubleOrNull("accuracy")?.takeIf { it in 1.0..100_000.0 }
                    val rssi = cursor.getInt(cursor.getColumnIndexOrThrow("rssi")).coerceIn(-120, 0)
                    val frequency = cursor.getInt(cursor.getColumnIndexOrThrow("frequency")).coerceAtLeast(0)
                    val source = cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty().take(100)
                    if (insertObservationIfAbsent(target, type, identifier, observedAt, lat, lon, accuracy, rssi, frequency, source, sourceId)) {
                        observationsImported++
                    }
                }
            }

            sourceDb.rawQuery("SELECT * FROM scan_history ORDER BY scanned_at, id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val scannedAt = cursor.getLong(cursor.getColumnIndexOrThrow("scanned_at"))
                    val wifiCount = cursor.getInt(cursor.getColumnIndexOrThrow("wifi_count"))
                    val cellCount = cursor.getInt(cursor.getColumnIndexOrThrow("cell_count"))
                    val bleCount = cursor.getInt(cursor.getColumnIndexOrThrow("ble_count"))
                    val matchedCount = cursor.getInt(cursor.getColumnIndexOrThrow("matched_count"))
                    val lat = cursor.doubleOrNull("latitude")
                    val lon = cursor.doubleOrNull("longitude")
                    val accuracy = cursor.doubleOrNull("accuracy")
                    val mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")).orEmpty()
                    val recorded = cursor.getInt(cursor.getColumnIndexOrThrow("recorded"))
                    val exists = target.rawQuery(
                        "SELECT 1 FROM scan_history WHERE scanned_at = ? AND wifi_count = ? AND cell_count = ? " +
                            "AND ble_count = ? AND matched_count = ? AND mode = ? AND recorded = ? LIMIT 1",
                        arrayOf(scannedAt.toString(), wifiCount.toString(), cellCount.toString(), bleCount.toString(),
                            matchedCount.toString(), mode, recorded.toString())
                    ).use { it.moveToFirst() }
                    if (!exists) {
                        val values = ContentValues().apply {
                            put("scanned_at", scannedAt); put("wifi_count", wifiCount); put("cell_count", cellCount)
                            put("ble_count", bleCount); put("matched_count", matchedCount)
                            put("latitude", lat); put("longitude", lon); put("accuracy", accuracy)
                            put("mode", mode); put("recorded", recorded)
                        }
                        target.insert("scan_history", null, values)
                    }
                }
            }
            target.execSQL("DELETE FROM scan_history WHERE id NOT IN (SELECT id FROM scan_history ORDER BY scanned_at DESC LIMIT 500)")
            target.setTransactionSuccessful()
        } finally {
            target.endTransaction()
        }
        return RadioImportResult(observationsImported, touched.size, newRadios, "SQLite Clear GPS", sourceId)
    }

    private fun importWigleSqlite(sourceDb: SQLiteDatabase, fingerprint: String): RadioImportResult {
        val networkColumns = tableColumns(sourceDb, "network")
        val locationColumns = tableColumns(sourceDb, "location")
        if (!networkColumns.containsAll(setOf("bssid", "ssid", "frequency")) ||
            !locationColumns.containsAll(setOf("_id", "bssid", "level", "lat", "lon", "accuracy", "time"))) {
            throw IllegalArgumentException("SQLite-файл WiGLE не содержит ожидаемые поля network/location.")
        }

        val summaries = LinkedHashMap<String, WigleNetworkSummary>()
        sourceDb.rawQuery("SELECT * FROM network", null).use { cursor ->
            while (cursor.moveToNext()) {
                val identifier = canonicalIdentifier(cursor.getString(cursor.getColumnIndexOrThrow("bssid")).orEmpty())
                if (identifier.isBlank() || identifier.length > 256) continue
                val rawType = cursor.getColumnIndex("type").takeIf { it >= 0 }?.let { cursor.getString(it).orEmpty() }.orEmpty()
                val type = wigleDatabaseTypeToRadioType(rawType)
                fun value(name: String): Double? = cursor.doubleOrNull(name)
                val bestLat = value("bestlat")
                val bestLon = value("bestlon")
                val lastLat = value("lastlat")
                val lastLon = value("lastlon")
                val pair = if (validCoordinates(bestLat, bestLon) && !(bestLat == 0.0 && bestLon == 0.0)) {
                    bestLat to bestLon
                } else if (validCoordinates(lastLat, lastLon) && !(lastLat == 0.0 && lastLon == 0.0)) {
                    lastLat to lastLon
                } else null
                val info = WigleNetworkSummary(
                    type, identifier, cursor.getString(cursor.getColumnIndexOrThrow("ssid")).orEmpty(),
                    cursor.getInt(cursor.getColumnIndexOrThrow("frequency")).coerceAtLeast(0),
                    cursor.getColumnIndex("lasttime").takeIf { it >= 0 }?.let { cursor.getLong(it) }?.takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                    cursor.getColumnIndex("bestlevel").takeIf { it >= 0 }?.let { cursor.getInt(it) }
                        ?.takeIf { it in -120..-1 } ?: -100,
                    pair?.first, pair?.second
                )
                summaries[type + "|" + identifier] = info
            }
        }

        val target = writableDatabase
        var observationsImported = 0
        var newRadios = 0
        var sourceId = 0L
        val touched = LinkedHashSet<String>()
        val seenKeys = HashSet<String>()
        target.beginTransaction()
        try {
            clearAllRadioDeletionUndo(target)
            sourceId = createOrGetImportedSource(target, "wigle", "WiGLE SQLite", "wigle-sqlite:" + fingerprint, System.currentTimeMillis())
            sourceDb.rawQuery("SELECT * FROM location ORDER BY time, _id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val identifier = canonicalIdentifier(cursor.getString(cursor.getColumnIndexOrThrow("bssid")).orEmpty())
                    if (identifier.isBlank() || identifier.length > 256) continue
                    val info = summaries.values.firstOrNull { it.identifier == identifier }
                    val type = info?.type ?: "wifi"
                    val lat = cursor.doubleOrNull("lat")
                    val lon = cursor.doubleOrNull("lon")
                    if ((lat == null) != (lon == null) || (lat != null && !validCoordinates(lat, lon))) continue
                    val time = cursor.getLong(cursor.getColumnIndexOrThrow("time"))
                    if (time <= 0L) continue
                    val rssi = cursor.getInt(cursor.getColumnIndexOrThrow("level")).coerceIn(-120, 0)
                    val accuracy = cursor.doubleOrNull("accuracy")?.takeIf { it > 0.0 }
                        ?.coerceAtMost(100_000.0) ?: DEFAULT_IMPORTED_ACCURACY_METERS
                    val frequency = (info?.frequency ?: 0).coerceIn(0, 100_000)
                    val key = type + "|" + identifier
                    seenKeys.add(key)
                    touched.add(key)
                    val newObservation = insertObservationIfAbsent(
                        target, type, identifier, time, lat, lon, if (lat != null) accuracy else null,
                        rssi, frequency, "wigle-import", sourceId
                    )
                    val summaryMissing = findPoint(target, type, identifier) == null
                    if (newObservation || summaryMissing) {
                        if (upsertImportedPoint(
                                target, type, identifier, info?.ssid.orEmpty(), lat, lon,
                                if (lat != null) accuracy else null, rssi, frequency, time, "wigle-import", sourceId
                            )
                        ) newRadios++
                    }
                    if (newObservation) observationsImported++
                }
            }

            summaries.forEach { (key, info) ->
                if (key in seenKeys) return@forEach
                if (upsertImportedPoint(
                        target, info.type, info.identifier, info.ssid, info.latitude, info.longitude,
                        DEFAULT_IMPORTED_ACCURACY_METERS, info.rssi, info.frequency, info.time, "wigle-import", sourceId,
                        incrementSightings = false
                    )
                ) newRadios++
                touched.add(key)
            }
            target.setTransactionSuccessful()
        } finally {
            target.endTransaction()
        }
        return RadioImportResult(observationsImported, touched.size, newRadios, "SQLite WiGLE", sourceId)
    }

    private fun upsertImportedPoint(
        db: SQLiteDatabase, type: String, identifier: String, label: String,
        latitude: Double?, longitude: Double?, accuracy: Double?,
        rssi: Int, frequency: Int, observedAt: Long, source: String, sourceId: Long,
        incrementSightings: Boolean = true
    ): Boolean {
        val validPosition = validCoordinates(latitude, longitude)
        val old = findPoint(db, type, identifier)
        if (old == null) {
            val values = ContentValues().apply {
                put("radio_type", type); put("identifier", identifier)
                put("label", label.take(100)); put("ssid", label.take(100))
                put("latitude", if (validPosition) latitude else null)
                put("longitude", if (validPosition) longitude else null)
                put("accuracy", if (validPosition) accuracy else null)
                put("last_rssi", rssi); put("peak_rssi", rssi); put("frequency", frequency)
                put("first_seen", observedAt); put("last_seen", observedAt)
                put("sightings", 1); put("position_source", if (validPosition) source else "unlocated")
                if (validPosition) {
                    put("source_id", sourceId)
                    put("position_observed_at", observedAt)
                }
            }
            val inserted = db.insertWithOnConflict("radio_points", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) return true
            return upsertImportedPoint(db, type, identifier, label, latitude, longitude, accuracy, rssi, frequency, observedAt, source, sourceId)
        }

        val update = ContentValues().apply {
            put("last_rssi", if (observedAt >= old.lastSeen) rssi else old.rssi)
            put("peak_rssi", maxOf(old.peakRssi, rssi))
            if (observedAt >= old.lastSeen && frequency > 0) put("frequency", frequency)
            put("first_seen", minOf(old.firstSeen, observedAt))
            put("last_seen", maxOf(old.lastSeen, observedAt))
            put(
                "sightings",
                if (!incrementSightings || old.sightings == Int.MAX_VALUE) old.sightings
                else old.sightings + 1
            )
            if (old.ssid.isBlank() && label.isNotBlank()) put("ssid", label.take(100))
            if (old.label.isBlank() && label.isNotBlank()) put("label", label.take(100))
            val oldAccuracy = old.accuracyMeters
            val improvesPosition = validPosition && old.positionSource != "manual-edit" &&
                (old.latitude == null || old.longitude == null ||
                    (source.startsWith("wigle") &&
                        (rssi > old.peakRssi || (accuracy != null && (oldAccuracy == null || accuracy < oldAccuracy)))))
            if (improvesPosition) {
                put("latitude", latitude); put("longitude", longitude); put("accuracy", accuracy)
                put("position_source", source); put("source_id", sourceId)
                put("position_observed_at", observedAt)
            }
        }
        db.update("radio_points", update, "id = ?", arrayOf(old.id.toString()))
        return false
    }

    private fun upsertNativePoint(
        db: SQLiteDatabase, type: String, identifier: String,
        label: String, ssid: String, notes: String,
        latitude: Double?, longitude: Double?, accuracy: Double?,
        rssi: Int, peakRssi: Int, frequency: Int, firstSeen: Long, lastSeen: Long,
        sightings: Int, positionSource: String, sourceId: Long
    ): Boolean {
        val old = findPoint(db, type, identifier)
        val validPosition = validCoordinates(latitude, longitude)
        if (old == null) {
            val values = ContentValues().apply {
                put("radio_type", type); put("identifier", identifier)
                put("label", label.take(100)); put("ssid", ssid.take(100)); put("notes", notes.take(4_000))
                put("latitude", if (validPosition) latitude else null)
                put("longitude", if (validPosition) longitude else null)
                put("accuracy", if (validPosition) accuracy else null)
                put("last_rssi", rssi); put("peak_rssi", peakRssi); put("frequency", frequency)
                put("first_seen", firstSeen); put("last_seen", lastSeen)
                put("sightings", sightings.coerceAtLeast(1)); put("position_source", positionSource)
                if (validPosition && positionSource != "manual-edit") {
                    put("source_id", sourceId)
                    put("position_observed_at", lastSeen)
                }
            }
            val inserted = db.insertWithOnConflict("radio_points", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) return true
            return upsertNativePoint(db, type, identifier, label, ssid, notes, latitude, longitude, accuracy,
                rssi, peakRssi, frequency, firstSeen, lastSeen, sightings, positionSource, sourceId)
        }

        val sourceHasManualFix = positionSource == "manual-edit"
        val shouldReplaceCoordinate = validPosition && old.positionSource != "manual-edit" &&
            (old.latitude == null || old.longitude == null || sourceHasManualFix ||
                (accuracy != null && (old.accuracyMeters == null || accuracy < old.accuracyMeters!!)))
        val values = ContentValues().apply {
            if (old.label.isBlank() && label.isNotBlank()) put("label", label.take(100))
            if (old.ssid.isBlank() && ssid.isNotBlank()) put("ssid", ssid.take(100))
            if (old.notes.isBlank() && notes.isNotBlank()) put("notes", notes.take(4_000))
            if (shouldReplaceCoordinate) {
                put("latitude", latitude); put("longitude", longitude); put("accuracy", accuracy)
                put("position_source", positionSource)
                if (positionSource != "manual-edit") {
                    put("source_id", sourceId)
                    put("position_observed_at", lastSeen)
                } else {
                    putNull("source_id"); putNull("position_observed_at")
                }
            }
            if (lastSeen >= old.lastSeen) {
                put("last_rssi", rssi); if (frequency > 0) put("frequency", frequency)
            }
            put("peak_rssi", maxOf(old.peakRssi, peakRssi))
            put("first_seen", minOf(old.firstSeen, firstSeen))
            put("last_seen", maxOf(old.lastSeen, lastSeen))
            put("sightings", maxOf(old.sightings, sightings))
        }
        db.update("radio_points", values, "id = ?", arrayOf(old.id.toString()))
        return false
    }

    private fun insertObservationIfAbsent(
        db: SQLiteDatabase, type: String, identifier: String, observedAt: Long,
        latitude: Double?, longitude: Double?, accuracy: Double?, rssi: Int, frequency: Int, positionSource: String,
        sourceId: Long
    ): Boolean {
        val exists = db.rawQuery(
            "SELECT 1 FROM radio_observations WHERE source_id = ? AND radio_type = ? AND identifier = ? AND observed_at = ? " +
                "AND rssi = ? AND frequency = ? " +
                "AND COALESCE(latitude, 999.0) = CAST(? AS REAL) " +
                "AND COALESCE(longitude, 999.0) = CAST(? AS REAL) LIMIT 1",
            arrayOf(
                sourceId.toString(), type, identifier, observedAt.toString(), rssi.toString(), frequency.toString(),
                latitude?.toString() ?: "999.0", longitude?.toString() ?: "999.0"
            )
        ).use { it.moveToFirst() }
        if (exists) return false
        val values = ContentValues().apply {
            put("radio_type", type); put("identifier", identifier)
            put("latitude", latitude); put("longitude", longitude); put("accuracy", accuracy)
            put("rssi", rssi); put("frequency", frequency); put("observed_at", observedAt)
            put("position_source", positionSource)
            put("source_id", sourceId)
        }
        return db.insert("radio_observations", null, values) != -1L
    }

    private fun canonicalIdentifier(value: String) = canonicalRadioIdentifier(value)

    private fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun wigleTypeToRadioType(value: String): String {
        val normalized = value.uppercase(Locale.ROOT)
        return when {
            normalized.contains("BTS") || normalized.contains("GSM") || normalized.contains("CELL") ||
                normalized.contains("CDMA") || normalized.contains("LTE") || normalized.contains("WCDMA") ||
                normalized.contains("UMTS") || normalized.contains("NR") -> "cell"
            normalized.contains("BTLE") || normalized.contains("BLUETOOTH") ||
                normalized.trim() == "BT" || normalized.trim() == "BLE" -> "ble"
            else -> "wifi"
        }
    }

    private fun wigleDatabaseTypeToRadioType(value: String): String {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return when (normalized) {
            "B", "BT", "BLE", "BTLE", "BLUETOOTH" -> "ble"
            "C", "G", "CELL", "BTS", "GSM", "CDMA", "LTE", "NR", "UMTS", "WCDMA" -> "cell"
            else -> if (normalized.contains("BT") || normalized.contains("BLE")) "ble" else "wifi"
        }
    }

    private fun parseWigleTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        return try { UTC_FORMAT.parse(value)?.time } catch (_: Exception) { null }
    }

    private fun tableNames(db: SQLiteDatabase): Set<String> {
        val names = LinkedHashSet<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Недопустимое имя таблицы SQLite." }
        val columns = LinkedHashSet<String>()
        db.rawQuery("PRAGMA table_info(\"" + table + "\")", null).use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")).lowercase(Locale.ROOT))
            }
        }
        return columns
    }

    private fun Cursor.doubleOrNull(column: String): Double? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getDouble(index).takeIf { it.isFinite() }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Int) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IllegalArgumentException(
                "SQLite-файл больше 128 МБ. Разбей базу на меньшие файлы и импортируй отдельно."
            )
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun findPoint(db: SQLiteDatabase, type: String, identifier: String): RadioPointRecord? {
        val canonical = canonicalIdentifier(identifier)
        val exact = db.rawQuery(
            "SELECT * FROM radio_points WHERE radio_type = ? AND lower(identifier) = ? LIMIT 1",
            arrayOf(type, canonical)
        ).use { if (it.moveToFirst()) it.toRadioPoint() else null }
        if (exact != null) return exact

        // Older imported databases may contain the same MAC in a compact/hyphen/dotted form.
        // Try a normalized MAC lookup before treating a known radio as unmatched.
        if (type != "wifi" && type != "ble") return null
        val compact = compactRadioMacIdentifier(identifier) ?: return null
        return db.rawQuery(
            "SELECT * FROM radio_points WHERE radio_type = ? " +
                "AND replace(replace(replace(lower(identifier), ':', ''), '-', ''), '.', '') = ? LIMIT 1",
            arrayOf(type, compact)
        ).use { if (it.moveToFirst()) it.toRadioPoint() else null }
    }

    private fun shouldReplacePosition(old: RadioPointRecord, reference: RadioReference, signal: RadioSignal): Boolean {
        // Do not move manually pinned coordinates or trusted imported WiGLE coordinates.
        if (old.positionSource == "manual-edit" || old.positionSource == "wigle-import") return false
        if (reference.accuracyMeters !in 1.0..MAX_SIGNAL_SAMPLE_ACCURACY_METERS) return false
        if (old.latitude == null || old.longitude == null) return true
        val accuracy = old.accuracyMeters
        if (accuracy != null && reference.accuracyMeters < accuracy * 0.8) return true
        return isBetterSignalSample(signal.rssi, old.peakRssi, reference.accuracyMeters)
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
        getInt(getColumnIndexOrThrow("peak_rssi")),
        getInt(getColumnIndexOrThrow("frequency")),
        getLong(getColumnIndexOrThrow("first_seen")),
        getLong(getColumnIndexOrThrow("last_seen")),
        getInt(getColumnIndexOrThrow("sightings")),
        getString(getColumnIndexOrThrow("position_source")).orEmpty(),
        if (getColumnIndex("source_id") >= 0 && !isNull(getColumnIndexOrThrow("source_id"))) getLong(getColumnIndexOrThrow("source_id")) else null,
        if (getColumnIndex("position_observed_at") >= 0 && !isNull(getColumnIndexOrThrow("position_observed_at"))) getLong(getColumnIndexOrThrow("position_observed_at")) else null
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
        private const val DB_VERSION = 9
        private const val RADIO_DELETION_UNDO_TTL_MS = 120_000L
        private val PROCESS_UNDO_SESSION_ID = UUID.randomUUID().toString()
        private const val DEFAULT_IMPORTED_ACCURACY_METERS = 250.0
        private const val MAX_SIGNAL_SAMPLE_ACCURACY_METERS = 50.0
        private const val MIN_SIGNAL_POINT_ACCURACY_METERS = 20.0
        private const val FINGERPRINT_HISTORY_AGE_MS = 90L * 24 * 60 * 60 * 1_000L
        private const val MAX_FINGERPRINT_KEYS = 80
        private const val MAX_FINGERPRINT_SCANS = 250
        private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
        private const val MAX_SQLITE_IMPORT_BYTES = 128 * 1024 * 1024
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

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

        private fun sha256File(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
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

        private fun frequencyToChannel(frequency: Int): Int = when {
            frequency in 2412..2472 -> (frequency - 2407) / 5
            frequency == 2484 -> 14
            frequency in 5000..5895 -> (frequency - 5000) / 5
            frequency in 5955..7115 -> (frequency - 5950) / 5
            else -> 0
        }

    }
}
