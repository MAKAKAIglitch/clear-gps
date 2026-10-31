package org.cleargps

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.StrictMode
import kotlin.system.exitProcess

/** Installs a minimal, privacy-preserving crash-loop guard before UI or services start. */
class ClearGpsApplication : Application() {
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
