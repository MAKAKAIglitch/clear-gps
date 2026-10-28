package org.cleargps

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuMotionSamplePolicyTest {
    @After
    fun clearStore() {
        ImuMotionLiveStore.clear()
    }

    @Test
    fun acceptsFiniteHorizontalAccelerationWithFreshOrientation() {
        val sample = ImuMotionSamplePolicy.fromHorizontalAcceleration(
            eastMetersPerSecondSquared = 1.25,
            northMetersPerSecondSquared = -2.5,
            elapsedRealtimeNanos = 1_000_000_000L,
            rotationVectorElapsedRealtimeNanos = 950_000_000L,
            rotationVectorAccuracy = 3
        )

        assertNotNull(sample)
        assertEquals(1.25, sample!!.eastMetersPerSecondSquared, 0.0)
        assertEquals(-2.5, sample.northMetersPerSecondSquared, 0.0)
    }

    @Test
    fun rejectsInvalidTimesUnreliableOrientationAndLargeOrNonFiniteAcceleration() {
        assertNull(ImuMotionSamplePolicy.fromHorizontalAcceleration(0.0, 0.0, 0L, 1L, 3))
        assertNull(ImuMotionSamplePolicy.fromHorizontalAcceleration(0.0, 0.0, 10L, 0L, 3))
        assertNull(ImuMotionSamplePolicy.fromHorizontalAcceleration(0.0, 0.0, 10L, 10L, 0))
        assertNull(ImuMotionSamplePolicy.fromHorizontalAcceleration(Double.NaN, 0.0, 10L, 10L, 3))
        assertNull(ImuMotionSamplePolicy.fromHorizontalAcceleration(Double.POSITIVE_INFINITY, 0.0, 10L, 10L, 3))
        assertNull(ImuMotionSamplePolicy.fromHorizontalAcceleration(40.0, 40.0, 10L, 10L, 3))
    }

    @Test
    fun rejectsOrientationSampleWhenPairingSkewIsTooLarge() {
        assertNull(
            ImuMotionSamplePolicy.fromHorizontalAcceleration(
                0.5, 0.5,
                elapsedRealtimeNanos = 1_000_000_000L,
                rotationVectorElapsedRealtimeNanos = 1L,
                rotationVectorAccuracy = 2
            )
        )
    }

    @Test
    fun freshnessRejectsMissingStaleFutureAndInvalidSamples() {
        val sample = sample(10L)
        assertNotNull(ImuMotionSamplePolicy.freshSample(sample, 10L))
        assertNull(ImuMotionSamplePolicy.freshSample(null, 10L))
        assertNull(ImuMotionSamplePolicy.freshSample(sample, 510_000_011L))
        assertNull(ImuMotionSamplePolicy.freshSample(sample, 9L))
        assertNull(ImuMotionSamplePolicy.freshSample(sample.copy(eastMetersPerSecondSquared = Double.NaN), 20L))
        assertNull(ImuMotionSamplePolicy.freshSample(sample, 10L, maximumAgeNanos = -1L))
    }

    @Test
    fun freshnessBoundaryIsInclusive() {
        val sample = sample(10L)
        assertNotNull(
            ImuMotionSamplePolicy.freshSample(
                sample,
                sample.elapsedRealtimeNanos + ImuMotionSamplePolicy.DEFAULT_MAXIMUM_AGE_NANOS
            )
        )
    }

    @Test
    fun liveStoreIgnoresOutOfOrderSamplesAndCanBeCleared() {
        val newer = sample(20L)
        val older = sample(19L)
        ImuMotionLiveStore.publish(newer)
        ImuMotionLiveStore.publish(older)

        assertEquals(newer, ImuMotionLiveStore.snapshot(20L))
        ImuMotionLiveStore.clear()
        assertNull(ImuMotionLiveStore.snapshot(20L))
        assertTrue(ImuMotionLiveStore.snapshot(0L) == null)
    }

    private fun sample(timestampNanos: Long) = ImuMotionSamplePolicy.fromHorizontalAcceleration(
        eastMetersPerSecondSquared = 0.25,
        northMetersPerSecondSquared = -0.5,
        elapsedRealtimeNanos = timestampNanos,
        rotationVectorElapsedRealtimeNanos = timestampNanos,
        rotationVectorAccuracy = 3
    )!!
}
