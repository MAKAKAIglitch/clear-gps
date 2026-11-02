package org.cleargps.cell

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class SectorAzimuthConfig(
    val minimumSpatialSamples: Int = 18,
    val angularBinDegrees: Int = 10,
    val minimumOccupiedBins: Int = 6,
    val spatialDeduplicationMeters: Double = 40.0,
    val maximumObservationAccuracyMeters: Double = 1_000.0,
    val maximumSiteUncertaintyMeters: Double = 1_000.0,
    val maximumModelSpreadDegrees: Double = 30.0,
    val minimumSignalContrastDb: Double = 3.0
) {
    init {
        require(minimumSpatialSamples >= 6)
        require(angularBinDegrees in 5..30 && 360 % angularBinDegrees == 0)
        require(minimumOccupiedBins >= 3)
        require(spatialDeduplicationMeters in 10.0..500.0)
    }
}

data class SectorAzimuthEstimate(
    val azimuthDegrees: Double,
    val widthDegrees: Double,
    val confidenceScore: Double,
    val sampleCount: Int,
    val occupiedBearingBins: Int,
    val signalContrastDb: Double,
    val modelDirectionSpreadDegrees: Double,
    val receiverProfileId: String?,
    val frequencyKeys: List<Int>
) {
    val suitableForMapDisplay: Boolean
        get() = receiverProfileId != null && confidenceScore >= 0.60 && sampleCount >= 18
}

object SectorAzimuthEstimator {
    private data class Located(val bearing: Double, val distance: Double, val rssi: Double)
    private data class Model(val azimuth: Double, val width: Double, val contrast: Double, val bins: Int)

    fun estimate(
        identity: CellIdentity,
        siteLatitude: Double,
        siteLongitude: Double,
        siteUncertaintyMeters: Double,
        observations: List<CellObservationSample>,
        config: SectorAzimuthConfig = SectorAzimuthConfig()
    ): SectorAzimuthEstimate? {
        if (!siteLatitude.isFinite() || siteLatitude !in -90.0..90.0 ||
            !siteLongitude.isFinite() || siteLongitude !in -180.0..180.0 ||
            !siteUncertaintyMeters.isFinite() || siteUncertaintyMeters < 0.0 ||
            siteUncertaintyMeters > config.maximumSiteUncertaintyMeters) return null

        val samples = observations.asSequence()
            .filter { it.identity.stableKey == identity.stableKey && it.usableForSector(config.maximumObservationAccuracyMeters) }
            .mapNotNull {
                val distance = CellSiteCoordinateEstimator.distanceMeters(
                    siteLatitude, siteLongitude, it.latitude, it.longitude)
                if (distance < max(100.0, 2.0 * (it.coordinateAccuracyMeters + siteUncertaintyMeters))) null
                else Located(bearing(siteLatitude, siteLongitude, it.latitude, it.longitude), distance, it.rssiDbm)
            }.toList()
        if (samples.size < config.minimumSpatialSamples) return null

        val grouped = observations.asSequence()
            .filter { it.identity.stableKey == identity.stableKey && it.usableForSector(config.maximumObservationAccuracyMeters) }
            .groupBy {
                (it.receiverProfileId?.takeIf(String::isNotBlank) ?: "__unknown__") to it.frequencyKey
            }
        val results = grouped.mapNotNull { (key, group) ->
            val located = group.mapNotNull {
                val d = CellSiteCoordinateEstimator.distanceMeters(siteLatitude, siteLongitude, it.latitude, it.longitude)
                if (d < max(100.0, 2.0 * (it.coordinateAccuracyMeters + siteUncertaintyMeters))) null
                else Located(bearing(siteLatitude, siteLongitude, it.latitude, it.longitude), d, it.rssiDbm)
            }
            val deduped = dedupe(located, siteLatitude, config.spatialDeduplicationMeters)
            if (deduped.size < config.minimumSpatialSamples) null
            else evaluate(deduped, config)?.let { key to it }
        }
        if (results.isEmpty()) return null
        val best = results.maxBy { it.second.confidence }
        if (results.any { angularDifference(best.second.azimuth, it.second.azimuth) > config.maximumModelSpreadDegrees }) return null
        val displayProfile = best.first.first.takeUnless { it == "__unknown__" }
        return SectorAzimuthEstimate(
            normalize(best.second.azimuth), best.second.width, best.second.confidence,
            best.second.sampleCount, best.second.bins, best.second.contrast,
            results.maxOf { angularDifference(best.second.azimuth, it.second.azimuth) },
            displayProfile, results.map { it.first.second }.filter { it > 0 }.distinct().sorted()
        )
    }

    private data class Evaluated(
        val azimuth: Double, val width: Double, val confidence: Double,
        val sampleCount: Int, val bins: Int, val contrast: Double
    )

