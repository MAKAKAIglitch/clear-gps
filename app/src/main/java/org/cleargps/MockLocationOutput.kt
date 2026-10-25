package org.cleargps

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Determines which Android providers may be replaced without disrupting Clear GPS's physical
 * inputs. The direct GPS provider is intentionally omitted in GPS/Auto modes.
 */
internal fun systemMockProviderNames(
    publishGpsProvider: Boolean,
    alsoPublishNetworkProvider: Boolean,
    apiLevel: Int
): List<String> = buildList {
    if (publishGpsProvider) add(LocationManager.GPS_PROVIDER)
    if (publishGpsProvider && alsoPublishNetworkProvider) add(LocationManager.NETWORK_PROVIDER)
    if (apiLevel >= Build.VERSION_CODES.S) add("fused")
}

/** Publishes a calculated fix through Android's test providers and Google Play services FLP. */
class MockLocationOutput(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val providerLock = Any()
    private val registeredProviders = linkedSetOf<String>()

    private val fusedClient: FusedLocationProviderClient? = try {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS
        ) LocationServices.getFusedLocationProviderClient(appContext) else null
    } catch (_: RuntimeException) {
        null
    }

    private val fusedLock = Any()
    private val fusedExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ClearGPS-FusedMock").apply { isDaemon = true }
    }
    private var fusedDesiredEnabled = false
    private var fusedPendingLocation: Location? = null
    private var fusedGeneration = 0L
    private var fusedWorkerRunning = false
    private var fusedModeActive = false
    private var fusedRetryAfterElapsedMs = 0L

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastWarning: String? = null
        private set

    @Volatile
    var lastPublishedProviders: Set<String> = emptySet()
        private set

    @Volatile
    private var lastFusedError: String? = null

    /**
     * @param publishGpsProvider false in GPS/Auto modes so test-provider registration does not
     * replace the physical GPS input that Clear GPS is actively filtering.
     * @param alsoPublishNetworkProvider true in radio-only mode, where Android network location is
     * not used as an input. The system fused provider and Google FLP are updated independently.
     */
    fun publish(
        source: Location,
        alsoPublishNetworkProvider: Boolean = false,
        publishGpsProvider: Boolean = true
    ): Boolean {
        if (!source.latitude.isFinite() || source.latitude !in -90.0..90.0 ||
            !source.longitude.isFinite() || source.longitude !in -180.0..180.0
        ) {
            return fail(
                "Отвергнуты некорректные координаты источника",
                IllegalArgumentException("Latitude/longitude out of range or not finite")
            )
        }

        val desiredProviders = systemMockProviderNames(
            publishGpsProvider = publishGpsProvider,
            alsoPublishNetworkProvider = alsoPublishNetworkProvider,
            apiLevel = Build.VERSION.SDK_INT
        )
        synchronized(providerLock) {
            registeredProviders.toList().filterNot { it in desiredProviders }.forEach {
                cleanupProviderLocked(it)
            }
        }

        val providerErrors = mutableListOf<String>()
        val successfulProviders = linkedSetOf<String>()
        desiredProviders.forEach { provider ->
            val error = publishToSystemProvider(provider, source)
            if (error == null) successfulProviders += provider else providerErrors += error
        }
        lastPublishedProviders = successfulProviders.toSet()

        val fusedQueued = queueFusedLocation(makeOutputLocation(source, LocationManager.GPS_PROVIDER))
        if (successfulProviders.isNotEmpty()) {
            appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LocationMonitorService.KEY_MOCK_LAST_PUBLISHED_ELAPSED_MS, SystemClock.elapsedRealtime())
                .apply()
        }

        if (successfulProviders.isNotEmpty() || fusedQueued) {
            lastError = null
            lastWarning = providerErrors.takeIf { it.isNotEmpty() }?.joinToString(" ")
            val routeText = buildList {
                if (successfulProviders.isNotEmpty()) add("system=" + successfulProviders.joinToString(","))
                if (fusedQueued) add("Google FLP queued")
            }.joinToString("; ")
            DiagnosticLogStore.log(
                appContext, "MOCK",
                "Mock update accepted; " + routeText + "; accuracy≈" +
                    makeOutputLocation(source, LocationManager.GPS_PROVIDER).accuracy.toInt() + " m" +
                    (lastWarning?.let { "; warning=$it" } ?: "")
            )
            return true
        }

        val reasons = providerErrors + listOfNotNull(lastFusedError)
        return fail(
            "Не удалось опубликовать рассчитанное местоположение. " +
                reasons.joinToString(" ") +
                " Проверь, что Clear GPS выбрано приложением фиктивного местоположения.",
            IllegalStateException("No mock-location output provider accepted the fix")
        )
    }

    private fun publishToSystemProvider(provider: String, source: Location): String? =
        synchronized(providerLock) {
            try {
                if (provider !in registeredProviders) {
                    try {
                        addTestProvider(provider)
                    } catch (_: IllegalArgumentException) {
                        // A service restart can lose the local flag while Android retains a test provider.
                        try {
                            manager.removeTestProvider(provider)
                        } catch (_: IllegalArgumentException) {
                            // It was not actually registered; retry a clean add below.
                        }
                        addTestProvider(provider)
                    }
                    registeredProviders += provider
                    manager.setTestProviderEnabled(provider, true)
                }
                manager.setTestProviderLocation(provider, makeOutputLocation(source, provider))
                null
            } catch (error: SecurityException) {
                cleanupProviderLocked(provider)
                "Android запретил публикацию через " + provider + ": " +
                    (error.message ?: "SecurityException")
            } catch (error: IllegalArgumentException) {
                cleanupProviderLocked(provider)
                "Android отклонил test provider " + provider + ": " +
                    (error.message ?: "IllegalArgumentException")
            } catch (error: RuntimeException) {
                cleanupProviderLocked(provider)
                "Ошибка системного провайдера " + provider + ": " + error.javaClass.simpleName
            }
        }

    private fun addTestProvider(provider: String) {
        manager.addTestProvider(
            provider,
            false, false, false, false,
            true, true, true,
            Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
        )
    }

    private fun makeOutputLocation(source: Location, provider: String): Location {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (source.hasSpeedAccuracy() && source.speedAccuracyMetersPerSecond.isFinite() &&
                    source.speedAccuracyMetersPerSecond >= 0f
                ) speedAccuracyMetersPerSecond = source.speedAccuracyMetersPerSecond
                if (source.hasBearingAccuracy() && source.bearingAccuracyDegrees.isFinite() &&
                    source.bearingAccuracyDegrees >= 0f
                ) bearingAccuracyDegrees = source.bearingAccuracyDegrees
                if (source.hasVerticalAccuracy() && source.verticalAccuracyMeters.isFinite() &&
                    source.verticalAccuracyMeters >= 0f
                ) verticalAccuracyMeters = source.verticalAccuracyMeters
            }
        }
    }

    private fun queueFusedLocation(location: Location): Boolean {
        val client = fusedClient
        if (client == null) {
            lastFusedError = "Google Play services Fused Location Provider недоступен."
            persistFusedStatus(
                false,
                "Google Play services недоступны: публикация через Google Fused Location Provider не используется."
            )
            return false
        }
        synchronized(fusedLock) {
            if (SystemClock.elapsedRealtime() < fusedRetryAfterElapsedMs) return false
            fusedDesiredEnabled = true
            fusedPendingLocation = Location(location)
            fusedGeneration++
            if (!fusedWorkerRunning) {
                fusedWorkerRunning = true
                try {
                    fusedExecutor.execute { drainFusedQueue(client) }
                } catch (error: RuntimeException) {
                    fusedWorkerRunning = false
                    fusedPendingLocation = null
                    fusedDesiredEnabled = false
                    lastFusedError = "Не удалось запустить Fused Location Provider: " +
                        error.javaClass.simpleName
                    persistFusedStatus(false, lastFusedError.orEmpty())
                    return false
                }
            }
        }
        return true
    }

    private data class FusedWork(
        val enable: Boolean,
        val generation: Long,
        val location: Location?,
        val enableModeFirst: Boolean
    )

    /** Serialize Fused mode/location changes off the main thread and keep only the newest fix queued. */
    private fun drainFusedQueue(client: FusedLocationProviderClient) {
        while (true) {
            val work = synchronized(fusedLock) {
                if (fusedDesiredEnabled) {
                    val pending = fusedPendingLocation
                    if (pending == null) {
                        fusedWorkerRunning = false
                        return
                    }
                    fusedPendingLocation = null
                    FusedWork(true, fusedGeneration, Location(pending), !fusedModeActive)
                } else {
                    if (!fusedModeActive) {
                        fusedWorkerRunning = false
                        return
                    }
                    FusedWork(false, fusedGeneration, null, false)
                }
            }

            try {
                if (!work.enable) {
                    Tasks.await(client.setMockMode(false), FUSED_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    synchronized(fusedLock) { fusedModeActive = false }
                    lastFusedError = null
                    persistFusedStatus(false, "Google Fused Location Provider: mock-режим выключен.")
                    continue
                }
                if (work.enableModeFirst) {
                    Tasks.await(client.setMockMode(true), FUSED_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    synchronized(fusedLock) { fusedModeActive = true }
                }
                val stillCurrent = synchronized(fusedLock) {
                    fusedDesiredEnabled && fusedGeneration == work.generation
                }
                if (!stillCurrent) continue
                val location = work.location ?: continue
                Tasks.await(client.setMockLocation(location), FUSED_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(LocationMonitorService.KEY_MOCK_LAST_PUBLISHED_ELAPSED_MS, SystemClock.elapsedRealtime())
                    .apply()
                synchronized(fusedLock) { fusedRetryAfterElapsedMs = 0L }
                lastFusedError = null
                persistFusedStatus(true, "Google Fused Location Provider подтвердил публикацию рассчитанной позиции.")
            } catch (error: Exception) {
                val superseded = synchronized(fusedLock) {
                    fusedGeneration != work.generation || (!fusedDesiredEnabled && work.enable)
                }
                if (superseded) continue

                var modeDisabled = !synchronized(fusedLock) { fusedModeActive }
                if (!modeDisabled) {
                    modeDisabled = try {
                        Tasks.await(client.setMockMode(false), FUSED_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                val failureMessage = "Google Fused Location Provider не принял координаты: " +
                    (error.message?.take(150)?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName) +
                    ". Проверь Google Play services и выбор Clear GPS в настройках mock location."
                synchronized(fusedLock) {
                    if (fusedGeneration == work.generation && fusedDesiredEnabled) {
                        fusedDesiredEnabled = false
                        fusedPendingLocation = null
                        fusedGeneration++
                        fusedRetryAfterElapsedMs = SystemClock.elapsedRealtime() + FUSED_RETRY_BACKOFF_MS
                    }
                    if (modeDisabled) fusedModeActive = false
                }
                lastFusedError = failureMessage
                persistFusedStatus(false, failureMessage)
                val hasSystemProvider = synchronized(providerLock) { registeredProviders.isNotEmpty() }
                if (!hasSystemProvider) {
                    appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
                        .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
                        .remove(LocationMonitorService.KEY_LAT)
                        .remove(LocationMonitorService.KEY_LON)
                        .remove(LocationMonitorService.KEY_ACCURACY)
                        .apply()
                }
            }
        }
    }

    private fun persistFusedStatus(active: Boolean, text: String) {
        val p = appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE)
        val changed = p.getBoolean(LocationMonitorService.KEY_FUSED_MOCK_ACTIVE, false) != active ||
            p.getString(LocationMonitorService.KEY_FUSED_MOCK_STATUS, "") != text
        p.edit()
            .putBoolean(LocationMonitorService.KEY_FUSED_MOCK_ACTIVE, active)
            .putString(LocationMonitorService.KEY_FUSED_MOCK_STATUS, text)
            .apply()
        if (changed) DiagnosticLogStore.log(
            appContext, "MOCK_FUSED", text, if (active) "INFO" else "WARN"
        )
    }

    private fun cleanupProviderLocked(provider: String) {
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

    private fun fail(prefix: String, error: Exception): Boolean {
        val detail = error.message?.take(180)?.takeIf { it.isNotBlank() }
        lastError = prefix + (detail?.let { " Подробности: " + it } ?: "")
        DiagnosticLogStore.log(appContext, "MOCK", lastError.orEmpty(), "ERROR")
        disable()
        return false
    }

    fun disable() {
        synchronized(providerLock) {
            val staleNames = registeredProviders + listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER) +
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf("fused") else emptyList<String>())
            staleNames.toSet().forEach { cleanupProviderLocked(it) }
            registeredProviders.clear()
        }
        synchronized(fusedLock) {
            fusedDesiredEnabled = false
            fusedPendingLocation = null
            fusedGeneration++
            if (!fusedWorkerRunning && fusedClient != null && fusedModeActive) {
                fusedWorkerRunning = true
                try {
                    fusedExecutor.execute { drainFusedQueue(fusedClient) }
                } catch (error: RuntimeException) {
                    fusedWorkerRunning = false
                    persistFusedStatus(false, "Не удалось завершить Google Fused mock mode: " +
                        error.javaClass.simpleName)
                }
            }
        }
        if (fusedClient == null) {
            persistFusedStatus(false, "Google Fused Location Provider недоступен на этом устройстве.")
        } else if (lastFusedError == null) {
            persistFusedStatus(false, "Отключение Google Fused mock mode запрошено.")
        }
        DiagnosticLogStore.log(appContext, "MOCK", "Системные mock-провайдеры удалены")
    }

    fun close() {
        disable()
        fusedExecutor.shutdown()
    }

    private companion object {
        const val DEFAULT_ACCURACY_METERS = 100f
        const val MAX_ACCURACY_METERS = 100_000f
        const val FUSED_TASK_TIMEOUT_MS = 8_000L
        const val FUSED_RETRY_BACKOFF_MS = 30_000L
    }
}
