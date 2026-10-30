package org.cleargps

import java.security.MessageDigest
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts cached OpenCellID cell-position estimates into a deliberately coarse radio anchor.
 * Cell positions are community-derived estimates, not surveyed mast locations or handset fixes; uncertainty therefore has
 * a conservative 5 km floor, includes the reported cell range and grows with cache age.
 */
internal object OpenCellIdPositionEstimator {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_ACCURACY_METERS = 5_000.0

    fun estimate(
        evidence: List<Pair<RadioSignal, OpenCellIdCellRecord>>,
        nowMillis: Long = System.currentTimeMillis()
    ): RadioEstimate? {
        data class Anchor(
            val signal: RadioSignal,
            val record: OpenCellIdCellRecord,
            val latitude: Double,
            val longitude: Double,
            val uncertainty: Double,
            val weight: Double
        )

        val valid = evidence.mapNotNull { (signal, record) ->
            if (signal.type != "cell" || record.status != "found") return@mapNotNull null
            val latitude = record.latitude ?: return@mapNotNull null
            val longitude = record.longitude ?: return@mapNotNull null
            val age = nowMillis - record.checkedAtMillis
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0 ||
                !OpenCellIdLookupPolicy.shouldUseForPosition(record, nowMillis) || age < 0L
            ) return@mapNotNull null

            val range = record.rangeMeters?.toDouble()?.coerceIn(100.0, 100_000.0) ?: 4_000.0
            val agePenalty = age / 86_400_000.0 * 50.0
            val uncertainty = (max(MIN_ACCURACY_METERS, range * 1.25) + agePenalty)
                .coerceAtMost(100_000.0)
            val rssiWeight = (0.4 + (signal.rssi + 120).coerceIn(0, 80) / 100.0)
                .coerceIn(0.4, 1.2)
            val samplesWeight = sqrt((record.samples + 1).coerceIn(1, 10_000).toDouble())
                .coerceIn(1.0, 8.0)
            // OpenCellID marks changeable as deprecated and always 1; it is not a
            // reliable mobility signal and therefore must not change source confidence.
            val weight = rssiWeight * samplesWeight / uncertainty.pow(2.0)
            Anchor(signal, record, latitude, longitude, uncertainty, weight)
        }.distinctBy { it.record.key }.take(16)

        if (valid.isEmpty()) return null
        val totalWeight = valid.sumOf { it.weight }
        if (!totalWeight.isFinite() || totalWeight <= 0.0) return null

        var x = 0.0
        var y = 0.0
        var z = 0.0
        valid.forEach { anchor ->
            val lat = Math.toRadians(anchor.latitude)
            val lon = Math.toRadians(anchor.longitude)
            x += cos(lat) * cos(lon) * anchor.weight
            y += cos(lat) * sin(lon) * anchor.weight
            z += sin(lat) * anchor.weight
        }
        val norm = sqrt(x * x + y * y + z * z)
        if (!norm.isFinite() || norm < 1e-12) return null
        val latitude = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        val longitude = Math.toDegrees(atan2(y, x))
        val spreadSquared = valid.sumOf { anchor ->
            anchor.weight * distanceMeters(latitude, longitude, anchor.latitude, anchor.longitude).pow(2.0)
        } / totalWeight
        val averageRange = valid.sumOf {
            it.weight * (it.record.rangeMeters?.toDouble()?.coerceIn(100.0, 100_000.0) ?: 4_000.0)
        } / totalWeight
        val accuracy = max(
            max(MIN_ACCURACY_METERS, sqrt(1.0 / totalWeight)),
            sqrt(spreadSquared) + averageRange * 0.75
        ).coerceAtMost(100_000.0)

        val identifiers = valid.map { "cell|${it.signal.identifier}" }.toSet()
        // The radio evidence is from this current scan. Cache age is already reflected
        // in uncertainty above; using the lookup timestamp here would make a freshly observed
        // cell look stale simply because its catalogue entry was queried weeks ago.
        val sourceTime = nowMillis
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(valid.map { it.record.key.toString() }.sorted().joinToString("|").toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return RadioEstimate(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            updatedAt = sourceTime,
            fingerprint = "opencellid:$digest",
            fallbackUsed = true,
            matchedCount = valid.size,
            matchedRadioTypes = setOf("cell"),
            matchedRadioIdentifiers = identifiers,
            contributingSources = setOf("opencellid")
        )
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (sin(dLat / 2.0).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2.0).pow(2.0))
            .coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2.0 * kotlin.math.asin(sqrt(a))
    }
}

/** Fuses independent provider fixes without promoting an external tower coordinate to ground truth. */
internal object RadioPositionFusion {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    data class SourceEstimate(
        val source: String,
        val estimate: RadioEstimate,
        val trustWeight: Double
    )

