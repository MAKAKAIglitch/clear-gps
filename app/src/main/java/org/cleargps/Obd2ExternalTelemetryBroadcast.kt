package org.cleargps

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Opt-in integration contract for apps that want raw OBD-II telemetry.
 *
 * This is a custom broadcast, not a platform-wide vehicle-data standard. A receiving app must
 * register for ACTION_TELEMETRY and understand the documented EXTRA_* keys. VIN, adapter address,
 * coordinates and radio identifiers are deliberately not included.
 */
internal object Obd2ExternalTelemetryBroadcast {
    const val KEY_ENABLED = "obd2_external_telemetry_broadcast_enabled"
    const val ACTION_TELEMETRY = "org.cleargps.action.OBD2_TELEMETRY"
    const val API_VERSION = 1

    const val EXTRA_API_VERSION = "api_version"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_STATUS = "status"
    const val EXTRA_UPDATED_AT_EPOCH_MILLIS = "updated_at_epoch_millis"
    const val EXTRA_VEHICLE_SPEED_KMH = "vehicle_speed_kmh"
    const val EXTRA_SPEED_SAMPLE_ELAPSED_REALTIME_NANOS = "speed_sample_elapsed_realtime_nanos"
    const val EXTRA_ENGINE_RPM = "engine_rpm"
    const val EXTRA_RPM_SAMPLE_ELAPSED_REALTIME_NANOS = "rpm_sample_elapsed_realtime_nanos"
    const val EXTRA_ENGINE_LOAD_PERCENT = "engine_load_percent"
    const val EXTRA_THROTTLE_PERCENT = "throttle_percent"
    const val EXTRA_MANIFOLD_PRESSURE_KPA = "manifold_pressure_kpa"
    const val EXTRA_COOLANT_TEMPERATURE_CELSIUS = "coolant_temperature_celsius"
    const val EXTRA_BAROMETRIC_PRESSURE_KPA = "barometric_pressure_kpa"

    fun buildIntent(
        connected: Boolean,
        telemetry: Obd2Telemetry?,
        nowEpochMillis: Long,
        nowElapsedRealtimeNanos: Long,
        statusOverride: String? = null
    ): Intent {
        val intent = Intent(ACTION_TELEMETRY)
            .putExtra(EXTRA_API_VERSION, API_VERSION)
            .putExtra(EXTRA_CONNECTED, connected)

        if (!connected) {
            intent.putExtra(EXTRA_STATUS, statusOverride ?: "disconnected")
            return intent
        }
        if (telemetry == null) {
            intent.putExtra(EXTRA_STATUS, statusOverride ?: "connected_waiting_for_data")
            return intent
        }
        val telemetryAgeMillis = nowEpochMillis - telemetry.updatedAt
        val telemetryFresh = telemetry.updatedAt > 0L &&
            nowEpochMillis >= telemetry.updatedAt &&
            telemetryAgeMillis in 0L..MAX_TELEMETRY_AGE_MILLIS
        if (!telemetryFresh) {
            intent.putExtra(EXTRA_STATUS, statusOverride ?: "connected_waiting_for_fresh_data")
            return intent
        }

        intent.putExtra(EXTRA_UPDATED_AT_EPOCH_MILLIS, telemetry.updatedAt)
        var fieldCount = 0
        val speedAt = telemetry.speedSampleElapsedRealtimeNanos
        val speedIsFresh = telemetry.vehicleSpeedKmh?.let { it in 0..Obd2SharedState.MAX_PLAUSIBLE_SPEED_KMH } == true &&
            isFreshSample(speedAt, telemetry.speedQueryDurationMillis, nowElapsedRealtimeNanos)
        if (speedIsFresh) {
            intent.putExtra(EXTRA_VEHICLE_SPEED_KMH, telemetry.vehicleSpeedKmh!!)
            intent.putExtra(EXTRA_SPEED_SAMPLE_ELAPSED_REALTIME_NANOS, speedAt!!)
            fieldCount++
        }

        val rpmAt = telemetry.engineRpmSampleElapsedRealtimeNanos
        val rpmIsFresh = telemetry.engineRpm?.let { it in 0..16_383 } == true &&
            isFreshSample(rpmAt, telemetry.engineRpmQueryDurationMillis, nowElapsedRealtimeNanos)
        if (rpmIsFresh) {
            intent.putExtra(EXTRA_ENGINE_RPM, telemetry.engineRpm!!)
            intent.putExtra(EXTRA_RPM_SAMPLE_ELAPSED_REALTIME_NANOS, rpmAt!!)
            fieldCount++
        }

        telemetry.engineLoadPercent?.takeIf { it in 0..100 }?.let {
            intent.putExtra(EXTRA_ENGINE_LOAD_PERCENT, it)
            fieldCount++
        }
        telemetry.throttlePercent?.takeIf { it in 0..100 }?.let {
            intent.putExtra(EXTRA_THROTTLE_PERCENT, it)
            fieldCount++
        }
        telemetry.manifoldPressureKpa?.takeIf { it in 0..255 }?.let {
            intent.putExtra(EXTRA_MANIFOLD_PRESSURE_KPA, it)
            fieldCount++
        }
        telemetry.coolantTemperatureCelsius?.takeIf { it in -40..215 }?.let {
            intent.putExtra(EXTRA_COOLANT_TEMPERATURE_CELSIUS, it)
            fieldCount++
        }
        telemetry.barometricPressureKpa?.takeIf { it in 0..255 }?.let {
            intent.putExtra(EXTRA_BAROMETRIC_PRESSURE_KPA, it)
            fieldCount++
        }

        intent.putExtra(EXTRA_STATUS, statusOverride ?: if (fieldCount > 0) "connected" else "connected_no_fresh_values")
        return intent
    }

    fun publish(context: Context, connected: Boolean, telemetry: Obd2Telemetry?) {
        context.sendBroadcast(
            buildIntent(
                connected = connected,
                telemetry = telemetry,
                nowEpochMillis = System.currentTimeMillis(),
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            )
        )
    }

    fun publishDisabled(context: Context) {
        context.sendBroadcast(
            buildIntent(
                connected = false,
                telemetry = null,
                nowEpochMillis = System.currentTimeMillis(),
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                statusOverride = "transmission_disabled"
            )
        )
    }

    private fun isFreshSample(sampleAtNanos: Long?, queryDurationMillis: Long?, nowElapsedRealtimeNanos: Long): Boolean {
        if (sampleAtNanos == null || sampleAtNanos <= 0L ||
            nowElapsedRealtimeNanos < sampleAtNanos ||
            nowElapsedRealtimeNanos - sampleAtNanos > Obd2SharedState.SPEED_MAX_AGE_NANOS
        ) return false
        return queryDurationMillis != null &&
            queryDurationMillis in 0L..Obd2SpeedSamplePolicy.MAXIMUM_QUERY_DURATION_MILLIS
    }

    private const val MAX_TELEMETRY_AGE_MILLIS = 5_000L
}
