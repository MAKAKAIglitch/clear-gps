package org.cleargps.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionFusionMotionModelTest {
    @Test
    fun integratesRecentEastAccelerationAndUpdatesVelocityWithoutChangingSourceMetadata() {
        val now = seconds(2)
        val anchorTime = seconds(1)
        val samples = samples(anchorTime, now) { 1.0 to 0.0 }
        val estimate = estimate(
            latitude = 0.0,
            longitude = 0.0,
            speed = 0.0,
            bearing = null,
            measurementTime = anchorTime,
            estimateTime = now
        )

        val corrected = PositionFusionMotionModel.compensate(estimate, samples, now)

        assertNotNull(corrected)
        corrected!!
        val expectedLongitude = Math.toDegrees(0.5 / EARTH_RADIUS_METERS)
        assertEquals(expectedLongitude, corrected.longitude, 1e-10)
        assertEquals(1.0, corrected.speedMetersPerSecond, 1e-6)
        assertEquals(90.0, corrected.bearingDegrees!!, 1e-6)
        assertTrue(corrected.horizontalAccuracyMeters > estimate.horizontalAccuracyMeters)
        assertEquals(estimate.lastMeasurementElapsedRealtimeNanos, corrected.lastMeasurementElapsedRealtimeNanos)
        assertEquals(estimate.acceptedMeasurementCount, corrected.acceptedMeasurementCount)
        assertEquals(estimate.sourcesUsed, corrected.sourcesUsed)
    }

    @Test
    fun accelerationCorrectionIsAddedToConstantVelocityPrediction() {
        val now = seconds(2)
        val anchorTime = seconds(1)
        val constantVelocityPredictionMetersNorth = 10.0
        val estimate = estimate(
            latitude = Math.toDegrees(constantVelocityPredictionMetersNorth / EARTH_RADIUS_METERS),
            longitude = 0.0,
            speed = 10.0,
            bearing = 0.0,
            measurementTime = anchorTime,
            estimateTime = now
        )

        val corrected = PositionFusionMotionModel.compensate(
            estimate,
            samples(anchorTime, now) { 0.0 to 1.0 },
            now
        )

        assertNotNull(corrected)
        assertEquals(
            Math.toDegrees(10.5 / EARTH_RADIUS_METERS),
            corrected!!.latitude,
            1e-10
        )
        assertEquals(11.0, corrected.speedMetersPerSecond, 1e-6)
        assertEquals(0.0, corrected.bearingDegrees!!, 1e-6)
    }

    @Test
    fun rejectsStaleAnchorGapsInvalidAccelerationAndFutureSamples() {
        val anchorTime = seconds(1)
        val now = seconds(2)
        val estimate = estimate(0.0, 0.0, 0.0, null, anchorTime, now)

        val staleNow = seconds(4)
        assertNull(
            PositionFusionMotionModel.compensate(
                estimate.copy(elapsedRealtimeNanos = staleNow),
                samples(anchorTime, staleNow) { 1.0 to 0.0 },
                staleNow
            )
        )
        assertNull(
            PositionFusionMotionModel.compensate(
                estimate,
                listOf(
                    HorizontalAccelerationSample(1.0, 0.0, anchorTime),
                    HorizontalAccelerationSample(1.0, 0.0, anchorTime + 400_000_000L)
                ),
                now
            )
        )
        assertNull(
            PositionFusionMotionModel.compensate(
                estimate,
                listOf(HorizontalAccelerationSample(20.0, 0.0, now - 100_000_000L)),
                now
            )
        )
        assertNull(
            PositionFusionMotionModel.compensate(
                estimate,
                listOf(HorizontalAccelerationSample(1.0, 0.0, now + 1L)),
                now
            )
        )
        assertNull(
            PositionFusionMotionModel.compensate(
                estimate,
                listOf(HorizontalAccelerationSample(1.0, 0.0, now + 1L)),
                now
            )
        )
    }

    @Test
    fun refusesUnboundedCorrectionAndHandlesLongitudeWrap() {
        val now = seconds(2)
        val anchorTime = seconds(1)
        val estimate = estimate(
            latitude = 0.0,
            longitude = 179.999999,
            speed = 0.0,
            bearing = null,
            measurementTime = anchorTime,
            estimateTime = now
        )
        val corrected = PositionFusionMotionModel.compensate(
            estimate,
            samples(anchorTime, now) { 1.0 to 0.0 },
            now
        )

        assertNotNull(corrected)
        assertTrue(corrected!!.longitude < -179.9999)
        assertTrue(corrected.longitude in -180.0..180.0)

        val longNow = seconds(3)
        val longEstimate = estimate(
            latitude = 0.0,
            longitude = 179.999999,
            speed = 0.0,
            bearing = null,
            measurementTime = anchorTime,
            estimateTime = longNow
        )
        val impulsive = samples(anchorTime, longNow) { 12.0 to 0.0 }
        assertNull(PositionFusionMotionModel.compensate(longEstimate, impulsive, longNow))
    }

    private fun samples(
        startNanos: Long,
        endNanos: Long,
        acceleration: () -> Pair<Double, Double>
    ): List<HorizontalAccelerationSample> {
        val values = acceleration()
        val result = ArrayList<HorizontalAccelerationSample>()
        var timestamp = startNanos
        while (timestamp < endNanos) {
            result += HorizontalAccelerationSample(values.first, values.second, timestamp)
            timestamp += 100_000_000L
        }
        return result
    }

    private fun estimate(
        latitude: Double,
        longitude: Double,
        speed: Double,
        bearing: Double?,
        measurementTime: Long,
        estimateTime: Long
    ) = FusedPosition(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = 5.0,
        speedMetersPerSecond = speed,
        bearingDegrees = bearing,
        elapsedRealtimeNanos = estimateTime,
        lastMeasurementElapsedRealtimeNanos = measurementTime,
        acceptedMeasurementCount = 3,
        sourcesUsed = setOf(PositionMeasurementSource.GNSS, PositionMeasurementSource.RADIO)
    )

    private fun seconds(value: Long) = value * 1_000_000_000L

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
