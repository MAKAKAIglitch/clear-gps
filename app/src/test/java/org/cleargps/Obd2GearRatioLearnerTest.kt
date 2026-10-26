package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2GearRatioLearnerTest {
    private val baseTime = 200_000_000_000L

    private fun observe(
        learner: Obd2GearRatioLearner,
        index: Int,
        rpm: Int = 3_000,
        speed: Int = 60,
        trustedPair: Boolean = true,
        load: Int? = 35,
        throttle: Int? = 20,
        rpmTime: Long = baseTime + index * 2_000_000_000L,
        speedTime: Long = rpmTime + 100_000_000L,
        rpmDuration: Long = 120L,
        speedDuration: Long = 150L,
        now: Long = speedTime + 100_000_000L
    ) = learner.observe(
        engineRpm = rpm,
        vehicleSpeedKmh = speed,
        engineLoadPercent = load,
        throttlePercent = throttle,
        engineRpmSampleElapsedRealtimeNanos = rpmTime,
        engineRpmQueryDurationMillis = rpmDuration,
        speedSampleElapsedRealtimeNanos = speedTime,
        speedQueryDurationMillis = speedDuration,
        trustedSpeedPair = trustedPair,
        nowElapsedRealtimeNanos = now
    )

    @Test
    fun requiresTrustedSpeedPairAndRejectsPoorTimeAlignment() {
        val learner = Obd2GearRatioLearner()
        assertEquals(
            Obd2GearLearningStatus.WAITING_FOR_TRUSTED_SPEED_PAIR,
            observe(learner, 0, trustedPair = false).status
        )
        val timestamp = baseTime + 10_000_000_000L
        assertEquals(
            Obd2GearLearningStatus.TIME_MISMATCH,
            observe(learner, 1, rpmTime = timestamp, speedTime = timestamp + 2_000_000_000L,
                now = timestamp + 2_100_000_000L).status
        )
        assertTrue(learner.currentSnapshot().bands.isEmpty())
    }

    @Test
    fun rejectsStaleSlowAndHighLoadSamples() {
        val stale = Obd2GearRatioLearner()
        assertEquals(
            Obd2GearLearningStatus.TIME_MISMATCH,
            observe(stale, 0, now = baseTime + 6_000_000_000L).status
        )
        val queryTooSlow = Obd2GearRatioLearner()
        assertEquals(
            Obd2GearLearningStatus.TIME_MISMATCH,
            observe(queryTooSlow, 1, rpmDuration = 1_800L).status
        )
        val highLoad = Obd2GearRatioLearner()
        assertEquals(
            Obd2GearLearningStatus.WAITING_FOR_STEADY_DRIVING,
            observe(highLoad, 2, load = 95).status
        )
        assertEquals(
            Obd2GearLearningStatus.WAITING_FOR_STEADY_DRIVING,
            observe(highLoad, 3, throttle = 90).status
        )
        assertEquals(
            Obd2GearLearningStatus.WAITING_FOR_STEADY_DRIVING,
            observe(highLoad, 4, speed = 5).status
        )
    }

    @Test
    fun confirmsAStableBandOnlyAfterFiveAlignedSamplesAcrossSixSeconds() {
        val learner = Obd2GearRatioLearner()
        var snapshot = Obd2GearLearningSnapshot()
        repeat(Obd2GearRatioLearner.MIN_BAND_SAMPLES) { index ->
            snapshot = observe(learner, index, rpm = 3_000, speed = 60)
            if (index < Obd2GearRatioLearner.MIN_BAND_SAMPLES - 1) {
                assertTrue(snapshot.bands.isEmpty())
            }
        }
        assertEquals(Obd2GearLearningStatus.LEARNING, snapshot.status)
        assertEquals(1, snapshot.bands.size)
        assertEquals(50.0, snapshot.bands.single().medianRpmPerKmh, 0.01)
        assertEquals(5, snapshot.bands.single().samples)
    }

    @Test
    fun learnsSeparateRelativeBandsButDoesNotPretendTheyAreGearNumbers() {
        val learner = Obd2GearRatioLearner()
        repeat(Obd2GearRatioLearner.MIN_BAND_SAMPLES) { index ->
            observe(learner, index, rpm = 3_000, speed = 60)
        }
        var snapshot = Obd2GearLearningSnapshot()
        repeat(Obd2GearRatioLearner.MIN_BAND_SAMPLES) { index ->
            snapshot = observe(learner, index + 5, rpm = 3_500, speed = 50)
        }
        assertEquals(Obd2GearLearningStatus.RATIOS_LEARNED, snapshot.status)
        assertEquals(2, snapshot.bands.size)
        assertEquals(70.0, snapshot.bands[0].medianRpmPerKmh, 0.01)
        assertEquals(50.0, snapshot.bands[1].medianRpmPerKmh, 0.01)
    }

    @Test
    fun samplesCannotBeCountedTwiceAndResetClearsAllBands() {
        val learner = Obd2GearRatioLearner()
        val timestamp = baseTime
        val first = observe(learner, 0, rpmTime = timestamp, speedTime = timestamp + 100_000_000L)
        val duplicate = observe(
            learner, 1, rpmTime = timestamp, speedTime = timestamp + 100_000_000L,
            now = timestamp + 200_000_000L
        )
        assertEquals(first, duplicate)
        learner.reset()
        val reset = learner.currentSnapshot()
        assertEquals(Obd2GearLearningStatus.WAITING_FOR_DATA, reset.status)
        assertTrue(reset.bands.isEmpty())
        assertNull(reset.currentRatioRpmPerKmh)
    }
}
