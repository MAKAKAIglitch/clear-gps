package org.cleargps

/**
 * Filters brief zero glitches after a recently positive OBD PID sample.
 *
 * Zero is a legitimate reading, so it is accepted after three consecutive replies or when there
 * is no recent positive sample. The first two zeros after recent nonzero values are exposed as
 * missing data, with their timestamps removed so downstream positioning cannot reuse them.
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
            zeroStreak < ZERO_CONFIRMATION_READINGS

    private companion object {
        const val ZERO_CONFIRMATION_WINDOW_NANOS = 12_000_000_000L
        const val ZERO_CONFIRMATION_READINGS = 3
    }
}
