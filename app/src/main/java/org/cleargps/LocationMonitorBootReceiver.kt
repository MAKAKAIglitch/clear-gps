package org.cleargps

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restores a previously running monitor after reboot only when background location is granted.
 * Never turns monitoring on by itself and never republishes a cached position as a fresh fix.
 */
class LocationMonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(LocationMonitorService.PREFS, Context.MODE_PRIVATE)
        val wasMonitoring = prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)
        if (!wasMonitoring) return

        val hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!BootRecoveryPolicy.shouldRestoreAfterBoot(
                action = intent?.action,
                monitoringWasEnabled = wasMonitoring,
                hasBackgroundLocationPermission = hasBackgroundLocation
            )
        ) {
            clearStaleOutput(prefs)
            prefs.edit()
                .putBoolean(LocationMonitorService.KEY_MONITORING, false)
                .putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
                .putString(
                    LocationMonitorService.KEY_SERVICE_STATUS,
                    appContext.getString(R.string.boot_restore_background_location_required)
                )
                .apply()
            DiagnosticLogStore.log(
                appContext,
                "SERVICE",
                "Boot recovery skipped: background location permission is absent or the broadcast is unexpected.",
                "WARN"
            )
            return
        }

        clearStaleOutput(prefs)
        prefs.edit()
            .putString(
                LocationMonitorService.KEY_SERVICE_STATUS,
                appContext.getString(R.string.boot_restore_starting)
            )
            .apply()

        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, LocationMonitorService::class.java)
                    .setAction(LocationMonitorService.ACTION_START)
            )
            DiagnosticLogStore.log(appContext, "SERVICE", "Restoring previously enabled monitoring after boot.")
        } catch (error: RuntimeException) {
            prefs.edit()
                .putBoolean(LocationMonitorService.KEY_MONITORING, false)
                .putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
                .putString(
                    LocationMonitorService.KEY_SERVICE_STATUS,
                    appContext.getString(R.string.boot_restore_failed)
                )
                .apply()
            clearStaleOutput(prefs)
            DiagnosticLogStore.log(
                appContext,
                "SERVICE",
                "Boot recovery failed: " + error.javaClass.simpleName,
                "WARN"
            )
        }
    }

    private fun clearStaleOutput(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            .putString(LocationMonitorService.KEY_SOURCE, "NONE")
            .remove(LocationMonitorService.KEY_LAT)
            .remove(LocationMonitorService.KEY_LON)
            .remove(LocationMonitorService.KEY_ACCURACY)
            .apply()
    }
}
