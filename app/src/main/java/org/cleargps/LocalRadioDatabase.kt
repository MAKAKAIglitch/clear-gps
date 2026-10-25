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
    val frequency: Int = 0, val ssid: String = ""
)

data class RadioPointRecord(
    val id: Long, val type: String, val identifier: String, val label: String, val ssid: String, val notes: String,
    val latitude: Double?, val longitude: Double?, val accuracyMeters: Double?,
    val rssi: Int, val peakRssi: Int, val frequency: Int, val firstSeen: Long, val lastSeen: Long,
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

data class RadioPointObservation(
    val id: Long,
    val observedAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val rssi: Int,
    val frequency: Int,
    val positionSource: String
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
    if (!horizontal.isFinite() || (horizontal < 1e-12 && kotlin.math.abs(z) < 1e-12)) return null
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
    val fromCache: Boolean, val message: String, val matchedCount: Int = 0, val recordedCount: Int = 0
)

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
                    exp(-kotlin.math.abs(current.rssi - old.rssi).toDouble() / 18.0)
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

    // If practically identical fingerprints exist at distant places, don't guess which
    // place is correct; this may happen in apartment blocks or with cloned SSIDs.
    val ambiguous = candidates.drop(1).any { candidate ->
        candidate.score >= best.score * 0.88 &&
            radioDistanceMeters(best.latitude, best.longitude, candidate.latitude, candidate.longitude) >
                clusterRadius(best, candidate)
    }
    if (ambiguous) return RadioFingerprintLookup(null, ambiguous = true)

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
    val accuracy = (sqrt(spreadSquared + fixErrorSquared) + sparsePenalty + matchPenalty).coerceIn(30.0, 20_000.0)

    return RadioFingerprintLookup(
        estimate = RadioEstimate(
            latitude = center.first,
            longitude = center.second,
            accuracyMeters = accuracy,
            updatedAt = now,
            fingerprint = radioSignalHash(best.matchedSignals),
            fallbackUsed = false,
            matchedCount = best.matchedSignals.size
        )
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
                age !in 0..(365L * 24 * 60 * 60 * 1_000L) ||
                point.positionSource.equals("local-radio-estimate", ignoreCase = true) ||
                point.positionSource.equals("radio-estimate", ignoreCase = true)) return@mapNotNull null
            val accuracy = (point.accuracyMeters ?: 250.0).takeIf { it.isFinite() && it > 0.0 }
                ?.coerceIn(20.0, 50_000.0) ?: 250.0
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
        matchedCount = inliers.size
    )
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
                last_rssi INTEGER NOT NULL DEFAULT -100, peak_rssi INTEGER NOT NULL DEFAULT -120, frequency INTEGER NOT NULL DEFAULT 0,
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE radio_points ADD COLUMN peak_rssi INTEGER NOT NULL DEFAULT -120")
            db.execSQL("UPDATE radio_points SET peak_rssi = last_rssi WHERE peak_rssi = -120")
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
                        if (reference != null && validReference(reference)) {
                            values.put("latitude", reference.latitude)
                            values.put("longitude", reference.longitude)
                            values.put("accuracy", reference.accuracyMeters)
                        }
                        db.insertOrThrow("radio_points", null, values)
                    } else {
                        if (reference != null && validReference(reference) &&
                            shouldReplacePosition(old, reference, signal)) {
                            val newPeakSample = isBetterSignalSample(signal.rssi, old.peakRssi, reference.accuracyMeters)
                            values.put("latitude", reference.latitude)
                            values.put("longitude", reference.longitude)
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
        val current = signals.asSequence()
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
        if (current.size < 2) return null

        val now = System.currentTimeMillis()
        // Prefer a past scan fingerprint: its coordinates are where the phone was,
        // not an average of the physical positions of nearby transmitters.
        val fingerprintLookup = lookupRadioFingerprintPosition(
            current,
            loadFingerprintSamples(current, now),
            now
        )
        if (fingerprintLookup.estimate != null) return fingerprintLookup.estimate
        // Do not bypass an explicit ambiguity decision by falling back to a weaker centroid.
        if (fingerprintLookup.ambiguous) return null

        // Fallback for locally imported WiGLE points or manually geolocated radios.
        val matches = ArrayList<Pair<RadioSignal, RadioPointRecord>>()
        val db = readableDatabase
        current.forEach { signal ->
            val point = findPoint(db, signal.type, signal.identifier)
            if (point != null && point.latitude != null && point.longitude != null &&
                point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                point.longitude.isFinite() && point.longitude in -180.0..180.0 &&
                (now - point.lastSeen) in 0..MAX_POINT_AGE_MS) {
                matches.add(signal to point)
            }
        }
        return estimateRadioAnchorPosition(matches, now)
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
        val keyPredicate = keys.joinToString(" OR ") { "(radio_type = ? AND identifier = ?)" }
        val timestampArguments = buildList {
            add(startTime.toString())
            add(now.toString())
            add("unlocated")
            add("local-radio-estimate")
            add("radio-estimate")
            keys.forEach { add(it.type); add(it.identifier) }
            add(MAX_FINGERPRINT_SCANS.toString())
        }.toTypedArray()
        val timestamps = readableDatabase.rawQuery(
            """
            SELECT observed_at
            FROM radio_observations
            WHERE observed_at >= ? AND observed_at <= ?
              AND latitude IS NOT NULL AND longitude IS NOT NULL
              AND position_source NOT IN (?, ?, ?)
              AND ($keyPredicate)
            GROUP BY observed_at
            ORDER BY COUNT(DISTINCT radio_type || ':' || identifier) DESC, observed_at DESC
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
            SELECT radio_type, identifier, latitude, longitude, accuracy, rssi, observed_at, position_source
            FROM radio_observations
            WHERE observed_at IN ($placeholders)
              AND latitude IS NOT NULL AND longitude IS NOT NULL
              AND position_source NOT IN (?, ?, ?)
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
                        identifier = cursor.getString(cursor.getColumnIndexOrThrow("identifier")).orEmpty(),
                        latitude = if (cursor.isNull(latIndex)) null else cursor.getDouble(latIndex),
                        longitude = if (cursor.isNull(lonIndex)) null else cursor.getDouble(lonIndex),
                        accuracyMeters = cursor.getDouble(cursor.getColumnIndexOrThrow("accuracy"))
                            .takeUnless { cursor.isNull(cursor.getColumnIndexOrThrow("accuracy")) },
                        rssi = cursor.getInt(cursor.getColumnIndexOrThrow("rssi")),
                        observedAt = cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")),
                        positionSource = cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty()
                    )
                )
            }
            result
        }
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
            "SELECT id, observed_at, latitude, longitude, accuracy, rssi, frequency, position_source " +
                "FROM radio_observations WHERE radio_type = ? AND identifier = ? " +
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
                    positionSource = cursor.getString(cursor.getColumnIndexOrThrow("position_source")).orEmpty()
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
                    put("last_rssi", rssi); put("peak_rssi", rssi); put("frequency", frequency)
                    put("first_seen", now); put("last_seen", now); put("sightings", 1)
                    put("position_source", "wigle-import")
                }
                val inserted = db.insertWithOnConflict("radio_points", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) {
                    val old = findPoint(db, type, identifier)
                    val update = ContentValues().apply {
                        put("last_rssi", rssi); put("peak_rssi", maxOf(old?.peakRssi ?: -120, rssi))
                        put("frequency", frequency); put("last_seen", now)
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
        private const val DB_VERSION = 3
        private const val DEFAULT_IMPORTED_ACCURACY_METERS = 250.0
        private const val MAX_SIGNAL_SAMPLE_ACCURACY_METERS = 50.0
        private const val MIN_SIGNAL_POINT_ACCURACY_METERS = 20.0
        private const val MAX_POINT_AGE_MS = 365L * 24 * 60 * 60 * 1_000L
        private const val FINGERPRINT_HISTORY_AGE_MS = 90L * 24 * 60 * 60 * 1_000L
        private const val MAX_FINGERPRINT_KEYS = 80
        private const val MAX_FINGERPRINT_SCANS = 250
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
