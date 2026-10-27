package org.cleargps

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules bounded service-health checks without holding a permanent wake lock. */
internal object ServiceRecoveryWatchdog {
    const val KEY_SERVICE_HEARTBEAT_ELAPSED_MS = "service_heartbeat_elapsed_ms"
    const val INPUT_TASK_REMOVED_AT_ELAPSED_MS = "task_removed_at_elapsed_ms"
    private const val PERIODIC_WORK_NAME = "clear-gps-service-watchdog"
    private const val RECOVERY_WORK_NAME = "clear-gps-service-recovery"

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) return
        try {
            val request = PeriodicWorkRequestBuilder<ServiceRecoveryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        } catch (error: Exception) {
            DiagnosticLogStore.log(appContext, "SERVICE",
                "Could not schedule the service watchdog: ${error.javaClass.simpleName}", "WARN")
        }
    }

    fun enqueueRecoveryCheck(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) return
        try {
            val request = OneTimeWorkRequestBuilder<ServiceRecoveryWorker>()
                .setInputData(workDataOf(INPUT_TASK_REMOVED_AT_ELAPSED_MS to SystemClock.elapsedRealtime()))
                .setInitialDelay(ServiceRecoveryPolicy.TASK_REMOVED_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                RECOVERY_WORK_NAME, ExistingWorkPolicy.REPLACE, request
            )
        } catch (error: Exception) {
            DiagnosticLogStore.log(appContext, "SERVICE",
                "Could not schedule a service recovery check: ${error.javaClass.simpleName}", "WARN")
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        try {
            val workManager = WorkManager.getInstance(appContext)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(RECOVERY_WORK_NAME)
        } catch (error: Exception) {
            DiagnosticLogStore.log(appContext, "SERVICE",
                "Could not cancel the service watchdog: ${error.javaClass.simpleName}", "WARN")
        }
    }
}
