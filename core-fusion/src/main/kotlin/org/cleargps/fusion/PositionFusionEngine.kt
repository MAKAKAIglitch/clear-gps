package org.cleargps.fusion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Independent absolute position measurements accepted by the first fusion-core iteration. */
enum class PositionMeasurementSource {
    GNSS,
    RADIO
}

/**
 * Position measurements use WGS84 coordinates and a conservative, source-normalized horizontal
 * uncertainty in metres. Android Location.accuracy must be normalized by an adapter before use.
 */
data class PositionMeasurement(
    val source: PositionMeasurementSource,
    val latitude: Double,
    val longitude: Double,
    val horizontalUncertaintyMeters: Double,
    val elapsedRealtimeNanos: Long
)

enum class PositionFusionUpdateStatus {
    INITIALIZED,
    UPDATED,
    REINITIALIZED_AFTER_GAP,
    REANCHORED,
    REJECTED
}

enum class PositionFusionRejectionReason {
    INVALID_COORDINATES,
    INVALID_UNCERTAINTY,
    INVALID_TIMESTAMP,
    OUT_OF_ORDER_TIMESTAMP,
    STATISTICAL_OUTLIER,
    NUMERIC_FAILURE
}

data class PositionFusionUpdateResult(
    val status: PositionFusionUpdateStatus,
    val rejectionReason: PositionFusionRejectionReason? = null,
    val estimate: FusedPosition? = null
)

/**
 * An ephemeral estimate. elapsedRealtimeNanos is prediction time; lastMeasurementElapsedRealtimeNanos
 * remains the timestamp of the most recently accepted absolute measurement.
 */
data class FusedPosition(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
    val speedMetersPerSecond: Double,
    val bearingDegrees: Double?,
    val elapsedRealtimeNanos: Long,
    val lastMeasurementElapsedRealtimeNanos: Long,
    val acceptedMeasurementCount: Int,
    val sourcesUsed: Set<PositionMeasurementSource>
)

data class PositionFusionConfig(
    val accelerationProcessNoiseStdMps2: Double = 2.0,
    val minimumMeasurementUncertaintyMeters: Double = 3.0,
    val maximumMeasurementUncertaintyMeters: Double = 5_000.0,
    val initialVelocityVariance: Double = 100.0,
    val maximumInnovationMahalanobisSquared: Double = 25.0,
    val maximumPredictionAgeNanos: Long = 2_000_000_000L,
    val maximumMeasurementGapNanos: Long = 15_000_000_000L,
    val reanchorDistanceMeters: Double = 20_000.0
) {
    init {
        require(accelerationProcessNoiseStdMps2.isFinite() && accelerationProcessNoiseStdMps2 >= 0.0)
        require(minimumMeasurementUncertaintyMeters.isFinite() && minimumMeasurementUncertaintyMeters > 0.0)
        require(maximumMeasurementUncertaintyMeters.isFinite() &&
            maximumMeasurementUncertaintyMeters >= minimumMeasurementUncertaintyMeters)
        require(initialVelocityVariance.isFinite() && initialVelocityVariance > 0.0)
        require(maximumInnovationMahalanobisSquared.isFinite() && maximumInnovationMahalanobisSquared > 0.0)
        require(maximumPredictionAgeNanos > 0L)
        require(maximumMeasurementGapNanos > 0L)
        require(reanchorDistanceMeters.isFinite() && reanchorDistanceMeters >= 1_000.0)
    }
}

/**
 * Small-area 2D constant-velocity Kalman foundation for combining independent position fixes.
 *
 * The motion model assumes unknown acceleration and expresses it as process noise; it does not
 * integrate accelerometer samples. A long gap starts a new segment and a travelled local frame is
 * reanchored, avoiding an unbounded flat-earth coordinate frame. Callers must gate measurement
 * trust separately and must not treat the output as a spoofing score.
 */
