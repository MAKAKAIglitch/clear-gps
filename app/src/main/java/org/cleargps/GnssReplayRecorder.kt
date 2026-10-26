package org.cleargps

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import org.cleargps.core.FilterDecision
import org.cleargps.core.GnssReplayCodec
import org.cleargps.core.GnssReplayEvent
import org.cleargps.core.GnssReplayMeasurement
import org.cleargps.core.GnssReplayPositionSource
import org.cleargps.core.GnssReplaySatellite
import org.cleargps.core.GnssReplaySensorType
import org.cleargps.core.GnssRfSnapshot
import org.cleargps.core.GnssSignalSnapshot
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos

/** Opt-in recorder. All potentially blocking appends are serialized on a private writer thread. */
internal object GnssReplayRecorder {
    const val KEY_ENABLED = "gnss_replay_recording_enabled"
    private const val KEY_START_NANOS = "gnss_replay_start_elapsed_nanos"
    private const val KEY_LAST_STOP = "gnss_replay_last_stop_reason"
    private const val MAX_FILE_BYTES = GnssReplayCodec.MAX_BYTES
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val SENSOR_SAMPLE_INTERVAL_NANOS = 200_000_000L // At most 5 samples/second per sensor.
    private const val SENSOR_SAMPLE_PERIOD_MICROS = 200_000

    private data class SensorSample(
        val type: GnssReplaySensorType,
        val calibrated: Boolean,
        val x: Double,
        val y: Double,
        val z: Double
    )

