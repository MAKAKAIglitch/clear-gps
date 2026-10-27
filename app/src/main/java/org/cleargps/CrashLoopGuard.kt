package org.cleargps

import android.content.Context

/**
 * Persistent crash-loop protection for experimental network-backed sources.
 * Only uncaught app-process crashes are counted; force-stops and OS kills are not.
 */
internal object CrashLoopGuard {
    internal const val PREFERENCES_NAME = "clear_gps_crash_loop_guard"
    internal const val CRASH_WINDOW_MILLIS = 10 * 60 * 1000L
    internal const val SAFE_MODE_DURATION_MILLIS = 10 * 60 * 1000L
    internal const val CRASH_THRESHOLD = 3

    private const val KEY_CRASH_TIMESTAMPS = "uncaught_crash_timestamps"
    private const val KEY_SAFE_MODE_UNTIL = "safe_mode_until_wall_time"
    private const val MAX_RECORDED_CRASHES = 12
    private const val MAX_STACK_FRAMES = 8
    private const val MAX_STACK_CHARACTERS = 1_200
    private val lock = Any()

    fun isSafeModeActive(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = try {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SAFE_MODE_UNTIL, 0L) > nowMillis
    } catch (_: Throwable) {
        // Fail closed: when guard state cannot be read, block the online experiment.
        true
    }

    fun recordUncaughtCrash(
        context: Context,
        error: Throwable,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        var recentCount = 0
        var safeModeActive = false
        try {
            synchronized(lock) {
                val guardPrefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                val recent = (
                    readRecentTimestamps(guardPrefs.getString(KEY_CRASH_TIMESTAMPS, null), nowMillis) +
                        nowMillis
                    ).takeLast(MAX_RECORDED_CRASHES)
                recentCount = recent.size
                val thresholdReached = recentCount >= CRASH_THRESHOLD
                val previousSafeModeUntil = guardPrefs.getLong(KEY_SAFE_MODE_UNTIL, 0L)
                val safeModeUntil = when {
                    thresholdReached -> maxOf(previousSafeModeUntil, nowMillis + SAFE_MODE_DURATION_MILLIS)
                    previousSafeModeUntil > nowMillis -> previousSafeModeUntil
                    else -> 0L
                }
                safeModeActive = safeModeUntil > nowMillis
                guardPrefs.edit()
                    .putString(KEY_CRASH_TIMESTAMPS, recent.joinToString(","))
                    .putLong(KEY_SAFE_MODE_UNTIL, safeModeUntil)
                    .commit()
                if (thresholdReached) {
                    // Require a fresh user decision before BeaconDB can be used again.
                    context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)
                        .commit()
                }
            }
        } catch (_: Throwable) {
            // Crash diagnostics must not prevent the platform's fatal-exception handler.
        }

        val stack = try {
            error.stackTrace.take(MAX_STACK_FRAMES).joinToString(" > ") { frame ->
                frame.className + "#" + frame.methodName + ":" + frame.lineNumber
            }.take(MAX_STACK_CHARACTERS)
        } catch (_: Throwable) {
            "unavailable"
        }
        val summary = "type=" + error.javaClass.name.take(160) +
            "; crashes_in_10m=" + recentCount +
            "; safe_mode=" + safeModeActive +
            "; stack=" + stack
        try {
            DiagnosticLogStore.logCrash(context, summary)
        } catch (_: Throwable) {
            // Never replace the original crash with a logging failure.
        }
    }

    private fun readRecentTimestamps(encoded: String?, nowMillis: Long): List<Long> =
        encoded.orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { timestamp ->
                timestamp <= nowMillis && timestamp >= nowMillis - CRASH_WINDOW_MILLIS
            }
            .takeLast(MAX_RECORDED_CRASHES)
}
