package org.cleargps.cell

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class CellSiteEstimatorConfig(
    val minimumExternalUncertaintyMeters: Double = 25.0,
    val minimumUserUncertaintyMeters: Double = 5.0,
    val minimumOutlierDistanceMeters: Double = 100.0,
    val minimumIndependentEvidenceForOutlierRejection: Int = 4,
    val algorithmVersion: String = "cell-site-position-1"
) {
    init {
        require(minimumExternalUncertaintyMeters > 0.0 && minimumExternalUncertaintyMeters.isFinite())
        require(minimumUserUncertaintyMeters > 0.0 && minimumUserUncertaintyMeters.isFinite())
        require(minimumOutlierDistanceMeters > 0.0 && minimumOutlierDistanceMeters.isFinite())
        require(minimumIndependentEvidenceForOutlierRejection >= 4)
        require(algorithmVersion.isNotBlank())
    }
}

object CellSiteCoordinateEstimator {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun estimate(
        evidence: List<CellSiteCoordinateEvidence>,
        config: CellSiteEstimatorConfig = CellSiteEstimatorConfig()
    ): CellSiteCoordinateEstimate? {
        val valid = evidence.distinctBy { it.evidenceId }.filter {
            it.latitude.isFinite() && it.longitude.isFinite() && it.uncertaintyMeters.isFinite() &&
                it.uncertaintyMeters > 0.0 && it.reliability > 0.0
        }
        if (valid.isEmpty()) return null

        val locked = valid.filter { it.lockedByUser && it.source == CellEvidenceSource.USER_VERIFIED }
        if (locked.isNotEmpty()) {
            val anchor = locked.minBy { it.uncertaintyMeters }
            if (locked.any {
                distanceMeters(anchor.latitude, anchor.longitude, it.latitude, it.longitude) >
                    max(25.0, anchor.uncertaintyMeters + it.uncertaintyMeters)
            }) return null
            return CellSiteCoordinateEstimate(
                anchor.latitude, anchor.longitude, anchor.uncertaintyMeters, 1.0,
                locked.size, locked.map { it.evidenceId }, emptyList(), emptyList(),
                false, true, config.algorithmVersion
            )
        }

        val grouped = valid.groupBy { it.dependencyGroup?.takeIf(String::isNotBlank) ?: "id:" + it.evidenceId }
        val selected = grouped.values.mapNotNull { group ->
            group.maxByOrNull { weight(it, effectiveUncertainty(it, config)) }
        }
        if (selected.isEmpty()) return null
        val chosenIds = selected.map { it.evidenceId }.toSet()
        val correlated = grouped.values.filter { it.size > 1 }.flatten()
            .filterNot { it.evidenceId in chosenIds }.map { it.evidenceId }

        val origin = selected.first()
        fun unwrap(lon: Double) = origin.longitude + ((lon - origin.longitude + 540.0) % 360.0 - 180.0)
        val medianLat = median(selected.map { it.latitude })
        val medianLon = median(selected.map { unwrap(it.longitude) })
        val distances = selected.map { distanceMeters(medianLat, normalizeLongitude(medianLon), it.latitude, it.longitude) }
        val medianDistance = median(distances)
        val mad = median(distances.map { abs(it - medianDistance) })
        val medianUncertainty = median(selected.map { effectiveUncertainty(it, config) })
        val threshold = max(config.minimumOutlierDistanceMeters,
            max(medianDistance + 3.0 * 1.4826 * mad, medianUncertainty * 3.0))
        val retained = if (selected.size >= config.minimumIndependentEvidenceForOutlierRejection) {
            selected.filterIndexed { index, item ->
                distances[index] <= threshold ||
                    distances[index] <= 3.0 * (effectiveUncertainty(item, config) + medianUncertainty)
            }.takeIf { it.size >= max(3, (selected.size + 1) / 2) } ?: selected
        } else selected
        val center = sphericalCenter(retained, config) ?: return null
        val totalWeight = retained.sumOf { weight(it, effectiveUncertainty(it, config)) }
        if (!totalWeight.isFinite() || totalWeight <= 0.0) return null
        val variance = retained.sumOf {
            val d = distanceMeters(center.first, center.second, it.latitude, it.longitude)
            weight(it, effectiveUncertainty(it, config)) *
                (effectiveUncertainty(it, config).let { u -> u * u } + d * d)
        } / totalWeight
        val uncertainty = sqrt(variance).coerceAtLeast(retained.minOf { effectiveUncertainty(it, config) })
        val reliability = retained.map { it.reliability }.average()
        val dispersion = sqrt(retained.sumOf {
            val d = distanceMeters(center.first, center.second, it.latitude, it.longitude)
            weight(it, effectiveUncertainty(it, config)) * d * d
        } / totalWeight) / medianUncertainty.coerceAtLeast(1.0)
        val confidence = ((1.0 - exp(-0.45 * retained.size)) / (1.0 + dispersion) * reliability)
            .coerceIn(0.0, 1.0)
        val retainedIds = retained.map { it.evidenceId }.toSet()
        val rejected = selected.filterNot { it.evidenceId in retainedIds }.map { it.evidenceId }
        val conflict = rejected.isNotEmpty() || distances.any { it > max(config.minimumOutlierDistanceMeters, medianUncertainty * 3.0) }
        return CellSiteCoordinateEstimate(
            center.first, center.second, uncertainty, confidence, retained.size,
            retained.map { it.evidenceId }, rejected, correlated, conflict, false, config.algorithmVersion
        )
    }

    private fun effectiveUncertainty(e: CellSiteCoordinateEvidence, c: CellSiteEstimatorConfig) =
        max(e.uncertaintyMeters, if (e.source == CellEvidenceSource.USER_VERIFIED)
            c.minimumUserUncertaintyMeters else c.minimumExternalUncertaintyMeters)

    private fun weight(e: CellSiteCoordinateEvidence, uncertainty: Double) =
        e.reliability / (uncertainty * uncertainty)

    private fun sphericalCenter(
        items: List<CellSiteCoordinateEvidence>, config: CellSiteEstimatorConfig
    ): Pair<Double, Double>? {
        var x = 0.0; var y = 0.0; var z = 0.0
        items.forEach {
            val lat = Math.toRadians(it.latitude); val lon = Math.toRadians(it.longitude)
            val w = weight(it, effectiveUncertainty(it, config))
            x += w * cos(lat) * cos(lon); y += w * cos(lat) * sin(lon); z += w * sin(lat)
        }
        val horizontal = sqrt(x * x + y * y)
        if (x.isNaN() || y.isNaN() || z.isNaN() || (horizontal < 1e-15 && abs(z) < 1e-15)) return null
        return Math.toDegrees(atan2(z, horizontal)) to normalizeLongitude(Math.toDegrees(atan2(y, x)))
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(normalizeLongitude(lon2 - lon1))
        val a = (sin(dp / 2.0) * sin(dp / 2.0) +
            cos(p1) * cos(p2) * sin(dl / 2.0) * sin(dl / 2.0)).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted(); val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun normalizeLongitude(value: Double): Double {
        val lon = ((value + 540.0) % 360.0) - 180.0
        return if (lon == -180.0 && value > 0.0) 180.0 else lon
    }
}
