package org.cleargps

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.StrictMode
import androidx.work.Configuration
import kotlin.system.exitProcess

/** Installs a minimal, privacy-preserving crash-loop guard before UI or services start. */
class ClearGpsApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    private val openStreetMapConsentListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ClearGpsPreferences.KEY_OPENSTREETMAP_CELL_SITE_LOOKUP_ENABLED) {
                OpenStreetMapCellSiteWorkScheduler.sync(this)
            }
        }

    private val regionPositionListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == LocationMonitorService.KEY_HAS_OUTPUT ||
                key == LocationMonitorService.KEY_LAT ||
                key == LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT ||
                key == LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT ||
                key == LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_LAT
            ) {
                readNavigationPosition(prefs)?.coordinate?.let { coordinate ->
                    OverpassRoadRegionQueue.enqueueCurrent(this, coordinate.latitude, coordinate.longitude)
                }
            }
        }

    /**
     * StrictMode is deliberately log-only and enabled only for debuggable installs.
     * It helps find main-thread I/O and VM/resource mistakes without turning diagnostics
     * into a new crash source or changing release behavior.
     */
    private fun configureDebugDiagnostics() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }

    override fun onCreate() {
        super.onCreate()
        configureDebugDiagnostics()
        val preferences = getSharedPreferences(ClearGpsPreferences.PREFS, MODE_PRIVATE)
        preferences.registerOnSharedPreferenceChangeListener(openStreetMapConsentListener)
        preferences.registerOnSharedPreferenceChangeListener(regionPositionListener)
        OpenStreetMapCellSiteWorkScheduler.sync(this)
        OverpassRoadRegionQueue.schedule(this)
        readNavigationPosition(preferences)?.coordinate?.let { coordinate ->
            OverpassRoadRegionQueue.enqueueCurrent(this, coordinate.latitude, coordinate.longitude)
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                CrashLoopGuard.recordUncaughtCrash(this, error)
            } catch (_: Throwable) {
                // Preserve Android's normal fatal-exception path if diagnostics fail.
            }
            if (previousHandler != null) {
                try {
                    previousHandler.uncaughtException(thread, error)
                } catch (_: Throwable) {
                    terminateProcess()
                }
            }
            terminateProcess()
        }
    }

    private fun terminateProcess(): Nothing {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
