package org.cleargps

import kotlin.math.sqrt

/** Explicit vertical datums; "mean sea level" without a named realization is not comparable to EGM96. */
internal enum class AltitudeVerticalDatum {
    WGS84_ELLIPSOID,
    EGM96_GEOID,
    EGM2008_GEOID,
    MEAN_SEA_LEVEL_UNSPECIFIED,
    UNKNOWN
}

internal enum class AltitudeMeasurementSource {
    GNSS,
    DEM,
    BAROMETER,
    USER_REFERENCE
}

/**
 * A height observation whose uncertainty has already been normalized by its source adapter to a
 * comparable conservative one-sigma-like value in meters.
 *
 * For a DEM lookup, elapsedRealtimeNanos is the monotonic timestamp of the coordinate fix used
 * to sample the raster. For barometry, valueMeters must be calibrated to the stated datum using
 * an independently trusted reference; raw pressure or an uncalibrated relative change is not a
 * height observation in this contract.
 */
internal data class AltitudeObservation(
    val valueMeters: Double,
    val verticalDatum: AltitudeVerticalDatum,
    val uncertaintyMeters: Double?,
    val elapsedRealtimeNanos: Long,
    val source: AltitudeMeasurementSource
)

internal enum class AltitudeComparisonStatus {
    INSUFFICIENT_DATA,
    CONSISTENT,
    DISCREPANT
}

internal enum class AltitudeComparisonReason {
    MISSING_OBSERVATION,
    NON_FINITE_HEIGHT,
    UNKNOWN_VERTICAL_DATUM,
    VERTICAL_DATUM_MISMATCH,
    INVALID_TIMESTAMP,
    STALE_OBSERVATION,
    INVALID_UNCERTAINTY,
    WITHIN_TOLERANCE,
    OUTSIDE_TOLERANCE
}

internal data class AltitudeComparisonResult(
    val status: AltitudeComparisonStatus,
    val reason: AltitudeComparisonReason,
    val absoluteDifferenceMeters: Double? = null,
    val allowedDifferenceMeters: Double? = null
)

/**
 * Guardrails for comparing heights. These values must be selected from the DEM product metadata
 * and replay/field logs; the policy deliberately has no universal defaults.
 */
internal data class AltitudeConsistencyConfig(
    val maximumObservationAgeNanos: Long,
    val uncertaintyMultiplier: Double,
    val minimumToleranceMeters: Double,
    val minimumSustainedDiscrepancyNanos: Long,
    val maximumEvidenceGapNanos: Long
) {
    init {
        require(maximumObservationAgeNanos > 0L)
        require(uncertaintyMultiplier.isFinite() && uncertaintyMultiplier > 0.0)
        require(minimumToleranceMeters.isFinite() && minimumToleranceMeters >= 0.0)
        require(minimumSustainedDiscrepancyNanos > 0L)
        require(maximumEvidenceGapNanos > 0L)
    }
}

/**
 * Compares two elevations only when they share an explicitly compatible vertical datum, have
 * fresh monotonic timestamps, and both provide usable uncertainty estimates.
 *
 * The allowed difference is max(minimumTolerance, multiplier * sqrt(u1² + u2²)).
 * This is evidence evaluation only: it does not change TrustEngine score or mock publication.
 */
