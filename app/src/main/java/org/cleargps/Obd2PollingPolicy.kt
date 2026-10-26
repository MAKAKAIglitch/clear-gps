package org.cleargps

/**
 * Conservative polling cadence for ELM-compatible adapters.
 *
 * A valid stationary-idle sample allows fewer requests; while moving, speed is refreshed
 * frequently enough for the five-second freshness contract used by position extrapolation.
 */
internal object Obd2PollingPolicy {
    const val MOVING_INTERVAL_MS = 1_500L
    const val UNKNOWN_SPEED_INTERVAL_MS = 2_000L
    const val STATIONARY_IDLE_INTERVAL_MS = 3_500L
    const val UNAVAILABLE_SPEED_INTERVAL_MS = 5_000L

    fun nextDelayMillis(telemetry: Obd2Telemetry): Long {
        if (telemetry.speedIssue == Obd2SpeedIssue.PID_UNSUPPORTED ||
            telemetry.speedIssue == Obd2SpeedIssue.PID_DISABLED_FOR_SESSION
        ) return UNAVAILABLE_SPEED_INTERVAL_MS

        val speed = telemetry.vehicleSpeedKmh ?: return UNKNOWN_SPEED_INTERVAL_MS
        if (speed == 0 && telemetry.engineRpm in IDLE_RPM_MIN..IDLE_RPM_MAX) {
            return STATIONARY_IDLE_INTERVAL_MS
        }
        return MOVING_INTERVAL_MS
    }

    private const val IDLE_RPM_MIN = 400
    private const val IDLE_RPM_MAX = 1_600
}
