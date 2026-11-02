package org.cleargps.cell

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class CellTimingAdvanceEstimatorConfig(
    val minimumSpatialSamples: Int = 8,
    val spatialDeduplicationMeters: Double = 40.0,
    val maximumCoordinateAccuracyMeters: Double = 50.0,
    val maximumPositionDeltaMillis: Long = 1_000L,
    val maximumAssumedSpeedMetersPerSecond: Double = 70.0,
    val minimumOccupiedBearingBins: Int = 4,
    val bearingBinDegrees: Int = 45,
    val maximumMedianResidualMeters: Double = 125.0,
    val maximumNinetiethResidualMeters: Double = 250.0,
    val maximumUncertaintyMeters: Double = 500.0,
    val maximumIterations: Int = 30
) {
    init {
        require(minimumSpatialSamples >= 6)
        require(spatialDeduplicationMeters.isFinite() && spatialDeduplicationMeters in 5.0..500.0)
        require(maximumCoordinateAccuracyMeters.isFinite() && maximumCoordinateAccuracyMeters > 0.0)
        require(maximumPositionDeltaMillis in 0L..10_000L)
        require(maximumAssumedSpeedMetersPerSecond.isFinite() &&
            maximumAssumedSpeedMetersPerSecond in 1.0..100.0)
        require(bearingBinDegrees in 15..90 && 360 % bearingBinDegrees == 0)
        require(minimumOccupiedBearingBins in 3..(360 / bearingBinDegrees))
        require(maximumMedianResidualMeters.isFinite() && maximumMedianResidualMeters > 0.0)
        require(maximumNinetiethResidualMeters.isFinite() &&
            maximumNinetiethResidualMeters >= maximumMedianResidualMeters)
        require(maximumUncertaintyMeters.isFinite() && maximumUncertaintyMeters > 0.0)
        require(maximumIterations in 1..100)
    }
}

data class CellTimingAdvanceSiteEstimate(
    val identity: CellIdentity,
    val latitude: Double,
    val longitude: Double,
    val uncertaintyRadiusMeters: Double,
    val confidenceScore: Double,
    val sampleCount: Int,
    val occupiedBearingBins: Int,
    val medianAbsoluteResidualMeters: Double,
    val ninetiethResidualMeters: Double,
    val receiverProfileId: String,
    val frequencyKey: Int,
    val evidenceIds: List<String>,
    val algorithmVersion: String = "cell-ta-multilateration-1"
) {
    fun toCoordinateEstimate(): CellSiteCoordinateEstimate = CellSiteCoordinateEstimate(
        latitude = latitude,
        longitude = longitude,
        uncertaintyRadiusMeters = uncertaintyRadiusMeters,
        confidenceScore = confidenceScore,
        independentEvidenceCount = sampleCount,
        acceptedEvidenceIds = evidenceIds,
        rejectedEvidenceIds = emptyList(),
        ignoredCorrelatedEvidenceIds = emptyList(),
        conflictDetected = false,
        lockedByUser = false,
        algorithmVersion = algorithmVersion
    )
}

/**
 * Estimates one LTE cell's location from synchronized handset positions and LTE TA ranges.
 *
 * Only absolute LTE command indices are converted. GSM and NR require separate unit models.
 */
object CellTimingAdvanceSiteEstimator {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val HUBER_THRESHOLD = 1.5
    private const val CONFIDENCE_MINIMUM = 0.45

    private data class Measurement(
        val x: Double,
        val y: Double,
        val rangeMeters: Double,
        val sigmaMeters: Double,
        val evidenceId: String
    )

    private data class Fit(
        val x: Double,
        val y: Double,
        val h11: Double,
        val h12: Double,
        val h22: Double,
        val medianResidualMeters: Double,
        val ninetiethResidualMeters: Double,
        val occupiedBearingBins: Int
    )

