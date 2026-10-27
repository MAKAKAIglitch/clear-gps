package org.cleargps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Best-effort recovery for a previously enabled monitor with a stale heartbeat. */
class ServiceRecoveryWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) return Result.success()
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastHeartbeat = prefs.getLong(ServiceRecoveryWatchdog.KEY_SERVICE_HEARTBEAT_ELAPSED_MS, 0L)
        val taskRemovedAt = inputData
            .getLong(ServiceRecoveryWatchdog.INPUT_TASK_REMOVED_AT_ELAPSED_MS, 0L)
            .takeIf { it > 0L }
        if (!ServiceRecoveryPolicy.heartbeatIsStale(nowElapsed, lastHeartbeat, taskRemovedAt)) {
            return Result.success()
        }

        val hasLocationPermission =
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocationPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission || !hasBackgroundLocationPermission) {
            clearStaleOutput(prefs, disableMock = true)
            prefs.edit()
                .putBoolean(LocationMonitorService.KEY_MONITORING, false)
                .putString(
                    LocationMonitorService.KEY_SERVICE_STATUS,
                    applicationContext.getString(
                        if (hasLocationPermission) R.string.service_recovery_permission_required
                        else R.string.service_recovery_location_permission_required
                    )
                ).apply()
            ServiceRecoveryWatchdog.cancel(applicationContext)
            DiagnosticLogStore.log(applicationContext, "SERVICE",
                "Watchdog recovery skipped because location permissions are unavailable.", "WARN")
            return Result.success()
        }

        if (!ServiceRecoveryPolicy.shouldAttemptRecovery(
                monitoringEnabled = true,
                hasLocationPermission = hasLocationPermission,
                hasBackgroundLocationPermission = hasBackgroundLocationPermission,
                nowElapsedMs = nowElapsed,
                lastHeartbeatElapsedMs = lastHeartbeat,
                taskRemovedAtElapsedMs = taskRemovedAt
            )
        ) return Result.success()

        clearStaleOutput(prefs, disableMock = false)
        prefs.edit()
            .putString(LocationMonitorService.KEY_SERVICE_STATUS,
                applicationContext.getString(R.string.service_recovery_starting))
            .apply()
        return try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, LocationMonitorService::class.java)
                    .setAction(LocationMonitorService.ACTION_START)
            )
            DiagnosticLogStore.log(applicationContext, "SERVICE",
                "Watchdog requested foreground-monitor recovery after a stale heartbeat.")
            Result.success()
        } catch (error: RuntimeException) {
            prefs.edit()
                .putString(LocationMonitorService.KEY_SERVICE_STATUS,
                    applicationContext.getString(R.string.service_recovery_failed))
                .apply()
            DiagnosticLogStore.log(applicationContext, "SERVICE",
                "Watchdog could not start the monitor: ${error.javaClass.simpleName}", "WARN")
            if (runAttemptCount < MAX_START_ATTEMPTS - 1) Result.retry() else Result.success()
        }
    }

    private fun clearStaleOutput(prefs: android.content.SharedPreferences, disableMock: Boolean) {
        val edit = prefs.edit()
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            .putString(LocationMonitorService.KEY_SOURCE, "NONE")
            .remove(LocationMonitorService.KEY_LAT)
            .remove(LocationMonitorService.KEY_LON)
            .remove(LocationMonitorService.KEY_ACCURACY)
        if (disableMock) {
            edit.putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
                .putBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
        }
        edit.apply()
    }

    private companion object {
        const val MAX_START_ATTEMPTS = 3
    }
}
