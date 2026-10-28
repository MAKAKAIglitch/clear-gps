package org.cleargps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.hypot

/** Latest horizontal acceleration sample in the Android world frame (east/north), never a position. */
internal data class ImuHorizontalAccelerationSample(
    val eastMetersPerSecondSquared: Double,
    val northMetersPerSecondSquared: Double,
    val elapsedRealtimeNanos: Long,
    val rotationVectorElapsedRealtimeNanos: Long,
    val rotationVectorAccuracy: Int
)

/** Validation and expiry rules for a paired linear-acceleration + orientation sample. */
internal object ImuMotionSamplePolicy {
    const val MAXIMUM_ORIENTATION_SKEW_NANOS = 250_000_000L
    const val DEFAULT_MAXIMUM_AGE_NANOS = 500_000_000L
    const val MAXIMUM_HORIZONTAL_ACCELERATION_METERS_PER_SECOND_SQUARED = 50.0

    fun fromHorizontalAcceleration(
        eastMetersPerSecondSquared: Double,
        northMetersPerSecondSquared: Double,
        elapsedRealtimeNanos: Long,
        rotationVectorElapsedRealtimeNanos: Long,
        rotationVectorAccuracy: Int
    ): ImuHorizontalAccelerationSample? {
        if (elapsedRealtimeNanos <= 0L || rotationVectorElapsedRealtimeNanos <= 0L) return null
        if (rotationVectorAccuracy !in MINIMUM_ACCEPTABLE_ROTATION_ACCURACY..MAXIMUM_ROTATION_ACCURACY) return null
        if (abs(elapsedRealtimeNanos - rotationVectorElapsedRealtimeNanos) > MAXIMUM_ORIENTATION_SKEW_NANOS) {
            return null
        }
        if (!eastMetersPerSecondSquared.isFinite() || !northMetersPerSecondSquared.isFinite()) return null
        if (hypot(eastMetersPerSecondSquared, northMetersPerSecondSquared) >
            MAXIMUM_HORIZONTAL_ACCELERATION_METERS_PER_SECOND_SQUARED
        ) return null

        return ImuHorizontalAccelerationSample(
            eastMetersPerSecondSquared = eastMetersPerSecondSquared,
            northMetersPerSecondSquared = northMetersPerSecondSquared,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            rotationVectorElapsedRealtimeNanos = rotationVectorElapsedRealtimeNanos,
            rotationVectorAccuracy = rotationVectorAccuracy
        )
    }

    fun freshSample(
        sample: ImuHorizontalAccelerationSample?,
        nowElapsedRealtimeNanos: Long,
        maximumAgeNanos: Long = DEFAULT_MAXIMUM_AGE_NANOS
    ): ImuHorizontalAccelerationSample? {
        if (sample == null || nowElapsedRealtimeNanos <= 0L || maximumAgeNanos < 0L) return null
        if (sample.elapsedRealtimeNanos <= 0L ||
            sample.rotationVectorElapsedRealtimeNanos <= 0L ||
            sample.rotationVectorAccuracy !in MINIMUM_ACCEPTABLE_ROTATION_ACCURACY..MAXIMUM_ROTATION_ACCURACY ||
            !sample.eastMetersPerSecondSquared.isFinite() ||
            !sample.northMetersPerSecondSquared.isFinite() ||
            hypot(sample.eastMetersPerSecondSquared, sample.northMetersPerSecondSquared) >
                MAXIMUM_HORIZONTAL_ACCELERATION_METERS_PER_SECOND_SQUARED
        ) return null
        if (sample.elapsedRealtimeNanos > nowElapsedRealtimeNanos ||
            sample.rotationVectorElapsedRealtimeNanos > nowElapsedRealtimeNanos + MAXIMUM_ORIENTATION_SKEW_NANOS
        ) return null
        if (abs(sample.elapsedRealtimeNanos - sample.rotationVectorElapsedRealtimeNanos) >
            MAXIMUM_ORIENTATION_SKEW_NANOS
        ) return null
        return sample.takeIf {
            nowElapsedRealtimeNanos - it.elapsedRealtimeNanos <= maximumAgeNanos
        }
    }