    fun estimate(
        identity: CellIdentity,
        observations: List<CellObservationSample>,
        config: CellTimingAdvanceEstimatorConfig = CellTimingAdvanceEstimatorConfig()
    ): CellTimingAdvanceSiteEstimate? {
        if (identity.technology != CellTechnology.LTE || !identity.mncLengthKnown) return null

        val eligible = observations.asSequence()
            .filter { it.identity.stableKey == identity.stableKey }
            .filter {
                it.usableForTimingAdvance(
                    maximumCoordinateAccuracyMeters = config.maximumCoordinateAccuracyMeters,
                    maximumPositionDeltaMillis = config.maximumPositionDeltaMillis
                )
            }
            .groupBy { it.receiverProfileId!! to it.frequencyKey }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<String, Int>, List<CellObservationSample>>> { it.value.size }
                    .thenBy { it.key.first }
                    .thenBy { it.key.second }
            )

        for ((profileAndFrequency, group) in eligible) {
            val estimate = estimateForProfile(identity, group, profileAndFrequency, config)
            if (estimate != null) return estimate
        }
        return null
    }

    private fun estimateForProfile(
        identity: CellIdentity,
        samples: List<CellObservationSample>,
        profileAndFrequency: Pair<String, Int>,
        config: CellTimingAdvanceEstimatorConfig
    ): CellTimingAdvanceSiteEstimate? {
        if (samples.size < config.minimumSpatialSamples) return null

        val ordered = samples.sortedWith(
            compareBy<CellObservationSample> { it.coordinateAccuracyMeters }
                .thenBy { it.timingAdvancePositionDeltaMillis ?: Long.MAX_VALUE }
                .thenByDescending { it.observedAtMillis }
        )
        val originLatitude = ordered.map { it.latitude }.average()
        val originLongitude = circularMeanLongitude(ordered.map { it.longitude })
        val latitudeScale = cos(Math.toRadians(originLatitude))
        if (!originLatitude.isFinite() || abs(latitudeScale) < 0.05) return null

        fun localPoint(sample: CellObservationSample): Pair<Double, Double> {
            val longitudeDelta = normalizeLongitudeDelta(sample.longitude - originLongitude)
            val x = Math.toRadians(longitudeDelta) * EARTH_RADIUS_METERS * latitudeScale
            val y = Math.toRadians(sample.latitude - originLatitude) * EARTH_RADIUS_METERS
            return x to y
        }

        val measurements = ArrayList<Measurement>()
        val spatialBuckets = HashMap<Pair<Long, Long>, MutableList<Measurement>>()
        val bucketSize = config.spatialDeduplicationMeters
        ordered.forEach { sample ->
            val point = localPoint(sample)
            val bucketX = floor(point.first / bucketSize).toLong()
            val bucketY = floor(point.second / bucketSize).toLong()
            val duplicate = (-1L..1L).any { offsetX ->
                (-1L..1L).any { offsetY ->
                    spatialBuckets[bucketX + offsetX to bucketY + offsetY].orEmpty().any { existing ->
                        hypot(point.first - existing.x, point.second - existing.y) <
                            config.spatialDeduplicationMeters
                    }
                }
            }
            if (duplicate) return@forEach

            val range = CellTimingAdvanceRangePolicy.from(
                sample.timingAdvanceValue, sample.timingAdvanceUnit
            ) ?: return@forEach
            val deltaMillis = sample.timingAdvancePositionDeltaMillis ?: return@forEach
            val movementAllowance = config.maximumAssumedSpeedMetersPerSecond *
                (deltaMillis / 1_000.0)
            // GPS error, TA quantization and possible handset movement are added, not averaged away.
            val sigma = range.quantizationHalfWidthMeters +
                sample.coordinateAccuracyMeters + movementAllowance
            val evidenceId = buildString {
                append("lte-ta:")
                append(identity.stableKey)
                append(':')
                append(profileAndFrequency.first)
                append(':')
                append(profileAndFrequency.second)
                append(':')
                append(sample.observedAtMillis)
                append(':')
                append(sample.latitude)
                append(':')
                append(sample.longitude)
            }
            val measurement = Measurement(
                x = point.first,
                y = point.second,
                rangeMeters = range.nominalDistanceMeters,
                sigmaMeters = sigma.coerceAtLeast(1.0),
                evidenceId = evidenceId
            )
            measurements += measurement
            spatialBuckets.getOrPut(bucketX to bucketY) { ArrayList() }.add(measurement)
        }
        if (measurements.size < config.minimumSpatialSamples) return null

        val initial = linearInitialGuess(measurements) ?: return null
        val fit = robustGaussNewton(initial.first, initial.second, measurements, config) ?: return null
        if (fit.medianResidualMeters > config.maximumMedianResidualMeters ||
            fit.ninetiethResidualMeters > config.maximumNinetiethResidualMeters
        ) return null
        if (fit.occupiedBearingBins < config.minimumOccupiedBearingBins) return null

        val determinant = fit.h11 * fit.h22 - fit.h12 * fit.h12
        if (!determinant.isFinite() || determinant <= 1e-14) return null
        val covarianceXX = fit.h22 / determinant
        val covarianceXY = -fit.h12 / determinant
        val covarianceYY = fit.h11 / determinant
        val covarianceTrace = covarianceXX + covarianceYY
        val covarianceDiscriminant = sqrt(
            ((covarianceXX - covarianceYY) * (covarianceXX - covarianceYY) +
                4.0 * covarianceXY * covarianceXY).coerceAtLeast(0.0)
        )
        val largestVariance = (covarianceTrace + covarianceDiscriminant) / 2.0
        val smallestVariance = (covarianceTrace - covarianceDiscriminant) / 2.0
        if (!largestVariance.isFinite() || smallestVariance <= 0.0) return null
        val geometryRatio = smallestVariance / largestVariance
        if (geometryRatio < 0.08) return null

        val uncertainty = max(
            2.45 * sqrt(largestVariance),
            fit.medianResidualMeters * 1.4826
        )
        if (!uncertainty.isFinite() || uncertainty > config.maximumUncertaintyMeters) return null

        val confidence = (
            0.35 * (fit.occupiedBearingBins.toDouble() / (360 / config.bearingBinDegrees)) +
                0.25 * (measurements.size / 12.0).coerceAtMost(1.0) +
                0.20 * (1.0 - fit.medianResidualMeters / config.maximumMedianResidualMeters).coerceIn(0.0, 1.0) +
                0.20 * (1.0 - uncertainty / config.maximumUncertaintyMeters).coerceIn(0.0, 1.0)
            ).coerceIn(0.0, 1.0)
        if (confidence < CONFIDENCE_MINIMUM) return null

        val latitude = originLatitude + Math.toDegrees(fit.y / EARTH_RADIUS_METERS)
        val longitude = normalizeLongitude(
            originLongitude + Math.toDegrees(fit.x / (EARTH_RADIUS_METERS * latitudeScale))
        )
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) return null

        return CellTimingAdvanceSiteEstimate(
            identity = identity,
            latitude = latitude,
            longitude = longitude,
            uncertaintyRadiusMeters = uncertainty,
            confidenceScore = confidence,
            sampleCount = measurements.size,
            occupiedBearingBins = fit.occupiedBearingBins,
            medianAbsoluteResidualMeters = fit.medianResidualMeters,
            ninetiethResidualMeters = fit.ninetiethResidualMeters,
            receiverProfileId = profileAndFrequency.first,
            frequencyKey = profileAndFrequency.second,
            evidenceIds = measurements.map { it.evidenceId }
        )
    }

    private fun linearInitialGuess(measurements: List<Measurement>): Pair<Double, Double>? {
        val anchor = measurements.first()
        var h11 = 0.0
        var h12 = 0.0
        var h22 = 0.0
        var g1 = 0.0
        var g2 = 0.0
        val anchorSquared = anchor.x * anchor.x + anchor.y * anchor.y -
            anchor.rangeMeters * anchor.rangeMeters

        measurements.drop(1).forEach { sample ->
            val a = 2.0 * (sample.x - anchor.x)
            val b = 2.0 * (sample.y - anchor.y)
            val rhs = sample.x * sample.x + sample.y * sample.y -
                sample.rangeMeters * sample.rangeMeters - anchorSquared
            val variance = sample.sigmaMeters * sample.sigmaMeters +
                anchor.sigmaMeters * anchor.sigmaMeters
            val weight = 1.0 / variance.coerceAtLeast(1.0)
            h11 += weight * a * a
            h12 += weight * a * b
            h22 += weight * b * b
            g1 += weight * a * rhs
            g2 += weight * b * rhs
        }

        val determinant = h11 * h22 - h12 * h12
        if (!determinant.isFinite() || determinant <= 1e-12) return null
        val x = (g1 * h22 - g2 * h12) / determinant
        val y = (g2 * h11 - g1 * h12) / determinant
        return if (x.isFinite() && y.isFinite() && hypot(x, y) <= 150_000.0) x to y else null
    }

    private fun robustGaussNewton(
        initialX: Double,
        initialY: Double,
        measurements: List<Measurement>,
        config: CellTimingAdvanceEstimatorConfig
    ): Fit? {
        var x = initialX
        var y = initialY
        var h11 = 0.0
        var h12 = 0.0
        var h22 = 0.0

        for (iteration in 0 until config.maximumIterations) {
            h11 = 0.0
            h12 = 0.0
            h22 = 0.0
            var g1 = 0.0
            var g2 = 0.0
            measurements.forEach { sample ->
                val dx = x - sample.x
                val dy = y - sample.y
                val distance = hypot(dx, dy).coerceAtLeast(1.0)
                val jx = dx / distance
                val jy = dy / distance
                val residual = distance - sample.rangeMeters
                val normalized = abs(residual) / sample.sigmaMeters
                val robustWeight = if (normalized <= HUBER_THRESHOLD) 1.0
                    else HUBER_THRESHOLD / normalized
                val weight = robustWeight /
                    (sample.sigmaMeters * sample.sigmaMeters)
                h11 += weight * jx * jx
                h12 += weight * jx * jy
                h22 += weight * jy * jy
                g1 += weight * jx * residual
                g2 += weight * jy * residual
            }
            val determinant = h11 * h22 - h12 * h12
            if (!determinant.isFinite() || determinant <= 1e-14) return null
            val stepX = (g1 * h22 - g2 * h12) / determinant
            val stepY = (g2 * h11 - g1 * h12) / determinant
            if (!stepX.isFinite() || !stepY.isFinite()) return null
            x -= stepX
            y -= stepY
            if (!x.isFinite() || !y.isFinite() || hypot(x, y) > 150_000.0) return null
            if (hypot(stepX, stepY) < 0.1) break
        }

        val absoluteResiduals = measurements.map {
            abs(hypot(x - it.x, y - it.y) - it.rangeMeters)
        }.sorted()
        val median = percentile(absoluteResiduals, 0.50)
        val p90 = percentile(absoluteResiduals, 0.90)
        val bins = measurements.mapNotNull { sample ->
            val east = sample.x - x
            val north = sample.y - y
            if (hypot(east, north) < 1.0) null
            else {
                val bearing = ((Math.toDegrees(atan2(east, north)) % 360.0) + 360.0) % 360.0
                (bearing / config.bearingBinDegrees).toInt()
            }
        }.toSet().size

        return Fit(x, y, h11, h12, h22, median, p90, bins)
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return Double.POSITIVE_INFINITY
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun circularMeanLongitude(longitudes: List<Double>): Double {
        val x = longitudes.sumOf { cos(Math.toRadians(it)) }
        val y = longitudes.sumOf { sin(Math.toRadians(it)) }
        return normalizeLongitude(Math.toDegrees(atan2(y, x)))
    }

    private fun normalizeLongitudeDelta(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0

    private fun normalizeLongitude(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0
}
