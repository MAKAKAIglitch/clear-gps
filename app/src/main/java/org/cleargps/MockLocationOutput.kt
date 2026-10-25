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
    private val registeredProviders = linkedSetOf<String>()

    /** Human-readable last failure, shown by the service and persisted diagnostics. */
    var lastError: String? = null
        private set

    /** A secondary provider can fail while GPS output remains useful. */
    var lastWarning: String? = null
        private set

    /** Provider names for which the most recent location update was accepted by Android. */
    var lastPublishedProviders: Set<String> = emptySet()
        private set

    /**
     * Publishes the calculated fix as a GPS mock location. In radio-only mode callers can also
     * publish the same fix through NETWORK_PROVIDER so apps that request network locations receive
     * it too. Never enable the second provider while the service needs Android network locations
     * as an input (GPS/Auto modes).
     */
    fun publish(source: Location, alsoPublishNetworkProvider: Boolean = false): Boolean {
        if (!source.latitude.isFinite() || source.latitude !in -90.0..90.0 ||
            !source.longitude.isFinite() || source.longitude !in -180.0..180.0) {
            return fail(
                "Отвергнуты некорректные координаты источника",
                IllegalArgumentException("Latitude/longitude out of range or not finite")
            )
        }

        return try {
            ensureTestProvider(LocationManager.GPS_PROVIDER)
            val gpsLocation = outputLocation(LocationManager.GPS_PROVIDER, source)
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, gpsLocation)
            lastPublishedProviders = setOf(LocationManager.GPS_PROVIDER)
            lastWarning = null

            if (alsoPublishNetworkProvider) {
                try {
                    ensureTestProvider(LocationManager.NETWORK_PROVIDER)
                    val networkLocation = outputLocation(LocationManager.NETWORK_PROVIDER, source)
                    manager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, networkLocation)
                    lastPublishedProviders = setOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER
                    )
                } catch (error: Exception) {
                    lastWarning = "Обновлён GPS-провайдер, но сетевой mock-провайдер недоступен: " +
                        (error.message?.take(140) ?: error.javaClass.simpleName)
                    unregisterProvider(LocationManager.NETWORK_PROVIDER)
                    DiagnosticLogStore.log(appContext, "MOCK", lastWarning.orEmpty(), "WARN")
                }
            } else if (registeredProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                // A source-mode transition must not leave an obsolete network override active.
                unregisterProvider(LocationManager.NETWORK_PROVIDER)
            }

            appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LocationMonitorService.KEY_MOCK_LAST_PUBLISHED_ELAPSED_MS, SystemClock.elapsedRealtime())
                .apply()
            lastError = null
            val providersText = lastPublishedProviders.joinToString(",")
            DiagnosticLogStore.log(
                appContext, "MOCK",
                "Mock location update accepted by Android; providers=$providersText; " +
                    "accuracy≈${gpsLocation.accuracy.toInt()} m" +
                    (lastWarning?.let { "; warning=$it" } ?: "")
            )
            true
        } catch (error: SecurityException) {
            fail("Нет разрешения на mock GPS. Выбери Clear GPS в настройках разработчика.", error)
        } catch (error: IllegalArgumentException) {
            fail("Android отклонил test location provider.", error)
        } catch (error: RuntimeException) {
            fail("Ошибка Android при публикации mock location.", error)
        }
    }

    private fun ensureTestProvider(provider: String) {
        if (registeredProviders.contains(provider)) {
            try {
                manager.setTestProviderEnabled(provider, true)
                return
            } catch (_: IllegalArgumentException) {
                registeredProviders.remove(provider)
            }
        }

        try {
            addTestProvider(provider)
        } catch (_: IllegalArgumentException) {
            // A process/service restart may lose the local registration flag while Android
            // still retains this test provider. Remove the stale registration and retry once.
            DiagnosticLogStore.log(
                appContext, "MOCK",
                "Test provider $provider уже существует; пробую восстановить регистрацию",
                "WARN"
            )
            try {
                manager.removeTestProvider(provider)
            } catch (_: IllegalArgumentException) {
                // It was not actually registered; try a clean add below.
            }
            addTestProvider(provider)
        }

        registeredProviders.add(provider)
        // Mark registered before enabling, so disable() can clean a partially initialized provider.
        manager.setTestProviderEnabled(provider, true)
        DiagnosticLogStore.log(appContext, "MOCK", "Test provider $provider зарегистрирован и включён")
    }

    private fun addTestProvider(provider: String) {
        manager.addTestProvider(
            provider,
            false, false, false, false,
            true, true, true,
            Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
        )
    }

    private fun outputLocation(provider: String, source: Location): Location {
        val sourceAccuracy = source.accuracy.takeIf {
            source.hasAccuracy() && it.isFinite() && it > 0f
        } ?: DEFAULT_ACCURACY_METERS
        return Location(provider).apply {
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
    }

    private fun fail(prefix: String, error: Exception): Boolean {
        val detail = error.message?.take(180)?.takeIf { it.isNotBlank() }
        lastError = prefix + (detail?.let { " Подробности: $it" } ?: "")
        DiagnosticLogStore.log(appContext, "MOCK", lastError.orEmpty(), "ERROR")
        disable()
        return false
    }

    private fun unregisterProvider(provider: String) {
        try {
            manager.removeTestProvider(provider)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: RuntimeException) {
        } finally {
            registeredProviders.remove(provider)
            lastPublishedProviders = lastPublishedProviders - provider
        }
    }

    fun disable() {
        val providersBeforeDisable = registeredProviders.toList()
        // Removing an absent provider is harmless. Also try both names in case the process died
        // after registering one provider but before saving its local registration state.
        unregisterProvider(LocationManager.NETWORK_PROVIDER)
        unregisterProvider(LocationManager.GPS_PROVIDER)
        lastPublishedProviders = emptySet()
        lastWarning = null
        if (providersBeforeDisable.isNotEmpty()) {
            DiagnosticLogStore.log(
                appContext, "MOCK",
                "Test location providers removed: " + providersBeforeDisable.joinToString(",")
            )
        }
    }

    private companion object {
        const val DEFAULT_ACCURACY_METERS = 100f
        const val MAX_ACCURACY_METERS = 100_000f
    }
}