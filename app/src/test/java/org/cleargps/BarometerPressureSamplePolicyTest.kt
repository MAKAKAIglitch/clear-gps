package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class BarometerPressureSamplePolicyTest {
    @Test
    fun convertsHectopascalsToPascalsAndPreservesResolutionAsMetadata() {
        val sample = BarometerPressureSamplePolicy.fromHectopascals(
            pressureHectopascals = 1013.25f,
            resolutionHectopascals = 0.01f,
            elapsedRealtimeNanos = 10L
        )!!

        assertEquals(101_325.0, sample.pressurePascals, 0.01)
        assertEquals(1.0, sample.sensorResolutionPascals!!, 0.00001)
        assertEquals(10L, sample.elapsedRealtimeNanos)
    }

    @Test
    fun invalidPressureAndTimestampsAreRejected() {
        assertNull(BarometerPressureSamplePolicy.fromHectopascals(Float.NaN, 0.01f, 10L))
        assertNull(BarometerPressureSamplePolicy.fromHectopascals(Float.POSITIVE_INFINITY, 0.01f, 10L))
        assertNull(BarometerPressureSamplePolicy.fromHectopascals(0.0f, 0.01f, 10L))
        assertNull(BarometerPressureSamplePolicy.fromHectopascals(1_300.0f, 0.01f, 10L))
        assertNull(BarometerPressureSamplePolicy.fromHectopascals(1_013.25f, 0.01f, 0L))
    }

    @Test
    fun invalidSensorResolutionIsOmittedRatherThanInvented() {
        val sample = BarometerPressureSamplePolicy.fromHectopascals(
            pressureHectopascals = 1_013.25f,
            resolutionHectopascals = Float.NaN,
            elapsedRealtimeNanos = 10L
        )!!

        assertNull(sample.sensorResolutionPascals)
    }

    @Test
    fun freshnessRejectsAbsentStaleFutureAndInvalidSamples() {
        val fresh = BarometerPressureSample(101_325.0, 1.0, 10L)
        assertNotNull(BarometerPressureSamplePolicy.freshSample(fresh, 10L))
        assertNull(BarometerPressureSamplePolicy.freshSample(null, 10L))
        assertNull(BarometerPressureSamplePolicy.freshSample(fresh, 10L + 5_000_000_001L))
        assertNull(BarometerPressureSamplePolicy.freshSample(fresh.copy(elapsedRealtimeNanos = 11L), 10L))
        assertNull(BarometerPressureSamplePolicy.freshSample(fresh.copy(pressurePascals = Double.NaN), 10L))
        assertFalse(BarometerPressureSamplePolicy.freshSample(fresh, 0L) != null)
    }

    @Test
    fun maximumAgeBoundaryIsInclusive() {
        val sample = BarometerPressureSample(101_325.0, null, 10L)
        assertNotNull(
            BarometerPressureSamplePolicy.freshSample(
                sample,
                nowElapsedRealtimeNanos = 10L + BarometerPressureSamplePolicy.DEFAULT_MAXIMUM_AGE_NANOS
            )
        )
    }
}