    private const val MINIMUM_ACCEPTABLE_ROTATION_ACCURACY = 1
    private const val MAXIMUM_ROTATION_ACCURACY = 3
}

/** Bounded, process-memory-only IMU history. Samples are never written to disk or exported. */
internal object ImuMotionLiveStore {
    private const val MAX_HISTORY_SAMPLES = 128
    private const val HISTORY_RETENTION_NANOS = 5_000_000_000L
    const val DEFAULT_HISTORY_SAMPLE_MAXIMUM_AGE_NANOS = 2_250_000_000L

    @Volatile
    private var latest: ImuHorizontalAccelerationSample? = null
    private val history = mutableListOf<ImuHorizontalAccelerationSample>()

    @Synchronized
    fun publish(sample: ImuHorizontalAccelerationSample) {
        if (ImuMotionSamplePolicy.freshSample(
                sample,
                nowElapsedRealtimeNanos = sample.elapsedRealtimeNanos,
                maximumAgeNanos = 0L
            ) == null
        ) return
        val previous = history.lastOrNull()
        if (previous != null && sample.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) return
        history += sample
        while (history.size > MAX_HISTORY_SAMPLES ||
            (history.size > 1 &&
                history.last().elapsedRealtimeNanos - history.first().elapsedRealtimeNanos > HISTORY_RETENTION_NANOS)
        ) {
            history.removeAt(0)
        }
        latest = history.lastOrNull()
    }

    fun snapshot(
        nowElapsedRealtimeNanos: Long,
        maximumAgeNanos: Long = ImuMotionSamplePolicy.DEFAULT_MAXIMUM_AGE_NANOS
    ): ImuHorizontalAccelerationSample? =
        ImuMotionSamplePolicy.freshSample(latest, nowElapsedRealtimeNanos, maximumAgeNanos)

    /**
     * Returns a chronological slice for bounded dead reckoning. Every sample is revalidated against
     * the current monotonic clock. Caller-side logic must still reject gaps and excessive motion.
     */
    fun samplesSince(
        afterElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long,
        maximumAgeNanos: Long = DEFAULT_HISTORY_SAMPLE_MAXIMUM_AGE_NANOS
    ): List<ImuHorizontalAccelerationSample> {
        if (afterElapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos <= 0L ||
            afterElapsedRealtimeNanos > nowElapsedRealtimeNanos || maximumAgeNanos < 0L
        ) return emptyList()
        val retained = synchronized(this) { history.toList() }
        return retained.asSequence()
            .filter {
                it.elapsedRealtimeNanos >= afterElapsedRealtimeNanos &&
                    it.elapsedRealtimeNanos <= nowElapsedRealtimeNanos
            }
            .mapNotNull {
                ImuMotionSamplePolicy.freshSample(it, nowElapsedRealtimeNanos, maximumAgeNanos)
            }
            .toList()
    }

    @Synchronized
    fun clear() {
        history.clear()
        latest = null
    }
}

/**
 * Low-rate IMU reader. The rotation vector maps device-frame linear acceleration into Android's
 * east/north/up frame. Samples stay in bounded process memory and may support a short position
 * prediction; they never create an absolute anchor or change the GNSS trust score.
 */
