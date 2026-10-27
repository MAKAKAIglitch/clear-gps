package org.cleargps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * A raw, short-lived atmospheric pressure sample from Android TYPE_PRESSURE.
 *
 * Android reports pressure in hectopascals; internal storage uses pascals. The sensor's
 * resolution is retained as metadata, not mistaken for calibrated measurement uncertainty.
 */
internal data class BarometerPressureSample(
    val pressurePascals: Double,
    val sensorResolutionPascals: Double?,
    val elapsedRealtimeNanos: Long
)

internal object BarometerPressureSamplePolicy {
    fun fromHectopascals(
        pressureHectopascals: Float,
        resolutionHectopascals: Float?,
        elapsedRealtimeNanos: Long
    ): BarometerPressureSample? {
        val pressure = pressureHectopascals.toDouble()
        if (!pressure.isFinite() || pressure !in 300.0..1_200.0 || elapsedRealtimeNanos <= 0L) {
            return null
        }
        val resolution = resolutionHectopascals?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.times(100.0)
        return BarometerPressureSample(
            pressurePascals = pressure * 100.0,
            sensorResolutionPascals = resolution,
            elapsedRealtimeNanos = elapsedRealtimeNanos
        )
    }

    fun freshSample(
        sample: BarometerPressureSample?,
        nowElapsedRealtimeNanos: Long,
        maximumAgeNanos: Long = DEFAULT_MAXIMUM_AGE_NANOS
    ): BarometerPressureSample? {
        if (sample == null || nowElapsedRealtimeNanos <= 0L || maximumAgeNanos <= 0L) return null
        if (!sample.pressurePascals.isFinite() || sample.pressurePascals !in 30_000.0..120_000.0) {
            return null
        }
        if (sample.elapsedRealtimeNanos <= 0L ||
            sample.elapsedRealtimeNanos > nowElapsedRealtimeNanos
        ) return null
        return sample.takeIf {
            nowElapsedRealtimeNanos - it.elapsedRealtimeNanos <= maximumAgeNanos
        }
    }

    const val DEFAULT_MAXIMUM_AGE_NANOS = 5_000_000_000L
}

/** Latest sample stays in process memory only; no raw pressure is persisted or exported. */
internal object BarometerPressureLiveStore {
    @Volatile
    private var latestSample: BarometerPressureSample? = null

    fun publish(sample: BarometerPressureSample) {
        latestSample = sample
    }

    fun snapshot(
        nowElapsedRealtimeNanos: Long,
        maximumAgeNanos: Long = BarometerPressureSamplePolicy.DEFAULT_MAXIMUM_AGE_NANOS
    ): BarometerPressureSample? =
        BarometerPressureSamplePolicy.freshSample(
            latestSample,
            nowElapsedRealtimeNanos,
            maximumAgeNanos
        )

    fun clear() {
        latestSample = null
    }
}

/**
 * Lightweight reader started only while location monitoring is active. The one-second
 * sampling period is a hint to Android; the OS/vendor may deliver at a different rate.
 */
internal class AndroidBarometerSensorReader(context: Context) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val pressureSensor = runCatching {
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    }.getOrNull()

    @Volatile
    private var registered = false

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        val manager = sensorManager
        val sensor = pressureSensor
        if (manager == null || sensor == null) {
            BarometerPressureLiveStore.clear()
            return false
        }
        return try {
            val success = manager.registerListener(this, sensor, SAMPLING_PERIOD_MICROS)
            registered = success
            if (!success) BarometerPressureLiveStore.clear()
            success
        } catch (_: SecurityException) {
            registered = false
            BarometerPressureLiveStore.clear()
            false
        } catch (_: RuntimeException) {
            registered = false
            BarometerPressureLiveStore.clear()
            false
        }
    }

    @Synchronized
    fun stop() {
        registered = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: RuntimeException) {
            // Sensor cleanup must never prevent the location service from stopping.
        } finally {
            BarometerPressureLiveStore.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.sensor.type != Sensor.TYPE_PRESSURE || event.values.isEmpty()) return
        val sample = BarometerPressureSamplePolicy.fromHectopascals(
            pressureHectopascals = event.values[0],
            resolutionHectopascals = event.sensor.resolution,
            elapsedRealtimeNanos = event.timestamp
        ) ?: return
        BarometerPressureLiveStore.publish(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 1_000_000
    }
}
