package org.cleargps

/**
 * Filters a single zero response that interrupts a recently positive OBD PID sample.
 *
 * Zero is a legitimate reading (stationary vehicle or stopped engine), so it is never removed
 * permanently: after two consecutive zero replies it is accepted. A rejected zero is exposed as
 * missing data, and its original timestamp is removed so downstream positioning cannot reuse it.
 */
internal class Obd2TelemetryStabilityFilter {
    private var lastNonZeroSpeedAtNanos = 0L
    private var lastNonZeroRpmAtNanos = 0L
    private var consecutiveSpeedZeros = 0
    private var consecutiveRpmZeros = 0

    fun filter(telemetry: Obd2Telemetry): Obd2Telemetry {
        val issues = telemetry.pidIssues.toMutableMap()
        val speed = telemetry.vehicleSpeedKmh
        val speedAt = telemetry.speedSampleElapsedRealtimeNanos
        val speedZeroSuppressed = when {
            speed == null -> {
                consecutiveSpeedZeros = 0
                false
            }
            speed == 0 -> {
                consecutiveSpeedZeros += 1
                isTransientZero(speedAt, lastNonZeroSpeedAtNanos, consecutiveSpeedZeros)
            }
            else -> {
                consecutiveSpeedZeros = 0
                if (speedAt != null && speedAt > 0L) lastNonZeroSpeedAtNanos = speedAt
                false
            }
        }

        val rpm = telemetry.engineRpm
        val rpmAt = telemetry.engineRpmSampleElapsedRealtimeNanos
        val rpmZeroSuppressed = when {
            rpm == null -> {
                consecutiveRpmZeros = 0
                false
            }
            rpm == 0 -> {
                consecutiveRpmZeros += 1
                isTransientZero(rpmAt, lastNonZeroRpmAtNanos, consecutiveRpmZeros)
            }
            else -> {
                consecutiveRpmZeros = 0
                if (rpmAt != null && rpmAt > 0L) lastNonZeroRpmAtNanos = rpmAt
                false
            }
        }

        if (speedZeroSuppressed) issues[0x0D] = Obd2PidIssue.TRANSIENT_ZERO_SUPPRESSED
        if (rpmZeroSuppressed) issues[0x0C] = Obd2PidIssue.TRANSIENT_ZERO_SUPPRESSED

        return telemetry.copy(
            vehicleSpeedKmh = if (speedZeroSuppressed) null else speed,
            speedIssue = if (speedZeroSuppressed) Obd2SpeedIssue.TRANSIENT_ZERO_SUPPRESSED else telemetry.speedIssue,
            speedSampleElapsedRealtimeNanos = if (speedZeroSuppressed) null else speedAt,
            speedQueryDurationMillis = if (speedZeroSuppressed) null else telemetry.speedQueryDurationMillis,
            engineRpm = if (rpmZeroSuppressed) null else rpm,
            engineRpmSampleElapsedRealtimeNanos = if (rpmZeroSuppressed) null else rpmAt,
            engineRpmQueryDurationMillis = if (rpmZeroSuppressed) null else telemetry.engineRpmQueryDurationMillis,
            pidIssues = issues
        )
    }

    private fun isTransientZero(sampleAtNanos: Long?, lastNonZeroAtNanos: Long, zeroStreak: Int): Boolean =
        sampleAtNanos != null &&
            lastNonZeroAtNanos > 0L &&
            sampleAtNanos > lastNonZeroAtNanos &&
            sampleAtNanos - lastNonZeroAtNanos <= ZERO_CONFIRMATION_WINDOW_NANOS &&
            zeroStreak == 1

    private companion object {
        const val ZERO_CONFIRMATION_WINDOW_NANOS = 5_000_000_000L
    }
}
