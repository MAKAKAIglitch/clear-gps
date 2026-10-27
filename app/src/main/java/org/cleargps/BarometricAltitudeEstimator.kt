package org.cleargps

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One calibration made while a trusted altitude and pressure were observed together.
 * The altitude datum is inherited from that trusted reference, never guessed from pressure.
 */
internal data class BarometricAltitudeCalibration(
    val referencePressurePa: Double,
    val referencePressureUncertaintyPa: Double,
    val trustedAltitudeMeters: Double,
    val trustedAltitudeUncertaintyMeters: Double,
    val verticalDatum: AltitudeVerticalDatum,
    val calibratedAtElapsedRealtimeNanos: Long
)

internal data class BarometricAltitudeConfig(
    /** Must be chosen for the device/use case; pressure offset drifts with weather. */
    val maximumCalibrationAgeNanos: Long,
    /** Explicit allowance for atmosphere/model error not represented by sensor uncertainties. */
    val modelUncertaintyMeters: Double
) {
    init {
        require(maximumCalibrationAgeNanos > 0L)
        require(modelUncertaintyMeters.isFinite() && modelUncertaintyMeters >= 0.0)
    }
}

/**
 * Estimates height relative to a previously trusted altitude using the hypsometric relation.
 *
 * This is an approximate pressure-derived altitude, not an independent absolute position. The
 * caller must supply pressure in pascals and an effective mean air-column temperature in kelvin.
 * A recently calibrated trusted reference and its vertical datum are required; uncalibrated
 * pressure is never emitted as an altitude. The configured model uncertainty must account for
 * conditions not covered by the simplified atmosphere model.
 */
internal object BarometricAltitudeEstimator {
    private const val DRY_AIR_SPECIFIC_GAS_CONSTANT = 287.05 // J / (kg K)
    private const val STANDARD_GRAVITY = 9.80665 // m / s²
    private const val MIN_PRESSURE_PA = 30_000.0
    private const val MAX_PRESSURE_PA = 120_000.0
    private const val MIN_MEAN_TEMPERATURE_K = 180.0
    private const val MAX_MEAN_TEMPERATURE_K = 330.0

    fun calibrate(
        referencePressurePa: Double,
        referencePressureUncertaintyPa: Double,
        trustedAltitudeMeters: Double,
        trustedAltitudeUncertaintyMeters: Double,
        verticalDatum: AltitudeVerticalDatum,
        elapsedRealtimeNanos: Long
    ): BarometricAltitudeCalibration? {
        if (!isValidPressure(referencePressurePa) ||
            !isValidUncertainty(referencePressureUncertaintyPa) ||
            referencePressureUncertaintyPa >= referencePressurePa ||
            !trustedAltitudeMeters.isFinite() ||
            !isValidUncertainty(trustedAltitudeUncertaintyMeters) ||
            !isSupportedDatum(verticalDatum) ||
            elapsedRealtimeNanos <= 0L
        ) {
            return null
        }
        return BarometricAltitudeCalibration(
            referencePressurePa = referencePressurePa,
            referencePressureUncertaintyPa = referencePressureUncertaintyPa,
            trustedAltitudeMeters = trustedAltitudeMeters,
            trustedAltitudeUncertaintyMeters = trustedAltitudeUncertaintyMeters,
            verticalDatum = verticalDatum,
            calibratedAtElapsedRealtimeNanos = elapsedRealtimeNanos
        )
    }

    fun estimate(
        calibration: BarometricAltitudeCalibration,
        pressurePa: Double,
        pressureUncertaintyPa: Double,
        meanAirTemperatureKelvin: Double,
        meanAirTemperatureUncertaintyKelvin: Double,
        elapsedRealtimeNanos: Long,
        config: BarometricAltitudeConfig
    ): AltitudeObservation? {
        if (!isValidPressure(pressurePa) ||
            !isValidUncertainty(pressureUncertaintyPa) ||
            pressureUncertaintyPa >= pressurePa ||
            !meanAirTemperatureKelvin.isFinite() ||
            meanAirTemperatureKelvin !in MIN_MEAN_TEMPERATURE_K..MAX_MEAN_TEMPERATURE_K ||
            !isValidUncertainty(meanAirTemperatureUncertaintyKelvin) ||
            meanAirTemperatureUncertaintyKelvin > meanAirTemperatureKelvin ||
            elapsedRealtimeNanos <= 0L ||
            calibration.calibratedAtElapsedRealtimeNanos <= 0L ||
            elapsedRealtimeNanos < calibration.calibratedAtElapsedRealtimeNanos ||
            elapsedRealtimeNanos - calibration.calibratedAtElapsedRealtimeNanos >
                config.maximumCalibrationAgeNanos ||
            !isSupportedDatum(calibration.verticalDatum)
        ) {
            return null
        }

        // A rough hypsometric estimate. Lower pressure than the trusted reference means higher
        // altitude. This does not compensate for weather fronts or non-standard lapse rates.
        val logPressureRatio = ln(pressurePa / calibration.referencePressurePa)
        val scaleHeight = DRY_AIR_SPECIFIC_GAS_CONSTANT *
            meanAirTemperatureKelvin / STANDARD_GRAVITY
        val altitudeDelta = -scaleHeight * logPressureRatio
        val altitude = calibration.trustedAltitudeMeters + altitudeDelta

        // First-order uncertainty propagation; modelUncertaintyMeters covers errors outside this
        // simplified sensor/temperature propagation and must be calibrated conservatively.
        val currentPressureTerm = scaleHeight / pressurePa * pressureUncertaintyPa
        val referencePressureTerm =
            scaleHeight / calibration.referencePressurePa *
                calibration.referencePressureUncertaintyPa
        val temperatureTerm = abs(
            DRY_AIR_SPECIFIC_GAS_CONSTANT / STANDARD_GRAVITY * logPressureRatio
        ) * meanAirTemperatureUncertaintyKelvin
        val uncertainty = sqrt(
            calibration.trustedAltitudeUncertaintyMeters * calibration.trustedAltitudeUncertaintyMeters +
                currentPressureTerm * currentPressureTerm +
                referencePressureTerm * referencePressureTerm +
                temperatureTerm * temperatureTerm +
                config.modelUncertaintyMeters * config.modelUncertaintyMeters
        )

        if (!altitude.isFinite() || !uncertainty.isFinite()) return null
        return AltitudeObservation(
            valueMeters = altitude,
            verticalDatum = calibration.verticalDatum,
            uncertaintyMeters = uncertainty,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            source = AltitudeMeasurementSource.BAROMETER
        )
    }

    private fun isValidPressure(value: Double): Boolean =
        value.isFinite() && value in MIN_PRESSURE_PA..MAX_PRESSURE_PA

    private fun isValidUncertainty(value: Double): Boolean =
        value.isFinite() && value >= 0.0

    private fun isSupportedDatum(datum: AltitudeVerticalDatum): Boolean =
        datum == AltitudeVerticalDatum.WGS84_ELLIPSOID ||
            datum == AltitudeVerticalDatum.EGM96_GEOID ||
            datum == AltitudeVerticalDatum.EGM2008_GEOID
}
