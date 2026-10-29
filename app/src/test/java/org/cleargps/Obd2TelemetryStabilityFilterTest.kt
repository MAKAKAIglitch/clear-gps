package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Obd2TelemetryStabilityFilterTest {
    @Test
    fun suppressesSingleZeroAfterRecentPositiveValues() {
        val filter = Obd2TelemetryStabilityFilter()
        assertEquals(60, filter.filter(telemetry(1_000_000_000L, 60, 2_000)).vehicleSpeedKmh)
        val result = filter.filter(telemetry(2_000_000_000L, 0, 0))
        assertNull(result.vehicleSpeedKmh)
        assertEquals(Obd2SpeedIssue.TRANSIENT_ZERO_SUPPRESSED, result.speedIssue)
        assertEquals(Obd2PidIssue.TRANSIENT_ZERO_SUPPRESSED, result.pidIssues[0x0D])
        assertNull(result.speedSampleElapsedRealtimeNanos)
        assertNull(result.engineRpm)
        assertEquals(Obd2PidIssue.TRANSIENT_ZERO_SUPPRESSED, result.pidIssues[0x0C])
        assertNull(result.engineRpmSampleElapsedRealtimeNanos)
    }

    @Test
    fun acceptsZeroAfterSecondConsecutiveReplySoRealStopIsNotHidden() {
        val filter = Obd2TelemetryStabilityFilter()
        filter.filter(telemetry(1_000_000_000L, 55, 1_800))
        filter.filter(telemetry(2_000_000_000L, 0, 0))
        val confirmedStop = filter.filter(telemetry(3_000_000_000L, 0, 0))
        assertEquals(0, confirmedStop.vehicleSpeedKmh)
        assertEquals(0, confirmedStop.engineRpm)
        assertNull(confirmedStop.speedIssue)
        assertEquals(emptyMap<Int, Obd2PidIssue>(), confirmedStop.pidIssues)
    }

    @Test
    fun startupZeroIsAcceptedWhenThereWasNoRecentNonZeroReading() {
        val filter = Obd2TelemetryStabilityFilter()
        val stopped = filter.filter(telemetry(1_000_000_000L, 0, 0))
        assertEquals(0, stopped.vehicleSpeedKmh)
        assertEquals(0, stopped.engineRpm)
    }

    @Test
    fun missingReplyBreaksTheZeroConfirmationStreak() {
        val filter = Obd2TelemetryStabilityFilter()
        filter.filter(telemetry(1_000_000_000L, 30, 1_500))
        filter.filter(telemetry(2_000_000_000L, 0, 0))
        filter.filter(telemetry(2_500_000_000L, null, null))
        val nextZero = filter.filter(telemetry(3_000_000_000L, 0, 0))
        assertNull(nextZero.vehicleSpeedKmh)
        assertNull(nextZero.engineRpm)
    }

    private fun telemetry(atNanos: Long, speed: Int?, rpm: Int?): Obd2Telemetry = Obd2Telemetry(
        vehicleSpeedKmh = speed,
        engineRpm = rpm,
        updatedAt = 10_000L + atNanos / 1_000_000L,
        speedIssue = if (speed == null) Obd2SpeedIssue.NO_VALID_RESPONSE else null,
        speedSampleElapsedRealtimeNanos = if (speed != null) atNanos else null,
        speedQueryDurationMillis = if (speed != null) 40L else null,
        engineRpmSampleElapsedRealtimeNanos = if (rpm != null) atNanos else null,
        engineRpmQueryDurationMillis = if (rpm != null) 40L else null
    )
}
