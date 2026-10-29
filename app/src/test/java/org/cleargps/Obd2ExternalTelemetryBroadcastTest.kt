package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Obd2ExternalTelemetryBroadcastTest {
    @Test
    fun buildsDocumentedBroadcastWithOnlyFreshTelemetryAndNoVin() {
        val nowEpoch = 100_000L
        val nowNanos = 20_000_000_000L
        val telemetry = Obd2Telemetry(
            vehicleSpeedKmh = 42,
            engineRpm = 1_850,
            engineLoadPercent = 36,
            throttlePercent = 17,
            vehicleVin = "1HGCM82633A004352",
            updatedAt = nowEpoch,
            speedSampleElapsedRealtimeNanos = nowNanos - 200_000_000L,
            speedQueryDurationMillis = 35L,
            engineRpmSampleElapsedRealtimeNanos = nowNanos - 300_000_000L,
            engineRpmQueryDurationMillis = 45L
        )
        val intent = Obd2ExternalTelemetryBroadcast.buildIntent(
            connected = true,
            telemetry = telemetry,
            nowEpochMillis = nowEpoch + 100L,
            nowElapsedRealtimeNanos = nowNanos
        )
        assertEquals(Obd2ExternalTelemetryBroadcast.ACTION_TELEMETRY, intent.action)
        assertTrue(intent.getBooleanExtra(Obd2ExternalTelemetryBroadcast.EXTRA_CONNECTED, false))
        assertEquals(1, intent.getIntExtra(Obd2ExternalTelemetryBroadcast.EXTRA_API_VERSION, 0))
        assertEquals(42, intent.getIntExtra(Obd2ExternalTelemetryBroadcast.EXTRA_VEHICLE_SPEED_KMH, -1))
        assertEquals(1_850, intent.getIntExtra(Obd2ExternalTelemetryBroadcast.EXTRA_ENGINE_RPM, -1))
        assertEquals(36, intent.getIntExtra(Obd2ExternalTelemetryBroadcast.EXTRA_ENGINE_LOAD_PERCENT, -1))
        assertFalse(intent.hasExtra("vehicle_vin"))
        assertFalse(intent.hasExtra("adapter_address"))
    }

    @Test
    fun staleSamplesAreNotIncludedInTheBroadcast() {
        val nowEpoch = 100_000L
        val nowNanos = 20_000_000_000L
        val staleAt = nowNanos - Obd2SharedState.SPEED_MAX_AGE_NANOS - 1L
        val telemetry = Obd2Telemetry(
            vehicleSpeedKmh = 80,
            engineRpm = 3_000,
            updatedAt = nowEpoch,
            speedSampleElapsedRealtimeNanos = staleAt,
            speedQueryDurationMillis = 40L,
            engineRpmSampleElapsedRealtimeNanos = staleAt,
            engineRpmQueryDurationMillis = 40L
        )
        val intent = Obd2ExternalTelemetryBroadcast.buildIntent(
            connected = true,
            telemetry = telemetry,
            nowEpochMillis = nowEpoch + 100L,
            nowElapsedRealtimeNanos = nowNanos
        )
        assertFalse(intent.hasExtra(Obd2ExternalTelemetryBroadcast.EXTRA_VEHICLE_SPEED_KMH))
        assertFalse(intent.hasExtra(Obd2ExternalTelemetryBroadcast.EXTRA_ENGINE_RPM))
    }

    @Test
    fun disconnectMessageDoesNotContainPreviousVehicleData() {
        val intent = Obd2ExternalTelemetryBroadcast.buildIntent(
            connected = false,
            telemetry = Obd2Telemetry(vehicleSpeedKmh = 33, engineRpm = 1_200, updatedAt = 100L),
            nowEpochMillis = 200L,
            nowElapsedRealtimeNanos = 20_000_000_000L
        )
        assertFalse(intent.getBooleanExtra(Obd2ExternalTelemetryBroadcast.EXTRA_CONNECTED, true))
        assertEquals("disconnected", intent.getStringExtra(Obd2ExternalTelemetryBroadcast.EXTRA_STATUS))
        assertFalse(intent.hasExtra(Obd2ExternalTelemetryBroadcast.EXTRA_VEHICLE_SPEED_KMH))
    }
}