    private val lock = Any()
    private val sensorLock = Any()
    private var activeSensorManager: SensorManager? = null
    private var activeSensorListener: SensorEventListener? = null
    private val lastSensorSampleAtNanos = mutableMapOf<Int, Long>()
    private val pending = AtomicInteger(0)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ClearGPS-replay-writer").apply { isDaemon = true }
    }
    @Volatile private var startedAtNanos = 0L
    private var originLatitude: Double? = null
    private var originLongitude: Double? = null
    private var lastOffsetNanos = 0L
    private var eventCount = 0

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
    private fun file(context: Context) = File(File(context.filesDir, "diagnostics"), "gnss-replay.cgreplay")

    private fun enqueue(action: () -> Unit) {
        pending.incrementAndGet()
        writer.execute {
            try { synchronized(lock) { action() } }
            finally { pending.decrementAndGet() }
        }
    }

    fun isRecording(context: Context): Boolean = synchronized(lock) {
        val p=prefs(context)
        if (p.getBoolean(KEY_ENABLED,false) && startedAtNanos<=0L) {
            p.edit().putBoolean(KEY_ENABLED,false).putString(KEY_LAST_STOP,"INTERRUPTED").remove(KEY_START_NANOS).apply()
            return@synchronized false
        }
        p.getBoolean(KEY_ENABLED,false) && startedAtNanos>0L
    }

    fun fileSize(context: Context): Long = file(context).takeIf { it.isFile }?.length() ?: 0L
    fun hasSavedLog(context: Context): Boolean = fileSize(context) > GnssReplayCodec.FORMAT_HEADER.length + 1L
    fun canStart(context: Context): Boolean = synchronized(lock) {
        val p=prefs(context)
        val mode=p.getString(ClearGpsPreferences.KEY_POSITION_MODE,"auto") ?: "auto"
        pending.get()==0 && startedAtNanos<=0L &&
            p.getBoolean(LocationMonitorService.KEY_MONITORING,false) &&
            mode !in setOf("radio","radio_obd2") &&
            !p.getBoolean(KEY_ENABLED,false) && !hasSavedLog(context)
    }
    fun lastStopReason(context: Context)=prefs(context).getString(KEY_LAST_STOP,"").orEmpty()

    fun start(context: Context): Boolean = synchronized(lock) {
        val p=prefs(context)
        val mode=p.getString(ClearGpsPreferences.KEY_POSITION_MODE,"auto") ?: "auto"
        if (pending.get()!=0 || startedAtNanos>0L ||
            !p.getBoolean(LocationMonitorService.KEY_MONITORING,false) ||
            mode in setOf("radio","radio_obd2") || p.getBoolean(KEY_ENABLED,false) || hasSavedLog(context)
        ) return@synchronized false

        val target=file(context)
        if (target.parentFile?.exists()!=true && target.parentFile?.mkdirs()!=true) return@synchronized false
        if (target.exists() && !target.delete()) return@synchronized false
        startedAtNanos=SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        originLatitude=null; originLongitude=null; lastOffsetNanos=0L; eventCount=0
        try {
            target.writeText(GnssReplayCodec.encodeHeader()+"\n",Charsets.UTF_8)
            p.edit().putBoolean(KEY_ENABLED,true).putLong(KEY_START_NANOS,startedAtNanos)
                .putString(KEY_LAST_STOP,"").apply()
            // Sensor capture is best-effort: missing sensors must not prevent GNSS recording.
            startSensorCapture(context.applicationContext)
            true
        } catch (_: Exception) {
            startedAtNanos=0L
            p.edit().putBoolean(KEY_ENABLED,false).remove(KEY_START_NANOS).apply()
            false
        }
    }

    private fun startSensorCapture(context: Context) {
        synchronized(sensorLock) {
            stopSensorCaptureLocked()
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return@synchronized
            val sensors = listOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_ROTATION_VECTOR
            ).distinct().mapNotNull { type ->
                runCatching { manager.getDefaultSensor(type) }.getOrNull()
            }
            if (sensors.isEmpty()) return@synchronized

            val appContext = context.applicationContext
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

                override fun onSensorChanged(event: SensorEvent) {
                    val atNanos = event.timestamp
                    if (atNanos <= 0L || event.values.size < 3) return
                    val values = event.values
                    val sample = try {
                        when (event.sensor.type) {
                            Sensor.TYPE_ACCELEROMETER -> SensorSample(
                                GnssReplaySensorType.ACCELEROMETER, true,
                                values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                            )
                            Sensor.TYPE_GYROSCOPE -> SensorSample(
                                GnssReplaySensorType.GYROSCOPE, true,
                                values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                            )
                            Sensor.TYPE_MAGNETIC_FIELD -> SensorSample(
                                GnssReplaySensorType.MAGNETOMETER, true,
                                values[0].toDouble(), values[1].toDouble(), values[2].toDouble()
                            )
                            Sensor.TYPE_ROTATION_VECTOR -> {
                                val rotationMatrix = FloatArray(9)
                                val orientation = FloatArray(3)
                                SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                                SensorManager.getOrientation(rotationMatrix, orientation)
                                SensorSample(
                                    GnssReplaySensorType.ORIENTATION_DEGREES, true,
                                    normalizeHeading(Math.toDegrees(orientation[0].toDouble())),
                                    Math.toDegrees(orientation[1].toDouble()),
                                    Math.toDegrees(orientation[2].toDouble())
                                )
                            }
                            else -> null
                        }
                    } catch (_: RuntimeException) {
                        null
                    } ?: return

                    if (!sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite()) return
                    val accepted = synchronized(sensorLock) {
                        if (activeSensorListener !== this ||
                            !prefs(appContext).getBoolean(KEY_ENABLED, false)
                        ) return@synchronized false
                        val last = lastSensorSampleAtNanos[event.sensor.type]
                        if (last != null &&
                            (atNanos <= last || atNanos - last < SENSOR_SAMPLE_INTERVAL_NANOS)
                        ) {
                            false
                        } else {
                            lastSensorSampleAtNanos[event.sensor.type] = atNanos
                            true
                        }
                    }
                    if (accepted) {
                        recordSensor(
                            appContext, atNanos, sample.type, sample.calibrated,
                            sample.x, sample.y, sample.z
                        )
                    }
                }
            }

            activeSensorManager = manager
            activeSensorListener = listener
            val registered = sensors.count { sensor ->
                try {
                    manager.registerListener(listener, sensor, SENSOR_SAMPLE_PERIOD_MICROS)
                } catch (_: RuntimeException) {
                    false
                }
            }
            if (registered == 0) stopSensorCaptureLocked()
        }
    }

    private fun stopSensorCapture() {
        synchronized(sensorLock) { stopSensorCaptureLocked() }
    }

    private fun stopSensorCaptureLocked() {
        val manager = activeSensorManager
        val listener = activeSensorListener
        activeSensorManager = null
        activeSensorListener = null
        lastSensorSampleAtNanos.clear()
        if (manager != null && listener != null) {
            try {
                manager.unregisterListener(listener)
            } catch (_: RuntimeException) {
                // Recorder shutdown must not affect the foreground monitor.
            }
        }
    }

    fun stop(context: Context, reason: String = "USER_STOP") {
        val p=prefs(context)
        val normalized=reason.takeIf { it in setOf("USER_STOP","SERVICE_STOP","SIZE_LIMIT","INTERRUPTED") } ?: "SERVICE_STOP"
        val wasEnabled=p.getBoolean(KEY_ENABLED,false)
        p.edit().putBoolean(KEY_ENABLED,false).remove(KEY_START_NANOS)
            .putString(KEY_LAST_STOP,normalized).apply()
        stopSensorCapture()
        if (wasEnabled || startedAtNanos>0L) enqueue { stopWorker(context,normalized) }
    }

    /** A process restart loses the in-memory ENU origin; preserve a readable partial file but stop capture. */
    fun reconcileInterruptedSession(context: Context) = synchronized(lock) {
        val p=prefs(context)
        if (p.getBoolean(KEY_ENABLED,false) && startedAtNanos<=0L) {
            p.edit().putBoolean(KEY_ENABLED,false).remove(KEY_START_NANOS)
                .putString(KEY_LAST_STOP,"INTERRUPTED").apply()
        }
    }

    private fun stopWorker(context: Context, reason: String) {
        stopSensorCapture()
        if (startedAtNanos>0L) {
            appendLines(context,listOf(GnssReplayCodec.encodeFooter(lastOffsetNanos,eventCount,reason)),false)
        }
        startedAtNanos=0L; originLatitude=null; originLongitude=null; lastOffsetNanos=0L; eventCount=0
        prefs(context).edit().putBoolean(KEY_ENABLED,false).remove(KEY_START_NANOS).putString(KEY_LAST_STOP,reason).apply()
    }

    fun recordSignal(context: Context, atNanos: Long, snapshot: GnssSignalSnapshot, satellites: List<GnssReplaySatellite>) {
        if (!prefs(context).getBoolean(KEY_ENABLED,false) || startedAtNanos<=0L) return
        enqueue {
            appendEvent(context,atNanos) { offset ->
                GnssReplayEvent.Signal(offset,snapshot.copy(elapsedRealtimeNanos=offset+1L),satellites)
            }
        }
    }

    fun recordRf(context: Context, atNanos: Long, snapshot: GnssRfSnapshot, measurements: List<GnssReplayMeasurement>) {
        if (!prefs(context).getBoolean(KEY_ENABLED,false) || startedAtNanos<=0L) return
        enqueue {
            appendEvent(context,atNanos) { offset ->
                GnssReplayEvent.Rf(offset,snapshot.copy(elapsedRealtimeNanos=offset+1L),measurements)
            }
        }
    }

    fun recordSensor(
        context: Context,
        atNanos: Long,
        type: GnssReplaySensorType,
        calibrated: Boolean,
        x: Double,
        y: Double,
        z: Double
    ) {
        if (!prefs(context).getBoolean(KEY_ENABLED, false) || startedAtNanos <= 0L ||
            atNanos <= 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()
        ) return
        val valid = if (type == GnssReplaySensorType.ORIENTATION_DEGREES) {
            x in 0.0..360.0 && y in -180.0..180.0 && z in -180.0..180.0
        } else {
            kotlin.math.abs(x) <= 100_000.0 &&
                kotlin.math.abs(y) <= 100_000.0 &&
                kotlin.math.abs(z) <= 100_000.0
        }
        if (!valid) return
        enqueue {
            appendEvent(context, atNanos) { offset ->
                GnssReplayEvent.Sensor(offset, type, calibrated, x, y, z, null, null, null)
            }
        }
    }

    fun recordPosition(context: Context, location: Location, inZone: Boolean, decision: FilterDecision) {
        if (!prefs(context).getBoolean(KEY_ENABLED,false) || startedAtNanos<=0L ||
            location.provider !in setOf("gps","network") || location.elapsedRealtimeNanos<=0L
        ) return
        val copy=Location(location)
        val state=decision.state.name
        val score=decision.riskScore
        val decisionSource=decision.source
        enqueue {
            if (!copy.latitude.isFinite() || copy.latitude !in -90.0..90.0 ||
                !copy.longitude.isFinite() || copy.longitude !in -180.0..180.0) return@enqueue
            if (copy.provider=="gps" && originLatitude==null) {
                originLatitude=copy.latitude
                originLongitude=copy.longitude
            }
            val originLat=originLatitude ?: return@enqueue
            val originLon=originLongitude ?: return@enqueue
            val east=Math.toRadians(normalizeLongitude(copy.longitude-originLon))*EARTH_RADIUS_METERS*cos(Math.toRadians(originLat))
            val north=Math.toRadians(copy.latitude-originLat)*EARTH_RADIUS_METERS
            appendEvent(context,copy.elapsedRealtimeNanos) { offset ->
                GnssReplayEvent.Position(
                    offsetNanos=offset,
                    source=if (copy.provider=="gps") GnssReplayPositionSource.GPS else GnssReplayPositionSource.NETWORK,
                    eastMeters=east,northMeters=north,
                    accuracyMeters=if (copy.hasAccuracy() && copy.accuracy.isFinite()) copy.accuracy.toDouble() else 0.0,
                    speedMetersPerSecond=if (copy.hasSpeed() && copy.speed.isFinite() && copy.speed>=0f) copy.speed.toDouble() else null,
                    speedAccuracyMetersPerSecond=if (android.os.Build.VERSION.SDK_INT>=26 && copy.hasSpeedAccuracy() &&
                        copy.speedAccuracyMetersPerSecond.isFinite() && copy.speedAccuracyMetersPerSecond>=0f)
                        copy.speedAccuracyMetersPerSecond.toDouble() else null,
                    bearingDegrees=if (copy.hasBearing() && copy.bearing.isFinite()) copy.bearing.toDouble().mod(360.0) else null,
                    bearingAccuracyDegrees=if (android.os.Build.VERSION.SDK_INT>=26 && copy.hasBearingAccuracy() &&
                        copy.bearingAccuracyDegrees.isFinite() && copy.bearingAccuracyDegrees>=0f)
                        copy.bearingAccuracyDegrees.toDouble().coerceIn(0.0,360.0) else null,
                    inRiskZone=inZone,recordedRiskScore=score.coerceIn(0,100),recordedState=state,
                    recordedDecisionSource=decisionSource.takeIf {
                        it in GnssReplayEvent.ALLOWED_DECISION_SOURCES
                    } ?: "NONE"
                )
            }
        }
    }

    private fun appendEvent(context: Context, atNanos: Long, build: (Long)->GnssReplayEvent) {
        if (startedAtNanos<=0L || atNanos<startedAtNanos || eventCount>=GnssReplayCodec.MAX_EVENTS) {
            if (eventCount>=GnssReplayCodec.MAX_EVENTS) stopWorker(context,"SIZE_LIMIT")
            return
        }
        val offset=maxOf(lastOffsetNanos,atNanos-startedAtNanos)
        try {
            val lines=GnssReplayCodec.encode(build(offset))
            val ok=appendLines(context,lines,true)
            if (ok && startedAtNanos>0L) { lastOffsetNanos=offset; eventCount++ }
        } catch (_: RuntimeException) {
            // Invalid receiver values must never interfere with the live monitoring callback.
        }
    }

    private fun appendLines(context: Context, lines: List<String>, closeOnLimit: Boolean): Boolean {
        val target=file(context)
        val bytes=lines.joinToString("\n",postfix="\n").toByteArray(Charsets.UTF_8)
        if (!target.exists() || target.length()+bytes.size>MAX_FILE_BYTES) {
            if (closeOnLimit) stopWorker(context,"SIZE_LIMIT")
            return false
        }
        return try {
            FileOutputStream(target,true).use { stream -> stream.write(bytes); stream.fd.sync() }
            true
        } catch (_: Exception) {
            if (closeOnLimit) stopWorker(context,"SIZE_LIMIT")
            false
        }
    }

    fun readLogBytes(context: Context): ByteArray =
        file(context).takeIf { it.isFile && it.length()<=MAX_FILE_BYTES }?.readBytes() ?: byteArrayOf()

    fun clear(context: Context) {
        stop(context,"USER_STOP")
        enqueue {
            file(context).delete()
            prefs(context).edit().putString(KEY_LAST_STOP,"").apply()
        }
    }

    private fun normalizeHeading(degrees: Double): Double {
        val normalized = degrees % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    private fun normalizeLongitude(degrees: Double): Double {
        var value=degrees
        while (value>180.0) value-=360.0
        while (value< -180.0) value+=360.0
        return value
    }
}