    private fun evaluate(samples: List<Located>, config: SectorAzimuthConfig): Evaluated? {
        val binCount = 360 / config.angularBinDegrees
        val models = listOf(2.0, 2.5, 3.0, 3.5, 4.0).mapNotNull { exponent ->
            val bins = arrayOfNulls<MutableList<Double>>(binCount)
            samples.forEach {
                val bin = (normalize(it.bearing) / config.angularBinDegrees).toInt().coerceIn(0, binCount - 1)
                val adjusted = it.rssi + 10.0 * exponent * log10(it.distance / 100.0)
                bins[bin] = (bins[bin] ?: mutableListOf()).also { values -> values.add(adjusted) }
            }
            val values = bins.map { it?.average() }
            val occupied = values.count { it != null }
            if (occupied < config.minimumOccupiedBins) return@mapNotNull null
            val baseline = median(values.filterNotNull())
            val peak = values.filterNotNull().maxOrNull() ?: return@mapNotNull null
            val contrast = peak - baseline
            if (contrast < config.minimumSignalContrastDb) return@mapNotNull null
            val smoothed = values.indices.map { i ->
                listOf(values[(i - 1 + binCount) % binCount], values[i], values[(i + 1) % binCount])
                    .filterNotNull().takeIf { it.isNotEmpty() }?.average()
            }
            val peakBin = smoothed.indices.maxBy { smoothed[it] ?: Double.NEGATIVE_INFINITY }
            val widthBins = contiguousBins(peakBin, smoothed, peak - 4.0)
            val angles = widthBins.map { (it + 0.5) * config.angularBinDegrees }
            val weights = widthBins.map { 10.0.pow10(((smoothed[it] ?: baseline) - baseline).coerceIn(0.0, 20.0) / 10.0) }
            val x = angles.indices.sumOf { weights[it] * cos(Math.toRadians(angles[it])) }
            val y = angles.indices.sumOf { weights[it] * sin(Math.toRadians(angles[it])) }
            val az = normalize(Math.toDegrees(atan2(y, x)))
            Model(az, min(180.0, widthBins.size * config.angularBinDegrees.toDouble()), contrast, occupied)
        }
        if (models.size < 3) return null
        val best = models.maxBy { it.contrast }
        val spread = models.maxOf { angularDifference(best.azimuth, it.azimuth) }
        if (spread > config.maximumModelSpreadDegrees) return null
        val width = median(models.map { it.width })
        val binScore = (best.bins / 18.0).coerceIn(0.0, 1.0)
        val sampleScore = (samples.size / 60.0).coerceIn(0.0, 1.0)
        val contrastScore = ((best.contrast - config.minimumSignalContrastDb) / 12.0).coerceIn(0.0, 1.0)
        val stability = 1.0 - spread / config.maximumModelSpreadDegrees
        val confidence = (0.30 * sampleScore + 0.25 * binScore + 0.25 * contrastScore + 0.20 * stability)
            .coerceIn(0.0, 1.0)
        return Evaluated(best.azimuth, width, confidence, samples.size, best.bins, best.contrast)
    }

    private fun dedupe(samples: List<Located>, siteLat: Double, cellSize: Double): List<Located> =
        samples.groupBy {
            val a = Math.toRadians(it.bearing)
            val east = it.distance * sin(a); val north = it.distance * cos(a)
            floor(east / cellSize).toLong() to floor(north / cellSize).toLong()
        }.values.map { group ->
            Located(circularMean(group.map { it.bearing }), median(group.map { it.distance }), median(group.map { it.rssi }))
        }

    private fun contiguousBins(peak: Int, values: List<Double?>, threshold: Double): List<Int> {
        val included = linkedSetOf(peak)
        for (direction in listOf(-1, 1)) {
            var index = peak
            repeat(values.size / 2) {
                index = (index + direction + values.size) % values.size
                val value = values[index] ?: return@repeat
                if (value < threshold || index in included) return@repeat
                included.add(index)
            }
        }
        return included.toList()
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(((lon2 - lon1 + 540.0) % 360.0) - 180.0)
        return normalize(Math.toDegrees(atan2(sin(dl) * cos(p2),
            cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))))
    }

    private fun circularMean(values: List<Double>): Double {
        val x = values.sumOf { cos(Math.toRadians(it)) }
        val y = values.sumOf { sin(Math.toRadians(it)) }
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    private fun angularDifference(a: Double, b: Double): Double {
        val d = abs(normalize(a) - normalize(b)) % 360.0
        return min(d, 360.0 - d)
    }

    private fun normalize(value: Double) = ((value % 360.0) + 360.0) % 360.0
    private fun median(values: List<Double>): Double {
        val s = values.sorted(); val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
    }
    private fun log10(value: Double) = ln(value) / ln(10.0)
    private fun Double.pow10() = kotlin.math.pow(10.0, this)
}
