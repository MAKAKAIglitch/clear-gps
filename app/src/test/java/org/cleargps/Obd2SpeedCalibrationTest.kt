package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2SpeedCalibrationTest {
    private val baseTime = 100_000_000_000L

    private fun observe(
        calibration: Obd2SpeedCalibration,
        index: Int,
        obdSpeed: Int = 30,
        gnssSpeed: Double = 31.5,
        trusted: Boolean = true,
        provider: String = "gps",
        mock: Boolean = false,
        accuracy: Double? = 5.0,
        speedAccuracy: Double? = 0.4,
        gnssTimestamp: Long = baseTime + index * 2_000_000_000L,
        obdTimestamp: Long = gnssTimestamp,
        durationMillis: Long = 300L,
        now: Long = maxOf(gnssTimestamp, obdTimestamp) + 100_000_000L
    ) = calibration.observe(
        gnssSpeedKmh = gnssSpeed, gnssAccuracyMeters = accuracy,
        gnssSpeedAccuracyMetersPerSecond = speedAccuracy,
        gnssElapsedRealtimeNanos = gnssTimestamp, gnssProvider = provider,
        gnssIsMock = mock, gnssTrustedNormal = trusted, obdSpeedKmh = obdSpeed,
        obdSampleElapsedRealtimeNanos = obdTimestamp, obdQueryDurationMillis = durationMillis,
        nowElapsedRealtimeNanos = now
    )

    @Test
    fun ignoresUntrustedNetworkAndMockFixes() {
        val calibration = Obd2SpeedCalibration()
        assertEquals(Obd2CalibrationStatus.WAITING_FOR_TRUSTED_GNSS, observe(calibration, 0, trusted = false).status)
        assertEquals(Obd2CalibrationStatus.WAITING_FOR_PHYSICAL_GNSS, observe(calibration, 1, provider = "network").status)
        assertEquals(Obd2CalibrationStatus.WAITING_FOR_PHYSICAL_GNSS, observe(calibration, 2, mock = true).status)
        assertEquals(0, calibrationSnapshotCount(calibration, 2))
    }

    @Test
    fun rejectsPoorAccuracyStaleAndMisalignedMeasurements() {
        val poor = Obd2SpeedCalibration()
        assertEquals(Obd2CalibrationStatus.LOW_GNSS_QUALITY, observe(poor, 0, accuracy = 60.0).status)
        val skewed = Obd2SpeedCalibration()
        assertEquals(
            Obd2CalibrationStatus.TIME_MISMATCH,
            observe(skewed, 1, gnssTimestamp = baseTime + 10_000_000_000L,
                obdTimestamp = baseTime + 6_000_000_000L, now = baseTime + 10_100_000_000L).status
        )
        val slow = Obd2SpeedCalibration()
        assertEquals(Obd2CalibrationStatus.OBD_QUERY_TOO_SLOW, observe(slow, 2, durationMillis = 2_000L).status)
        val stale = Obd2SpeedCalibration()
        assertEquals(
            Obd2CalibrationStatus.TIME_MISMATCH,
            observe(stale, 3, gnssTimestamp = baseTime, obdTimestamp = baseTime,
                now = baseTime + 6_000_000_000L).status
        )
    }

    @Test
    fun enablesOnlyStableDiverseCalibrationAfterTwentyPairsAndThirtySeconds() {
        val calibration = Obd2SpeedCalibration()
        var snapshot = Obd2SpeedCalibrationSnapshot()
        repeat(Obd2SpeedCalibration.MIN_CALIBRATION_SAMPLES) { index ->
            val obd = 20 + index * 2
            val timestamp = baseTime + index * 2_000_000_000L
            snapshot = observe(calibration, index, obdSpeed = obd, gnssSpeed = obd * 1.05,
                gnssTimestamp = timestamp, obdTimestamp = timestamp, now = timestamp + 100_000_000L)
            if (index < Obd2SpeedCalibration.MIN_CALIBRATION_SAMPLES - 1) assertNull(snapshot.activeCorrectionScale)
        }
        assertEquals(Obd2CalibrationStatus.CALIBRATED, snapshot.status)
        assertEquals(Obd2SpeedCalibration.MIN_CALIBRATION_SAMPLES, snapshot.acceptedPairs)
        assertTrue(snapshot.activeCorrectionScale != null)
        assertEquals(1.05, snapshot.activeCorrectionScale!!, 0.01)
    }

    @Test
    fun aLargeSpeedDisagreementDoesNotTrainOrChangeActiveCoefficient() {
        val calibration = Obd2SpeedCalibration()
        var snapshot = Obd2SpeedCalibrationSnapshot()
        repeat(Obd2SpeedCalibration.MIN_CALIBRATION_SAMPLES) { index ->
            val obd = 20 + index * 2
            val timestamp = baseTime + index * 2_000_000_000L
            snapshot = observe(calibration, index, obdSpeed = obd, gnssSpeed = obd * 1.05,
                gnssTimestamp = timestamp, obdTimestamp = timestamp, now = timestamp + 100_000_000L)
        }
        val trained = snapshot.activeCorrectionScale
        val outlierTime = baseTime + 80_000_000_000L
        snapshot = observe(calibration, 41, obdSpeed = 40, gnssSpeed = 70.0,
            gnssTimestamp = outlierTime, obdTimestamp = outlierTime, now = outlierTime + 100_000_000L)
        assertEquals(Obd2CalibrationStatus.SPEEDS_DIVERGE, snapshot.status)
        assertEquals(trained!!, snapshot.activeCorrectionScale!!, 0.0001)
        assertEquals(Obd2SpeedCalibration.MIN_CALIBRATION_SAMPLES, snapshot.acceptedPairs)
    }

    @Test
    fun duplicateObdSampleIsNotCountedTwiceAndResetDropsSessionCalibration() {
        val calibration = Obd2SpeedCalibration()
        val timestamp = baseTime
        val first = observe(calibration, 0, gnssTimestamp = timestamp, obdTimestamp = timestamp, now = timestamp + 100_000_000L)
        val duplicate = observe(calibration, 1, gnssTimestamp = timestamp + 1_000_000_000L, obdTimestamp = timestamp, now = timestamp + 1_100_000_000L)
        assertEquals(1, first.acceptedPairs)
        assertEquals(first, duplicate)
        calibration.reset()
        val reset = calibration.observe(
            gnssSpeedKmh = null, gnssAccuracyMeters = null, gnssSpeedAccuracyMetersPerSecond = null,
            gnssElapsedRealtimeNanos = 0L, gnssProvider = null, gnssIsMock = false,
            gnssTrustedNormal = false, obdSpeedKmh = null, obdSampleElapsedRealtimeNanos = null,
            obdQueryDurationMillis = null, nowElapsedRealtimeNanos = baseTime
        )
        assertEquals(Obd2CalibrationStatus.WAITING_FOR_DATA, reset.status)
        assertEquals(0, reset.acceptedPairs)
        assertNull(reset.activeCorrectionScale)
    }

    private fun calibrationSnapshotCount(calibration: Obd2SpeedCalibration, index: Int): Int =
        observe(calibration, index, trusted = false).acceptedPairs
}