internal class AltitudeConsistencyPolicy(
    private val config: AltitudeConsistencyConfig
) {
    fun compare(
        first: AltitudeObservation?,
        second: AltitudeObservation?,
        nowElapsedRealtimeNanos: Long
    ): AltitudeComparisonResult {
        if (first == null || second == null) {
            return insufficient(AltitudeComparisonReason.MISSING_OBSERVATION)
        }
        if (!first.valueMeters.isFinite() || !second.valueMeters.isFinite()) {
            return insufficient(AltitudeComparisonReason.NON_FINITE_HEIGHT)
        }
        if (!isComparableDatum(first.verticalDatum) || !isComparableDatum(second.verticalDatum)) {
            return insufficient(AltitudeComparisonReason.UNKNOWN_VERTICAL_DATUM)
        }
        if (first.verticalDatum != second.verticalDatum) {
            return insufficient(AltitudeComparisonReason.VERTICAL_DATUM_MISMATCH)
        }
        if (nowElapsedRealtimeNanos <= 0L ||
            first.elapsedRealtimeNanos <= 0L ||
            second.elapsedRealtimeNanos <= 0L ||
            first.elapsedRealtimeNanos > nowElapsedRealtimeNanos ||
            second.elapsedRealtimeNanos > nowElapsedRealtimeNanos
        ) {
            return insufficient(AltitudeComparisonReason.INVALID_TIMESTAMP)
        }
        if (nowElapsedRealtimeNanos - first.elapsedRealtimeNanos > config.maximumObservationAgeNanos ||
            nowElapsedRealtimeNanos - second.elapsedRealtimeNanos > config.maximumObservationAgeNanos
        ) {
            return insufficient(AltitudeComparisonReason.STALE_OBSERVATION)
        }

        val firstUncertainty = first.uncertaintyMeters
        val secondUncertainty = second.uncertaintyMeters
        if (firstUncertainty == null || secondUncertainty == null ||
            !firstUncertainty.isFinite() || !secondUncertainty.isFinite() ||
            firstUncertainty < 0.0 || secondUncertainty < 0.0
        ) {
            return insufficient(AltitudeComparisonReason.INVALID_UNCERTAINTY)
        }

        val difference = kotlin.math.abs(first.valueMeters - second.valueMeters)
        if (!difference.isFinite()) {
            return insufficient(AltitudeComparisonReason.NON_FINITE_HEIGHT)
        }
        val combinedUncertainty = sqrt(
            firstUncertainty * firstUncertainty + secondUncertainty * secondUncertainty
        )
        val tolerance = maxOf(
            config.minimumToleranceMeters,
            config.uncertaintyMultiplier * combinedUncertainty
        )
        if (!tolerance.isFinite()) {
            return insufficient(AltitudeComparisonReason.INVALID_UNCERTAINTY)
        }
        return if (difference <= tolerance) {
            AltitudeComparisonResult(
                AltitudeComparisonStatus.CONSISTENT,
                AltitudeComparisonReason.WITHIN_TOLERANCE,
                difference,
                tolerance
            )
        } else {
            AltitudeComparisonResult(
                AltitudeComparisonStatus.DISCREPANT,
                AltitudeComparisonReason.OUTSIDE_TOLERANCE,
                difference,
                tolerance
            )
        }
    }

    private fun isComparableDatum(datum: AltitudeVerticalDatum): Boolean =
        datum == AltitudeVerticalDatum.WGS84_ELLIPSOID ||
            datum == AltitudeVerticalDatum.EGM96_GEOID ||
            datum == AltitudeVerticalDatum.EGM2008_GEOID

    private fun insufficient(reason: AltitudeComparisonReason) =
        AltitudeComparisonResult(
            status = AltitudeComparisonStatus.INSUFFICIENT_DATA,
            reason = reason
        )
}

internal data class SustainedAltitudeDiscrepancyState(
    val active: Boolean,
    val sustained: Boolean,
    val durationNanos: Long
)

/**
 * Requires continuous, fresh disagreement before exposing a sustained mismatch.
 * A good/insufficient sample, a backwards clock, or a long observation gap resets the window.
 */
internal class SustainedAltitudeDiscrepancyTracker(
    private val config: AltitudeConsistencyConfig
) {
    private var startedAtNanos: Long? = null
    private var lastEvidenceAtNanos: Long? = null

    @Synchronized
    fun update(
        comparison: AltitudeComparisonResult,
        nowElapsedRealtimeNanos: Long
    ): SustainedAltitudeDiscrepancyState {
        if (nowElapsedRealtimeNanos <= 0L) return reset()
        val last = lastEvidenceAtNanos
        if (last != null && nowElapsedRealtimeNanos < last) return reset()
        if (comparison.status != AltitudeComparisonStatus.DISCREPANT) return reset()

        if (last != null &&
            nowElapsedRealtimeNanos - last > config.maximumEvidenceGapNanos
        ) {
            startedAtNanos = nowElapsedRealtimeNanos
        } else if (startedAtNanos == null) {
            startedAtNanos = nowElapsedRealtimeNanos
        }

        lastEvidenceAtNanos = nowElapsedRealtimeNanos
        val duration = (nowElapsedRealtimeNanos - (startedAtNanos ?: nowElapsedRealtimeNanos))
            .coerceAtLeast(0L)
        return SustainedAltitudeDiscrepancyState(
            active = true,
            sustained = duration >= config.minimumSustainedDiscrepancyNanos,
            durationNanos = duration
        )
    }

    @Synchronized
    fun reset(): SustainedAltitudeDiscrepancyState {
        startedAtNanos = null
        lastEvidenceAtNanos = null
        return SustainedAltitudeDiscrepancyState(
            active = false,
            sustained = false,
            durationNanos = 0L
        )
    }
}