internal class AndroidImuMotionReader(context: Context) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearAccelerationSensor = runCatching {
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    }.getOrNull()
    private val rotationVectorSensor = runCatching {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }.getOrNull()
    private val lock = Any()

    @Volatile
    private var registered = false
    private var latestRotation: RotationVectorSnapshot? = null

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        val manager = sensorManager
        val linearSensor = linearAccelerationSensor
        val rotationSensor = rotationVectorSensor
        if (manager == null || linearSensor == null || rotationSensor == null) {
            ImuMotionLiveStore.clear()
            return false
        }

        return try {
            val rotationRegistered = manager.registerListener(this, rotationSensor, SAMPLING_PERIOD_MICROS)
            val accelerationRegistered = rotationRegistered &&
                manager.registerListener(this, linearSensor, SAMPLING_PERIOD_MICROS)
            if (!rotationRegistered || !accelerationRegistered) {
                manager.unregisterListener(this)
                synchronized(lock) { latestRotation = null }
                registered = false
                ImuMotionLiveStore.clear()
                false
            } else {
                synchronized(lock) { latestRotation = null }
                registered = true
                true
            }
        } catch (_: SecurityException) {
            runCatching { manager.unregisterListener(this) }
            synchronized(lock) { latestRotation = null }
            registered = false
            ImuMotionLiveStore.clear()
            false
        } catch (_: RuntimeException) {
            runCatching { manager.unregisterListener(this) }
            synchronized(lock) { latestRotation = null }
            registered = false
            ImuMotionLiveStore.clear()
            false
        }
    }

    @Synchronized
    fun stop() {
        registered = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: RuntimeException) {
            // Sensor cleanup must not prevent location monitoring from stopping.
        } finally {
            synchronized(lock) { latestRotation = null }
            ImuMotionLiveStore.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        synchronized(lock) {
            if (!registered) return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> updateRotationVector(event)
                Sensor.TYPE_LINEAR_ACCELERATION -> updateLinearAcceleration(event)
            }
        }
    }

    private fun updateRotationVector(event: SensorEvent) {
        if (event.timestamp <= 0L) return
        val old = latestRotation
        if (old != null && event.timestamp <= old.elapsedRealtimeNanos) return
        if (event.accuracy !in MINIMUM_ACCEPTABLE_ROTATION_ACCURACY..MAXIMUM_ROTATION_ACCURACY ||
            event.values.size < 3 ||
            event.values.take(4).any { !it.isFinite() }
        ) {
            latestRotation = null
            return
        }
        // The matrix API needs only x/y/z and optional w; discard any vendor-specific extra value.
        latestRotation = RotationVectorSnapshot(
            vector = event.values.take(4).toFloatArray(),
            elapsedRealtimeNanos = event.timestamp,
            accuracy = event.accuracy
        )
    }

    private fun updateLinearAcceleration(event: SensorEvent) {
        if (event.timestamp <= 0L || event.values.size < 3 || event.values.take(3).any { !it.isFinite() }) return
        val orientation = latestRotation ?: return
        if (abs(event.timestamp - orientation.elapsedRealtimeNanos) >
            ImuMotionSamplePolicy.MAXIMUM_ORIENTATION_SKEW_NANOS
        ) return

        val rotationMatrix = FloatArray(9)
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, orientation.vector)
        } catch (_: RuntimeException) {
            return
        }
        if (rotationMatrix.any { !it.isFinite() }) return

        val ax = event.values[0].toDouble()
        val ay = event.values[1].toDouble()
        val az = event.values[2].toDouble()
        // Rotation matrix maps phone/device axes into Android world axes: east, north, up.
        val east = rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
        val north = rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
        val sample = ImuMotionSamplePolicy.fromHorizontalAcceleration(
            eastMetersPerSecondSquared = east.toDouble(),
            northMetersPerSecondSquared = north.toDouble(),
            elapsedRealtimeNanos = event.timestamp,
            rotationVectorElapsedRealtimeNanos = orientation.elapsedRealtimeNanos,
            rotationVectorAccuracy = orientation.accuracy
        ) ?: return
        ImuMotionLiveStore.publish(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            synchronized(lock) { latestRotation = null }
        }
    }

    private data class RotationVectorSnapshot(
        val vector: FloatArray,
        val elapsedRealtimeNanos: Long,
        val accuracy: Int
    )

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 100_000 // 10 Hz; diagnostic only, lower power than game cadence.
        const val MINIMUM_ACCEPTABLE_ROTATION_ACCURACY = 1
        const val MAXIMUM_ROTATION_ACCURACY = 3
    }
}
