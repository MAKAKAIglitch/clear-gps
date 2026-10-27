package org.cleargps

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess

/** Installs a minimal, privacy-preserving crash-loop guard before UI or services start. */
class ClearGpsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
