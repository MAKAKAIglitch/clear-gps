package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarometricAltitudeEstimatorTest {
    private val config = BarometricAltitudeConfig(
        maximumCalibrationAgeNanos = seconds(60),
        modelUncertaintyMeters = 2.0
    )

    @Test
    fun calibrationRequiredAndEstimateCarriesTrustedDatum() {
        assertNull(
            BarometricAltitudeEstimator.estimate(
                calibration = calibration(),
                pressurePa = 100_000.0,
                pressureUncertaintyPa = 2.0,
                meanAirTemperatureKelvin = 288.15,
                meanAirTemperatureUncertaintyKelvin = 2.0,
                elapsedRealtimeNanos = seconds(11),
                config = config
            ).takeIf { false }
        )
        val estimate = estimate(pressure = 99_000.0, at = 11)!!

        assertTrue(estimate.valueMeters > calibration().trustedAltitudeMeters)
        assertEquals(AltitudeVerticalDatum.EGM96_GEOID, estimate.verticalDatum)
        assertEquals(AltitudeMeasurementSource.BAROMETER, estimate.source)
        assertEquals(seconds(11), estimate.elapsedRealtimeNanos)
        assertTrue(estimate.uncertaintyMeters!! >= config.modelUncertaintyMeters)
    }

    @Test
    fun referencePressureProducesReferenceAltitude() {
        val estimate = estimate(pressure = 101_325.0, at = 10)!!
        assertEquals(calibration().trustedAltitudeMeters, estimate.valueMeters, 0.000001)
    }

    @Test
    fun rejectsInvalidPressureTemperatureTimestampAndUnknownDatum() {
        assertNull(
            BarometricAltitudeEstimator.calibrate(
                referencePressurePa = Double.NaN,
                referencePressureUncertaintyPa = 1.0,
                trustedAltitudeMeters = 10.0,
                trustedAltitudeUncertaintyMeters = 2.0,
                verticalDatum = AltitudeVerticalDatum.EGM96_GEOID,
                elapsedRealtimeNanos = seconds(10)
            )
        )
        assertNull(estimate(pressure = 0.0, at = 11))
        assertNull(
            BarometricAltitudeEstimator.estimate(
                calibration = calibration(),
                pressurePa = 100_000.0,
                pressureUncertaintyPa = 2.0,
                meanAirTemperatureKelvin = 100.0,
                meanAirTemperatureUncertaintyKelvin = 2.0,
                elapsedRealtimeNanos = seconds(11),
                config = config
            )
        )
        assertNull(
            BarometricAltitudeEstimator.calibrate(
                referencePressurePa = 101_325.0,
                referencePressureUncertaintyPa = 2.0,
                trustedAltitudeMeters = 10.0,
                trustedAltitudeUncertaintyMeters = 2.0,
                verticalDatum = AltitudeVerticalDatum.MEAN_SEA_LEVEL_UNSPECIFIED,
                elapsedRealtimeNanos = seconds(10)
            )
        )
    }

    @Test
    fun rejectsStaleCalibrationAndBackwardsMonotonicTime() {
        assertNull(estimate(pressure = 100_000.0, at = 71))
        assertNull(estimate(pressure = 100_000.0, at = 9))
    }

    @Test
    fun increasedSensorUncertaintyCannotMakeEstimateAppearMoreCertain() {
        val narrow = BarometricAltitudeEstimator.estimate(
            calibration = calibration(pressureUncertainty = 1.0, altitudeUncertainty = 1.0),
            pressurePa = 100_000.0,
            pressureUncertaintyPa = 1.0,
            meanAirTemperatureKelvin = 288.15,
            meanAirTemperatureUncertaintyKelvin = 1.0,
            elapsedRealtimeNanos = seconds(11),
            config = config
        )!!
        val broad = BarometricAltitudeEstimator.estimate(
            calibration = calibration(pressureUncertainty = 8.0, altitudeUncertainty = 6.0),
            pressurePa = 100_000.0,
            pressureUncertaintyPa = 8.0,
            meanAirTemperatureKelvin = 288.15,
            meanAirTemperatureUncertaintyKelvin = 3.0,
            elapsedRealtimeNanos = seconds(11),
            config = config
        )!!
        assertTrue(broad.uncertaintyMeters!! > narrow.uncertaintyMeters!!)
    }

    private fun estimate(pressure: Double, at: Long) =
        BarometricAltitudeEstimator.estimate(
            calibration = calibration(),
            pressurePa = pressure,
            pressureUncertaintyPa = 2.0,
            meanAirTemperatureKelvin = 288.15,
            meanAirTemperatureUncertaintyKelvin = 2.0,
            elapsedRealtimeNanos = seconds(at),
            config = config
        )

    private fun calibration(
        pressureUncertainty: Double = 2.0,
        altitudeUncertainty: Double = 3.0
    ) = BarometricAltitudeEstimator.calibrate(
        referencePressurePa = 101_325.0,
        referencePressureUncertaintyPa = pressureUncertainty,
        trustedAltitudeMeters = 150.0,
        trustedAltitudeUncertaintyMeters = altitudeUncertainty,
        verticalDatum = AltitudeVerticalDatum.EGM96_GEOID,
        elapsedRealtimeNanos = seconds(10)
    )!!

    private fun seconds(value: Long) = value * 1_000_000_000L
}