    fun combine(sources: List<SourceEstimate>): RadioEstimate? {
        val valid = sources.filter { source ->
            val fix = source.estimate
            source.source.isNotBlank() &&
                source.trustWeight.isFinite() && source.trustWeight > 0.0 &&
                fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
                fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
                fix.accuracyMeters.isFinite() && fix.accuracyMeters > 0.0
        }.distinctBy { it.source }
        if (valid.isEmpty()) return null
        if (valid.size == 1) {
            return valid.single().estimate.copy(contributingSources = setOf(valid.single().source))
        }

        val weighted = valid.map { source ->
            val uncertainty = source.estimate.accuracyMeters.coerceIn(50.0, 100_000.0)
            source to (source.trustWeight.coerceIn(0.01, 1.0) / uncertainty.pow(2.0))
        }
        val totalWeight = weighted.sumOf { it.second }
        if (!totalWeight.isFinite() || totalWeight <= 0.0) return null
        val centre = sphericalCentre(weighted) ?: return null
        val maxPairDistance = valid.indices.maxOfOrNull { i ->
            (i + 1 until valid.size).maxOfOrNull { j ->
                distanceMeters(
                    valid[i].estimate.latitude, valid[i].estimate.longitude,
                    valid[j].estimate.latitude, valid[j].estimate.longitude
                )
            } ?: 0.0
        } ?: 0.0
        val conflict = valid.indices.any { i ->
            (i + 1 until valid.size).any { j ->
                val left = valid[i].estimate
                val right = valid[j].estimate
                val distance = distanceMeters(left.latitude, left.longitude, right.latitude, right.longitude)
                distance > max(10_000.0, 4.0 * sqrt(left.accuracyMeters.pow(2.0) + right.accuracyMeters.pow(2.0)))
            }
        }

        val selectedCentre = if (conflict) {
            weighted.maxBy { it.second }.first.estimate.let { it.latitude to it.longitude }
        } else centre
        val weightedSpread = sqrt(weighted.sumOf { (source, weight) ->
            weight * distanceMeters(
                selectedCentre.first, selectedCentre.second,
                source.estimate.latitude, source.estimate.longitude
            ).pow(2.0)
        } / totalWeight)
        val nominalAccuracy = sqrt(1.0 / totalWeight)
        val accuracy = if (conflict) {
            val bestAccuracy = weighted.maxBy { it.second }.first.estimate.accuracyMeters
            max(bestAccuracy, maxPairDistance / 2.0)
        } else {
            max(nominalAccuracy, weightedSpread)
        }.coerceIn(50.0, 100_000.0)

        val contributing = if (conflict) {
            listOf(weighted.maxBy { it.second }.first)
        } else valid
        val sourceNames = contributing.map { it.source }.sorted()
        val allTypes = contributing.flatMap { it.estimate.matchedRadioTypes }.toSet()
        val allIdentifiers = contributing.flatMap { it.estimate.matchedRadioIdentifiers }.toSet()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceNames.joinToString("+").toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        return RadioEstimate(
            latitude = selectedCentre.first,
            longitude = selectedCentre.second,
            accuracyMeters = accuracy,
            updatedAt = contributing.minOf { it.estimate.updatedAt },
            fingerprint = "radio-fusion:$digest",
            fallbackUsed = contributing.any { it.estimate.fallbackUsed } || contributing.size > 1,
            matchedCount = contributing.sumOf { it.estimate.matchedCount }.coerceAtMost(256),
            matchedRadioTypes = allTypes,
            matchedRadioIdentifiers = allIdentifiers,
            measurementElapsedRealtimeNanos = contributing.mapNotNull {
                it.estimate.measurementElapsedRealtimeNanos
            }.minOrNull(),
            contributingSources = sourceNames.toSet(),
            sourceConflictDetected = conflict
        )
    }

    private fun sphericalCentre(
        points: List<Pair<SourceEstimate, Double>>
    ): Pair<Double, Double>? {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        points.forEach { (source, weight) ->
            val lat = Math.toRadians(source.estimate.latitude)
            val lon = Math.toRadians(source.estimate.longitude)
            x += cos(lat) * cos(lon) * weight
            y += cos(lat) * sin(lon) * weight
            z += sin(lat) * weight
        }
        val norm = sqrt(x * x + y * y + z * z)
        if (!norm.isFinite() || norm < 1e-12) return null
        return Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to Math.toDegrees(atan2(y, x))
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = (kotlin.math.sin(dLat / 2.0).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2.0).pow(2.0))
            .coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2.0 * kotlin.math.asin(sqrt(a))
    }
}
