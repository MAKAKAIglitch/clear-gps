package org.cleargps

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/** Optional adapter; requires explicit user selection as the Android mock-location app. */
class MockLocationOutput(context: Context) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var registered = false

    /** Human-readable last failure, shown by the service and persisted diagnostics. */
    var lastError: String? = null
        private set

    fun publish(source: Location): Boolean {
        if (!source.latitude.isFinite() || source.latitude !in -90.0..90.0 ||
            !source.longitude.isFinite() || source.longitude !in -180.0..180.0) {
            lastError = "Отвергнуты некорректные координаты источника"
            DiagnosticLogStore.log(appContext, "MOCK", lastError!!, "ERROR")
            return false
        }

        return try {
            if (!registered) {
                try {
                    addGpsTestProvider()
                } catch (duplicate: IllegalArgumentException) {
                    // A killed/recreated foreground service may lose its local flag while
                    // LocationManager still retains the old test provider. Clean that stale
                    // registration and retry exactly once instead of failing every later tick.
                    DiagnosticLogStore.log(
                        appContext, "MOCK",
                        "GPS test provider уже существует; пробую восстановить регистрацию",
                        "WARN"
                    )
                    try {
                        manager.removeTestProvider(LocationManager.GPS_PROVIDER)
                    } catch (_: IllegalArgumentException) {
                        // It was not actually registered; try a clean add below.
                    }
                    addGpsTestProvider()
                }
                // Mark registered immediately so a failure enabling the provider can clean it up.
                registered = true
                manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                DiagnosticLogStore.log(appContext, "MOCK", "Test GPS provider зарегистрирован и включён")
            }

            val sourceAccuracy = source.accuracy.takeIf {
                source.hasAccuracy() && it.isFinite() && it > 0f
            } ?: DEFAULT_ACCURACY_METERS
            val out = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = source.latitude
                longitude = source.longitude
                accuracy = sourceAccuracy.coerceIn(1f, MAX_ACCURACY_METERS)
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (source.hasAltitude() && source.altitude.isFinite()) altitude = source.altitude
                if (source.hasSpeed() && source.speed.isFinite() && source.speed >= 0f) speed = source.speed
                if (source.hasBearing() && source.bearing.isFinite()) bearing = source.bearing
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (source.hasSpeedAccuracy() && source.speedAccuracyMetersPerSecond.isFinite() &&
                        source.speedAccuracyMetersPerSecond >= 0f) {
                        speedAccuracyMetersPerSecond = source.speedAccuracyMetersPerSecond
                    }
                    if (source.hasBearingAccuracy() && source.bearingAccuracyDegrees.isFinite() &&
                        source.bearingAccuracyDegrees >= 0f) {
                        bearingAccuracyDegrees = source.bearingAccuracyDegrees
                    }
                    if (source.hasVerticalAccuracy() && source.verticalAccuracyMeters.isFinite() &&
                        source.verticalAccuracyMeters >= 0f) {
                        verticalAccuracyMeters = source.verticalAccuracyMeters
                    }
                }
            }
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, out)
            lastError = null
            DiagnosticLogStore.log(
                appContext, "MOCK",
                "Mock GPS update опубликован; accuracy≈${out.accuracy.toInt()} м"
            )
            true
        } catch (error: SecurityException) {
            fail("Нет разрешения на mock GPS. Выбери Clear GPS в настройках разработчика.", error)
        } catch (error: IllegalArgumentException) {
            fail("Android отклонил test GPS provider.", error)
        } catch (error: RuntimeException) {
            fail("Ошибка Android при публикации mock GPS.", error)
        }
    }

    private fun addGpsTestProvider() {
        manager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false, false, false, false,
            true, true, true,
            Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
        )
    }

    private fun fail(prefix: String, error: Exception): Boolean {
        val detail = error.message?.take(180)?.takeIf { it.isNotBlank() }
        lastError = prefix + (detail?.let { " Подробности: $it" } ?: "")
        DiagnosticLogStore.log(appContext, "MOCK", lastError.orEmpty(), "ERROR")
        disable()
        return false
    }

    fun disable() {
        val wasRegistered = registered
        try {
            // Removing an absent provider is harmless. This also recovers if the service was
            // recreated and the provider registration flag was lost.
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: RuntimeException) {
        } finally {
            registered = false
        }
        if (wasRegistered) DiagnosticLogStore.log(appContext, "MOCK", "Test GPS provider удалён")
    }

    private companion object {
        const val DEFAULT_ACCURACY_METERS = 100f
        const val MAX_ACCURACY_METERS = 100_000f
    }
}
