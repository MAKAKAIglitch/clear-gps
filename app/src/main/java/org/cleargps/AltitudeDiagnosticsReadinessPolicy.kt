package org.cleargps

import android.location.Location
import android.os.Build

/**
 * Readiness diagnostics for the first live GNSS/DEM/barometer connection.
 *
 * This policy deliberately does not calculate a height discrepancy or spoofing score. SRTM HGT
 * does not carry source-normalized vertical uncertainty in the current importer, Android's MSL
 * field does not identify a geoid realization in this contract, and raw pressure is not height.
 * Those gaps must be resolved before calling AltitudeConsistencyPolicy or the sustained tracker.
 */
internal enum class AltitudeComparisonReadiness {
    GNSS_HEIGHT_UNAVAILABLE,
    DEM_HEIGHT_UNAVAILABLE,
    INVALID_MONOTONIC_TIMESTAMP,
    STALE_OBSERVATION,
    UNKNOWN_VERTICAL_DATUM,
    VERTICAL_DATUM_MISMATCH,
    GNSS_UNCERTAINTY_UNAVAILABLE,
    DEM_UNCERTAINTY_UNAVAILABLE,
    READY_FOR_POLICY
}

internal enum class BarometerAltitudeReadiness {
    NO_FRESH_PRESSURE,
    RAW_PRESSURE_ONLY
}

/** Contains no coordinates, elevation values, or raw pressure. Kept in process memory only. */
internal data class AltitudeDiagnosticsSnapshot(
    val comparisonReadiness: AltitudeComparisonReadiness,
    val gnssDatum: AltitudeVerticalDatum?,
    val demDatum: AltitudeVerticalDatum?,
    val barometerReadiness: BarometerAltitudeReadiness,
    val evaluatedAtElapsedRealtimeNanos: Long
)

internal object AltitudeDiagnosticsReadinessPolicy {
    const val MAXIMUM_OBSERVATION_AGE_NANOS = 30_000_000_000L
    const val MAXIMUM_SNAPSHOT_AGE_NANOS = 120_000_000_000L

    /** Prefer Android's MSL field when present, but do not guess which geoid realization it uses. */
    fun gnssObservation(location: Location): AltitudeObservation? {
        val timestamp = location.elapsedRealtimeNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            location.hasMslAltitude() &&
            location.mslAltitudeMeters.isFinite()
        ) {
            val uncertainty = if (location.hasMslAltitudeAccuracy() &&
                location.mslAltitudeAccuracyMeters.isFinite() &&
                location.mslAltitudeAccuracyMeters >= 0f
            ) {
                location.mslAltitudeAccuracyMeters.toDouble()
            } else {
                null
            }
            return AltitudeObservation(
                valueMeters = location.mslAltitudeMeters,
                verticalDatum = AltitudeVerticalDatum.MEAN_SEA_LEVEL_UNSPECIFIED,
                uncertaintyMeters = uncertainty,
                elapsedRealtimeNanos = timestamp,
                source = AltitudeMeasurementSource.GNSS
            )
        }

