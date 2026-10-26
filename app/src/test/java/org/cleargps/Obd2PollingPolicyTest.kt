package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Test

class Obd2PollingPolicyTest {
    @Test
    fun pollsLessOftenAtAValidStationaryIdle() {
        val telemetry = Obd2Telemetry(vehicleSpeedKmh = 0, engineRpm = 850)
        assertEquals(Obd2PollingPolicy.STATIONARY_IDLE_INTERVAL_MS, Obd2PollingPolicy.nextDelayMillis(telemetry))
    }

    @Test
    fun keepsFrequentPollingWhenMoving() {
        val telemetry = Obd2Telemetry(vehicleSpeedKmh = 32, engineRpm = 1_700)
        assertEquals(Obd2PollingPolicy.MOVING_INTERVAL_MS, Obd2PollingPolicy.nextDelayMillis(telemetry))
    }

    @Test
    fun unknownSpeedUsesShortRetryRatherThanAssumingStationary() {
        val telemetry = Obd2Telemetry(vehicleSpeedKmh = null, engineRpm = 800)
        assertEquals(Obd2PollingPolicy.UNKNOWN_SPEED_INTERVAL_MS, Obd2PollingPolicy.nextDelayMillis(telemetry))
    }

    @Test
    fun unavailableSpeedPidUsesLongerRetry() {
        val unsupported = Obd2Telemetry(speedIssue = Obd2SpeedIssue.PID_UNSUPPORTED)
        val disabled = Obd2Telemetry(speedIssue = Obd2SpeedIssue.PID_DISABLED_FOR_SESSION)
        assertEquals(Obd2PollingPolicy.UNAVAILABLE_SPEED_INTERVAL_MS, Obd2PollingPolicy.nextDelayMillis(unsupported))
        assertEquals(Obd2PollingPolicy.UNAVAILABLE_SPEED_INTERVAL_MS, Obd2PollingPolicy.nextDelayMillis(disabled))
    }

    @Test
    fun missingRpmDoesNotPretendTheVehicleIsIdling() {
        val telemetry = Obd2Telemetry(vehicleSpeedKmh = 0, engineRpm = null)
        assertEquals(Obd2PollingPolicy.MOVING_INTERVAL_MS, Obd2PollingPolicy.nextDelayMillis(telemetry))
    }
}
