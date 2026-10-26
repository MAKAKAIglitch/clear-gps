package org.cleargps

import kotlin.math.abs

/** Session-only estimates from fresh OBD speed; never persisted or fed into spoofing detection. */
internal data class Obd2DriveMetricsSnapshot(
    val sessionDistanceMeters: Double = 0.0,
    val longitudinalAccelerationMps2: Double? = null,
    val acceptedSpeedSamples: Int = 0
)

internal class Obd2DriveMetricsTracker {
    private data class Sample(val timestampNanos: Long, val speedKmh: Double)
    private var previous: Sample? = null
    private var lastTimestampNanos: Long? = null
    private var distanceMeters = 0.0
    private var sampleCount = 0
    private val accelerationWindow = mutableListOf<Double>()
    private var accelerationMps2: Double? = null

    fun reset() {
        previous = null
        lastTimestampNanos = null
        distanceMeters = 0.0
        sampleCount = 0
        accelerationWindow.clear()
        accelerationMps2 = null
    }

    fun observe(speedKmh: Int?, speedSampleElapsedRealtimeNanos: Long?, nowElapsedRealtimeNanos: Long): Obd2DriveMetricsSnapshot {
        val timestamp = speedSampleElapsedRealtimeNanos
        val sample = if (
            speedKmh != null && speedKmh in 0..Obd2Protocol.MAX_PLAUSIBLE_SPEED_KMH &&
            timestamp != null && timestamp > 0L && timestamp <= nowElapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - timestamp <= MAX_SAMPLE_AGE_NANOS
        ) Sample(timestamp, speedKmh.toDouble()) else null

        if (sample == null) {
            // Break the chain; never bridge stale or missing intervals by guessing.
            previous = null
            accelerationWindow.clear()
            accelerationMps2 = null
        } else {
            val last = lastTimestampNanos
            if (last == null || sample.timestampNanos > last) {
                val before = previous
                if (before == null) {
                    accelerationWindow.clear()
                    accelerationMps2 = null
                } else {
                    val seconds = (sample.timestampNanos - before.timestampNanos) / 1_000_000_000.0
                    if (seconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) {
                        val segment = ((before.speedKmh + sample.speedKmh) / 2.0) / 3.6 * seconds
                        val accumulated = distanceMeters + segment
                        if (segment.isFinite() && segment >= 0.0 && accumulated.isFinite() && accumulated >= distanceMeters) {
                            distanceMeters = accumulated
                        }
                        if (seconds >= MIN_ACCELERATION_INTERVAL_SECONDS) {
                            val slope = (sample.speedKmh - before.speedKmh) / 3.6 / seconds
                            if (slope.isFinite() && abs(slope) <= MAX_ACCELERATION_MPS2) {
                                accelerationWindow += slope
                                while (accelerationWindow.size > ACCEL_WINDOW) accelerationWindow.removeAt(0)
                                accelerationMps2 = median(accelerationWindow)
                            } else {
                                accelerationWindow.clear()
                                accelerationMps2 = null
                            }
                        } else {
                            accelerationMps2 = null
                        }
                    } else {
                        // Long gaps and clock discontinuities start a fresh segment.
                        accelerationWindow.clear()
                        accelerationMps2 = null
                    }
                }
                previous = sample
                lastTimestampNanos = sample.timestampNanos
                if (sampleCount < MAX_COUNT) sampleCount++
            }
        }
        return Obd2DriveMetricsSnapshot(distanceMeters, accelerationMps2, sampleCount)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val ordered = values.sorted()
        val mid = ordered.size / 2
        return if (ordered.size % 2 == 0) (ordered[mid - 1] + ordered[mid]) / 2.0 else ordered[mid]
    }

    companion object {
        const val MAX_SAMPLE_AGE_NANOS = 5_000_000_000L
        private const val MIN_INTERVAL_SECONDS = 0.25
        private const val MAX_INTERVAL_SECONDS = 5.0
        private const val MIN_ACCELERATION_INTERVAL_SECONDS = 0.5
        private const val MAX_ACCELERATION_MPS2 = 6.0
        private const val ACCEL_WINDOW = 3
        private const val MAX_COUNT = 1_000_000
    }
}
