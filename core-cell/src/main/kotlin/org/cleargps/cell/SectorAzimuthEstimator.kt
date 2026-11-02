package org.cleargps.cell

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
        require(maximumObservationAccuracyMeters.isFinite() && maximumObservationAccuracyMeters > 0.0)
        require(maximumSiteUncertaintyMeters.isFinite() && maximumSiteUncertaintyMeters > 0.0)
        require(maximumModelSpreadDegrees.isFinite() && maximumModelSpreadDegrees in 1.0..90.0)
        require(minimumSignalContrastDb.isFinite() && minimumSignalContrastDb >= 0.0)
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
    private data class Evaluated(
        val azimuth: Double,
        val width: Double,
        val confidence: Double,
        val sampleCount: Int,
        val bins: Int,
        val contrast: Double
    )

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
            siteUncertaintyMeters > config.maximumSiteUncertaintyMeters
        ) return null

        val usable = observations.asSequence()
            .filter {
                it.identity.stableKey == identity.stableKey &&
                    it.usableForSector(config.maximumObservationAccuracyMeters)
            }
            .mapNotNull { observation ->
                val distance = CellSiteCoordinateEstimator.distanceMeters(
                    siteLatitude, siteLongitude, observation.latitude, observation.longitude
                )
                val minimumDistance = max(
                    100.0,
                    2.0 * (observation.coordinateAccuracyMeters + siteUncertaintyMeters)
                )
                if (distance < minimumDistance) null else Located(
                    bearing(siteLatitude, siteLongitude, observation.latitude, observation.longitude),
                    distance,
                    observation.rssiDbm
                )
            }.toList()

        if (usable.size < config.minimumSpatialSamples) return null

        // Compare models only within the same receiver profile and radio channel.
        val groups = observations.asSequence()
            .filter {
                it.identity.stableKey == identity.stableKey &&
                    it.usableForSector(config.maximumObservationAccuracyMeters)
            }
            .groupBy {
                (it.receiverProfileId?.takeIf(String::isNotBlank) ?: UNKNOWN_RECEIVER) to it.frequencyKey
            }

        val results = groups.mapNotNull { (key, group) ->
            val located = group.mapNotNull { observation ->
                val distance = CellSiteCoordinateEstimator.distanceMeters(
                    siteLatitude, siteLongitude, observation.latitude, observation.longitude
                )
                val minimumDistance = max(
                    100.0,
                    2.0 * (observation.coordinateAccuracyMeters + siteUncertaintyMeters)
                )
                if (distance < minimumDistance) null else Located(
                    bearing(siteLatitude, siteLongitude, observation.latitude, observation.longitude),
                    distance,
                    observation.rssiDbm
                )
            }
            val deduped = dedupe(located, config.spatialDeduplicationMeters)
            if (deduped.size < config.minimumSpatialSamples) null
            else evaluate(deduped, config)?.let { key to it }
        }

        if (results.isEmpty()) return null
        val best = results.maxBy { it.second.confidence }
        val directionSpread = results.maxOf {
            angularDifference(best.second.azimuth, it.second.azimuth)
        }
        if (directionSpread > config.maximumModelSpreadDegrees) return null

        val receiverProfile = best.first.first.takeUnless { it == UNKNOWN_RECEIVER }
        return SectorAzimuthEstimate(
            azimuthDegrees = normalize(best.second.azimuth),
            widthDegrees = best.second.width,
            confidenceScore = best.second.confidence,
            sampleCount = best.second.sampleCount,
            occupiedBearingBins = best.second.bins,
            signalContrastDb = best.second.contrast,
            modelDirectionSpreadDegrees = directionSpread,
            receiverProfileId = receiverProfile,
            frequencyKeys = results.map { it.first.second }.filter { it > 0 }.distinct().sorted()
        )
    }

    private fun evaluate(samples: List<Located>, config: SectorAzimuthConfig): Evaluated? {
        val binCount = 360 / config.angularBinDegrees
        val models = listOf(2.0, 2.5, 3.0, 3.5, 4.0).mapNotNull { exponent ->
            val bins = arrayOfNulls<MutableList<Double>>(binCount)
            samples.forEach { sample ->
                val bin = (normalize(sample.bearing) / config.angularBinDegrees)
                    .toInt().coerceIn(0, binCount - 1)
                val distanceCorrectedRssi =
                    sample.rssi + 10.0 * exponent * log10(sample.distance / 100.0)
                bins[bin] = (bins[bin] ?: mutableListOf()).also { it.add(distanceCorrectedRssi) }
            }

            val values = bins.map { it?.average() }
            val occupied = values.count { it != null }
            if (occupied < config.minimumOccupiedBins) return@mapNotNull null

            val baseline = median(values.filterNotNull())
            val peak = values.filterNotNull().maxOrNull() ?: return@mapNotNull null
            val contrast = peak - baseline
            if (contrast < config.minimumSignalContrastDb) return@mapNotNull null

            val smoothed = values.indices.map { index ->
                listOf(
                    values[(index - 1 + binCount) % binCount],
                    values[index],
                    values[(index + 1) % binCount]
                ).filterNotNull().takeIf { it.isNotEmpty() }?.average()
            }
            val peakBin = smoothed.indices.maxBy { smoothed[it] ?: Double.NEGATIVE_INFINITY }
            val widthBins = contiguousBins(peakBin, smoothed, peak - 4.0)
            val angles = widthBins.map { (it + 0.5) * config.angularBinDegrees }
            val weights = widthBins.map {
                10.0.pow(((smoothed[it] ?: baseline) - baseline).coerceIn(0.0, 20.0) / 10.0)
            }
            val x = angles.indices.sumOf { index ->
                weights[index] * cos(Math.toRadians(angles[index]))
            }
            val y = angles.indices.sumOf { index ->
                weights[index] * sin(Math.toRadians(angles[index]))
            }
            val azimuth = normalize(Math.toDegrees(atan2(y, x)))
            Model(
                azimuth,
                min(180.0, widthBins.size * config.angularBinDegrees.toDouble()),
                contrast,
                occupied
            )
        }

        if (models.size < 3) return null
        val best = models.maxBy { it.contrast }
        val spread = models.maxOf { angularDifference(best.azimuth, it.azimuth) }
        if (spread > config.maximumModelSpreadDegrees) return null

        val width = median(models.map { it.width })
        val binScore = (best.bins / 18.0).coerceIn(0.0, 1.0)
        val sampleScore = (samples.size / 60.0).coerceIn(0.0, 1.0)
        val contrastScore =
            ((best.contrast - config.minimumSignalContrastDb) / 12.0).coerceIn(0.0, 1.0)
        val stability = 1.0 - spread / config.maximumModelSpreadDegrees
        val confidence = (
            0.30 * sampleScore + 0.25 * binScore +
                0.25 * contrastScore + 0.20 * stability
            ).coerceIn(0.0, 1.0)

        return Evaluated(best.azimuth, width, confidence, samples.size, best.bins, best.contrast)
    }

    private fun dedupe(samples: List<Located>, cellSizeMeters: Double): List<Located> =
        samples.groupBy {
            val angle = Math.toRadians(it.bearing)
            val east = it.distance * sin(angle)
            val north = it.distance * cos(angle)
            floor(east / cellSizeMeters).toLong() to floor(north / cellSizeMeters).toLong()
        }.values.map { group ->
            Located(
                circularMean(group.map { it.bearing }),
                median(group.map { it.distance }),
                median(group.map { it.rssi })
            )
        }

    private fun contiguousBins(
        peak: Int,
        values: List<Double?>,
        threshold: Double
    ): List<Int> {
        val included = linkedSetOf(peak)
        for (direction in listOf(-1, 1)) {
            var index = peak
            var steps = 0
            while (steps < values.size / 2) {
                val next = (index + direction + values.size) % values.size
                if (next in included) break
                val value = values[next] ?: break
                if (value < threshold) break
                included.add(next)
                index = next
                steps++
            }
        }
        return included.toList()
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val deltaLongitude = Math.toRadians(((lon2 - lon1 + 540.0) % 360.0) - 180.0)
        return normalize(
            Math.toDegrees(
                atan2(
                    sin(deltaLongitude) * cos(p2),
                    cos(p1) * sin(p2) -
                        sin(p1) * cos(p2) * cos(deltaLongitude)
                )
            )
        )
    }

    private fun circularMean(values: List<Double>): Double {
        val x = values.sumOf { cos(Math.toRadians(it)) }
        val y = values.sumOf { sin(Math.toRadians(it)) }
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    private fun angularDifference(a: Double, b: Double): Double {
        val difference = abs(normalize(a) - normalize(b)) % 360.0
        return min(difference, 360.0 - difference)
    }

    private fun normalize(value: Double) = ((value % 360.0) + 360.0) % 360.0

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }

    private fun log10(value: Double) = ln(value) / ln(10.0)

    private const val UNKNOWN_RECEIVER = "__unknown__"
}