        if (!location.hasAltitude() || !location.altitude.isFinite()) return null
        val uncertainty = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            location.hasVerticalAccuracy() &&
            location.verticalAccuracyMeters.isFinite() &&
            location.verticalAccuracyMeters >= 0f
        ) {
            location.verticalAccuracyMeters.toDouble()
        } else {
            null
        }
        return AltitudeObservation(
            valueMeters = location.altitude,
            verticalDatum = AltitudeVerticalDatum.WGS84_ELLIPSOID,
            uncertaintyMeters = uncertainty,
            elapsedRealtimeNanos = timestamp,
            source = AltitudeMeasurementSource.GNSS
        )
    }

    /** HGT elevation is EGM96; source uncertainty remains null until the pack has quality metadata. */
    fun demObservation(
        sample: SrtmHgtElevationSample?,
        fixElapsedRealtimeNanos: Long
    ): AltitudeObservation? {
        if (sample == null || !sample.elevationMeters.isFinite()) return null
        return AltitudeObservation(
            valueMeters = sample.elevationMeters,
            verticalDatum = when (sample.verticalDatum) {
                SrtmVerticalDatum.EGM96_GEOID -> AltitudeVerticalDatum.EGM96_GEOID
            },
            uncertaintyMeters = null,
            elapsedRealtimeNanos = fixElapsedRealtimeNanos,
            source = AltitudeMeasurementSource.DEM
        )
    }

    fun assess(
        gnssObservation: AltitudeObservation?,
        demObservation: AltitudeObservation?,
        barometerPressureSample: BarometerPressureSample?,
        nowElapsedRealtimeNanos: Long
    ): AltitudeDiagnosticsSnapshot {
        val readiness = when {
            gnssObservation == null -> AltitudeComparisonReadiness.GNSS_HEIGHT_UNAVAILABLE
            demObservation == null -> AltitudeComparisonReadiness.DEM_HEIGHT_UNAVAILABLE
            nowElapsedRealtimeNanos <= 0L ||
                gnssObservation.elapsedRealtimeNanos <= 0L ||
                demObservation.elapsedRealtimeNanos <= 0L ||
                gnssObservation.elapsedRealtimeNanos > nowElapsedRealtimeNanos ||
                demObservation.elapsedRealtimeNanos > nowElapsedRealtimeNanos ->
                AltitudeComparisonReadiness.INVALID_MONOTONIC_TIMESTAMP
            nowElapsedRealtimeNanos - gnssObservation.elapsedRealtimeNanos >
                MAXIMUM_OBSERVATION_AGE_NANOS ||
                nowElapsedRealtimeNanos - demObservation.elapsedRealtimeNanos >
                MAXIMUM_OBSERVATION_AGE_NANOS ->
                AltitudeComparisonReadiness.STALE_OBSERVATION
            !isComparableDatum(gnssObservation.verticalDatum) ||
                !isComparableDatum(demObservation.verticalDatum) ->
                AltitudeComparisonReadiness.UNKNOWN_VERTICAL_DATUM
            gnssObservation.verticalDatum != demObservation.verticalDatum ->
                AltitudeComparisonReadiness.VERTICAL_DATUM_MISMATCH
            !hasValidUncertainty(gnssObservation.uncertaintyMeters) ->
                AltitudeComparisonReadiness.GNSS_UNCERTAINTY_UNAVAILABLE
            !hasValidUncertainty(demObservation.uncertaintyMeters) ->
                AltitudeComparisonReadiness.DEM_UNCERTAINTY_UNAVAILABLE
            else -> AltitudeComparisonReadiness.READY_FOR_POLICY
        }

        val freshPressure = BarometerPressureSamplePolicy.freshSample(
            sample = barometerPressureSample,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
        return AltitudeDiagnosticsSnapshot(
            comparisonReadiness = readiness,
            gnssDatum = gnssObservation?.verticalDatum,
            demDatum = demObservation?.verticalDatum,
            barometerReadiness = if (freshPressure == null) {
                BarometerAltitudeReadiness.NO_FRESH_PRESSURE
            } else {
                BarometerAltitudeReadiness.RAW_PRESSURE_ONLY
            },
            evaluatedAtElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
    }

    fun description(snapshot: AltitudeDiagnosticsSnapshot): String {
        val comparison = when (snapshot.comparisonReadiness) {
            AltitudeComparisonReadiness.GNSS_HEIGHT_UNAVAILABLE ->
                "Нет пригодной высоты GNSS"
            AltitudeComparisonReadiness.DEM_HEIGHT_UNAVAILABLE ->
                "Нет доступной локальной SRTM-высоты"
            AltitudeComparisonReadiness.INVALID_MONOTONIC_TIMESTAMP ->
                "Высотная сверка отклонена: некорректная монотонная метка времени"
            AltitudeComparisonReadiness.STALE_OBSERVATION ->
                "Высотная сверка отклонена: устаревшее измерение"
            AltitudeComparisonReadiness.UNKNOWN_VERTICAL_DATUM ->
                "Высотная сверка отложена: вертикальный датум не определён"
            AltitudeComparisonReadiness.VERTICAL_DATUM_MISMATCH ->
                "Высотная сверка отложена: GNSS и SRTM используют разные вертикальные датумы"
            AltitudeComparisonReadiness.GNSS_UNCERTAINTY_UNAVAILABLE ->
                "Высотная сверка отложена: нет точности высоты GNSS"
            AltitudeComparisonReadiness.DEM_UNCERTAINTY_UNAVAILABLE ->
                "Высотная сверка отложена: не задана погрешность DEM"
            AltitudeComparisonReadiness.READY_FOR_POLICY ->
                "Высотные данные прошли базовые проверки; оценка расхождения ещё не включена"
        }
        val barometer = when (snapshot.barometerReadiness) {
            BarometerAltitudeReadiness.NO_FRESH_PRESSURE ->
                "свежего давления барометра нет"
            BarometerAltitudeReadiness.RAW_PRESSURE_ONLY ->
                "свежее давление есть, но не откалибровано в высоту"
        }
        return "$comparison; $barometer. Этот диагностический этап не влияет на риск спуфинга."
    }

    private fun isComparableDatum(datum: AltitudeVerticalDatum): Boolean =
        datum == AltitudeVerticalDatum.WGS84_ELLIPSOID ||
            datum == AltitudeVerticalDatum.EGM96_GEOID ||
            datum == AltitudeVerticalDatum.EGM2008_GEOID

    private fun hasValidUncertainty(value: Double?): Boolean =
        value != null && value.isFinite() && value >= 0.0
}

/** Latest assessment is deliberately ephemeral; no raw sample or coordinate is persisted. */
internal object AltitudeDiagnosticsLiveStore {
    @Volatile
    private var latest: AltitudeDiagnosticsSnapshot? = null

    fun update(snapshot: AltitudeDiagnosticsSnapshot) {
        if (snapshot.evaluatedAtElapsedRealtimeNanos <= 0L) return
        latest = snapshot
    }

    fun snapshot(
        nowElapsedRealtimeNanos: Long,
        maxAgeNanos: Long = AltitudeDiagnosticsReadinessPolicy.MAXIMUM_SNAPSHOT_AGE_NANOS
    ): AltitudeDiagnosticsSnapshot? {
        if (nowElapsedRealtimeNanos <= 0L || maxAgeNanos < 0L) return null
        val current = latest ?: return null
        val age = nowElapsedRealtimeNanos - current.evaluatedAtElapsedRealtimeNanos
        return current.takeIf { age in 0L..maxAgeNanos }
    }

    fun clear() {
        latest = null
    }
}