class PositionFusionEngine(
    private val config: PositionFusionConfig = PositionFusionConfig()
) {
    private var originLatitude: Double? = null
    private var originLongitude: Double? = null
    private var state: DoubleArray? = null // east, north, east velocity, north velocity
    private var covariance: Array<DoubleArray>? = null
    private var stateElapsedRealtimeNanos: Long = 0L
    private var acceptedMeasurementCount: Int = 0
    private val acceptedSources = linkedSetOf<PositionMeasurementSource>()

    fun reset() {
        originLatitude = null
        originLongitude = null
        state = null
        covariance = null
        stateElapsedRealtimeNanos = 0L
        acceptedMeasurementCount = 0
        acceptedSources.clear()
    }

    fun update(measurement: PositionMeasurement): PositionFusionUpdateResult {
        validate(measurement)?.let { return rejected(it) }

        if (state == null || covariance == null || originLatitude == null || originLongitude == null) {
            initialize(measurement)
            return acceptedResult(PositionFusionUpdateStatus.INITIALIZED, measurement.elapsedRealtimeNanos)
        }

        if (measurement.elapsedRealtimeNanos <= stateElapsedRealtimeNanos) {
            return rejected(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP)
        }

        val gap = measurement.elapsedRealtimeNanos - stateElapsedRealtimeNanos
        if (gap > config.maximumMeasurementGapNanos) {
            initialize(measurement)
            return acceptedResult(
                PositionFusionUpdateStatus.REINITIALIZED_AFTER_GAP,
                measurement.elapsedRealtimeNanos
            )
        }

        val currentState = state!!.copyOf()
        val currentCovariance = copyMatrix(covariance!!)
        val predicted = predictState(
            currentState,
            currentCovariance,
            gap.toDouble() / NANOS_PER_SECOND
        )
        val localMeasurement = toLocalMeters(measurement.latitude, measurement.longitude)
        if (!localMeasurement.first.isFinite() || !localMeasurement.second.isFinite()) {
            return rejected(PositionFusionRejectionReason.NUMERIC_FAILURE)
        }

        val uncertainty = max(
            config.minimumMeasurementUncertaintyMeters,
            measurement.horizontalUncertaintyMeters
        )
        val measurementVariance = uncertainty * uncertainty
        val innovationEast = localMeasurement.first - predicted.first[0]
        val innovationNorth = localMeasurement.second - predicted.first[1]
        val s00 = predicted.second[0][0] + measurementVariance
        val s01 = predicted.second[0][1]
        val s11 = predicted.second[1][1] + measurementVariance
        val determinant = s00 * s11 - s01 * s01
        if (!determinant.isFinite() || determinant <= MINIMUM_DETERMINANT) {
            return rejected(PositionFusionRejectionReason.NUMERIC_FAILURE)
        }

        val mahalanobisSquared = (
            s11 * innovationEast * innovationEast -
                2.0 * s01 * innovationEast * innovationNorth +
                s00 * innovationNorth * innovationNorth
            ) / determinant
        if (!mahalanobisSquared.isFinite()) {
            return rejected(PositionFusionRejectionReason.NUMERIC_FAILURE)
        }
        if (mahalanobisSquared > config.maximumInnovationMahalanobisSquared) {
            // A rejected sample must not advance the filter clock or pollute its state.
            return rejected(PositionFusionRejectionReason.STATISTICAL_OUTLIER)
        }

        val inverseS00 = s11 / determinant
        val inverseS01 = -s01 / determinant
        val inverseS11 = s00 / determinant
        val gain = Array(STATE_SIZE) { row ->
            doubleArrayOf(
                predicted.second[row][0] * inverseS00 + predicted.second[row][1] * inverseS01,
                predicted.second[row][0] * inverseS01 + predicted.second[row][1] * inverseS11
            )
        }
        val updatedState = predicted.first.copyOf()
        for (row in 0 until STATE_SIZE) {
            updatedState[row] += gain[row][0] * innovationEast + gain[row][1] * innovationNorth
        }

        // Joseph form is more numerically stable than P = (I - KH)P.
        val identityMinusKh = identityMatrix(STATE_SIZE)
        for (row in 0 until STATE_SIZE) {
            identityMinusKh[row][0] -= gain[row][0]
            identityMinusKh[row][1] -= gain[row][1]
        }
        val ap = multiply(identityMinusKh, predicted.second)
        val updatedCovariance = multiply(ap, transpose(identityMinusKh))
        for (row in 0 until STATE_SIZE) {
            for (column in 0 until STATE_SIZE) {
                updatedCovariance[row][column] += measurementVariance *
                    (gain[row][0] * gain[column][0] + gain[row][1] * gain[column][1])
            }
        }
        stabilizeCovariance(updatedCovariance)
        if (updatedState.any { !it.isFinite() } ||
            updatedCovariance.any { row -> row.any { !it.isFinite() } }
        ) {
            return rejected(PositionFusionRejectionReason.NUMERIC_FAILURE)
        }

        state = updatedState
        covariance = updatedCovariance
        stateElapsedRealtimeNanos = measurement.elapsedRealtimeNanos
        acceptedMeasurementCount += 1
        acceptedSources += measurement.source

        val needsReanchor = hypot(updatedState[0], updatedState[1]) >= config.reanchorDistanceMeters
        if (needsReanchor) {
            val newOrigin = toCoordinates(updatedState[0], updatedState[1])
            originLatitude = newOrigin.first
            originLongitude = newOrigin.second
            updatedState[0] = 0.0
            updatedState[1] = 0.0
            state = updatedState
            return acceptedResult(PositionFusionUpdateStatus.REANCHORED, measurement.elapsedRealtimeNanos)
        }

        return acceptedResult(PositionFusionUpdateStatus.UPDATED, measurement.elapsedRealtimeNanos)
    }

    /**
     * Predict only briefly between measurements. No result is returned for future-dated or stale
     * requests, so a caller cannot keep publishing a frozen last-known location forever.
     */
    fun estimate(nowElapsedRealtimeNanos: Long): FusedPosition? {
        val currentState = state ?: return null
        val currentCovariance = covariance ?: return null
        val latitude = originLatitude ?: return null
        val longitude = originLongitude ?: return null
        if (nowElapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos < stateElapsedRealtimeNanos) return null
        val age = nowElapsedRealtimeNanos - stateElapsedRealtimeNanos
        if (age > config.maximumPredictionAgeNanos) return null

        val predicted = predictState(
            currentState.copyOf(),
            copyMatrix(currentCovariance),
            age.toDouble() / NANOS_PER_SECOND
        )
        val positionAccuracy = sqrt(
            max(0.0, predicted.second[0][0]) + max(0.0, predicted.second[1][1])
        )
        if (!positionAccuracy.isFinite()) return null

        val geographic = toCoordinates(predicted.first[0], predicted.first[1], latitude, longitude)
        val speed = hypot(predicted.first[2], predicted.first[3])
        if (!geographic.first.isFinite() || !geographic.second.isFinite() || !speed.isFinite()) return null
        val bearing = if (speed < MIN_SPEED_FOR_BEARING_MPS) {
            null
        } else {
            normalizeBearingDegrees(Math.toDegrees(atan2(predicted.first[2], predicted.first[3])))
        }
        return FusedPosition(
            latitude = geographic.first,
            longitude = geographic.second,
            horizontalAccuracyMeters = positionAccuracy,
            speedMetersPerSecond = speed,
            bearingDegrees = bearing,
            elapsedRealtimeNanos = nowElapsedRealtimeNanos,
            lastMeasurementElapsedRealtimeNanos = stateElapsedRealtimeNanos,
            acceptedMeasurementCount = acceptedMeasurementCount,
            sourcesUsed = acceptedSources.toSet()
        )
    }

    private fun validate(measurement: PositionMeasurement): PositionFusionRejectionReason? {
        if (!measurement.latitude.isFinite() || measurement.latitude !in -90.0..90.0 ||
            !measurement.longitude.isFinite() || measurement.longitude !in -180.0..180.0
        ) return PositionFusionRejectionReason.INVALID_COORDINATES
        if (!measurement.horizontalUncertaintyMeters.isFinite() ||
            measurement.horizontalUncertaintyMeters <= 0.0 ||
            measurement.horizontalUncertaintyMeters > config.maximumMeasurementUncertaintyMeters
        ) return PositionFusionRejectionReason.INVALID_UNCERTAINTY
        if (measurement.elapsedRealtimeNanos <= 0L) return PositionFusionRejectionReason.INVALID_TIMESTAMP
        return null
    }

    private fun initialize(measurement: PositionMeasurement) {
        originLatitude = measurement.latitude
        originLongitude = measurement.longitude
        val uncertainty = max(
            config.minimumMeasurementUncertaintyMeters,
            measurement.horizontalUncertaintyMeters
        )
        val positionVariance = uncertainty * uncertainty
        val initialCovariance = Array(STATE_SIZE) { DoubleArray(STATE_SIZE) }
        initialCovariance[0][0] = positionVariance
        initialCovariance[1][1] = positionVariance
        initialCovariance[2][2] = config.initialVelocityVariance
        initialCovariance[3][3] = config.initialVelocityVariance
        state = DoubleArray(STATE_SIZE)
        covariance = initialCovariance
        stateElapsedRealtimeNanos = measurement.elapsedRealtimeNanos
        acceptedMeasurementCount = 1
        acceptedSources.clear()
        acceptedSources += measurement.source
    }

    private fun toLocalMeters(latitude: Double, longitude: Double): Pair<Double, Double> {
        val originLat = Math.toRadians(originLatitude ?: return Double.NaN to Double.NaN)
        val originLon = originLongitude ?: return Double.NaN to Double.NaN
        val cosLat = cos(originLat)
        val east = EARTH_RADIUS_METERS * cosLat *
            Math.toRadians(normalizeLongitudeDegrees(longitude - originLon))
        val north = EARTH_RADIUS_METERS * Math.toRadians(latitude - Math.toDegrees(originLat))
        return east to north
    }

    private fun toCoordinates(
        eastMeters: Double,
        northMeters: Double,
        latitudeOrigin: Double = originLatitude ?: Double.NaN,
        longitudeOrigin: Double = originLongitude ?: Double.NaN
    ): Pair<Double, Double> {
        val originLatRadians = Math.toRadians(latitudeOrigin)
        val latitude = latitudeOrigin + Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
        val longitudeScale = cos(originLatRadians).let { if (abs(it) < 1e-6) 1e-6 else it }
        val longitude = longitudeOrigin +
            Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * longitudeScale))
        return latitude.coerceIn(-90.0, 90.0) to normalizeLongitudeDegrees(longitude)
    }

    private fun predictState(
        sourceState: DoubleArray,
        sourceCovariance: Array<DoubleArray>,
        dtSeconds: Double
    ): Pair<DoubleArray, Array<DoubleArray>> {
        val dt = dtSeconds.coerceAtLeast(0.0)
        val transition = identityMatrix(STATE_SIZE).apply {
            this[0][2] = dt
            this[1][3] = dt
        }
        val predictedState = sourceState.copyOf().apply {
            this[0] += sourceState[2] * dt
            this[1] += sourceState[3] * dt
        }
        val predictedCovariance = multiply(
            multiply(transition, sourceCovariance),
            transpose(transition)
        )
        val accelerationVariance = config.accelerationProcessNoiseStdMps2 *
            config.accelerationProcessNoiseStdMps2
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        val positionNoise = accelerationVariance * dt4 / 4.0
        val crossNoise = accelerationVariance * dt3 / 2.0
        val velocityNoise = accelerationVariance * dt2
        predictedCovariance[0][0] += positionNoise
        predictedCovariance[0][2] += crossNoise
        predictedCovariance[2][0] += crossNoise
        predictedCovariance[2][2] += velocityNoise
        predictedCovariance[1][1] += positionNoise
        predictedCovariance[1][3] += crossNoise
        predictedCovariance[3][1] += crossNoise
        predictedCovariance[3][3] += velocityNoise
        stabilizeCovariance(predictedCovariance)
        return predictedState to predictedCovariance
    }

    private fun acceptedResult(
        status: PositionFusionUpdateStatus,
        timestamp: Long
    ) = PositionFusionUpdateResult(status = status, estimate = estimate(timestamp))

    private fun rejected(reason: PositionFusionRejectionReason) =
        PositionFusionUpdateResult(
            status = PositionFusionUpdateStatus.REJECTED,
            rejectionReason = reason
        )

    private fun stabilizeCovariance(matrix: Array<DoubleArray>) {
        for (row in 0 until STATE_SIZE) {
            matrix[row][row] = max(matrix[row][row], MINIMUM_VARIANCE)
            for (column in row + 1 until STATE_SIZE) {
                val symmetric = (matrix[row][column] + matrix[column][row]) / 2.0
                matrix[row][column] = symmetric
                matrix[column][row] = symmetric
            }
        }
    }

    private fun identityMatrix(size: Int) = Array(size) { row ->
        DoubleArray(size) { column -> if (row == column) 1.0 else 0.0 }
    }

    private fun copyMatrix(source: Array<DoubleArray>) = Array(source.size) { source[it].copyOf() }

    private fun transpose(source: Array<DoubleArray>) = Array(source[0].size) { row ->
        DoubleArray(source.size) { column -> source[column][row] }
    }

    private fun multiply(left: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> {
        val rows = left.size
        val columns = right[0].size
        val shared = right.size
        return Array(rows) { row ->
            DoubleArray(columns) { column ->
                var sum = 0.0
                for (index in 0 until shared) sum += left[row][index] * right[index][column]
                sum
            }
        }
    }

    private companion object {
        const val STATE_SIZE = 4
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MINIMUM_DETERMINANT = 1e-12
        const val MINIMUM_VARIANCE = 1e-9
        const val MIN_SPEED_FOR_BEARING_MPS = 0.5

        fun normalizeLongitudeDegrees(value: Double): Double {
            val shifted = (value + 180.0) % 360.0
            return (if (shifted < 0.0) shifted + 360.0 else shifted) - 180.0
        }

        fun normalizeBearingDegrees(value: Double): Double {
            val shifted = value % 360.0
            return if (shifted < 0.0) shifted + 360.0 else shifted
        }
    }
}
