package org.cleargps

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashLoopGuardTest {
    private lateinit var context: Context

    @Before
    fun resetState() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(CrashLoopGuard.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, true)
            .putBoolean(DiagnosticLogStore.KEY_ENABLED, false)
            .commit()
        DiagnosticLogStore.clear(context)
    }

    @Test
    fun thirdCrashWithinTenMinutesEnablesSafeModeAndDisablesBeaconDb() {
        val start = 1_800_000_000_000L
        crashAt(start, "private exception payload 55.123456, 37.654321")
        crashAt(start + 1_000L, "private exception payload 55.123456, 37.654321")
        assertFalse(CrashLoopGuard.isSafeModeActive(context, start + 1_500L))
        assertTrue(beaconDbEnabled())

        crashAt(start + 2_000L, "private exception payload 55.123456, 37.654321")
        assertTrue(CrashLoopGuard.isSafeModeActive(context, start + 2_001L))
        assertFalse(beaconDbEnabled())
        val log = DiagnosticLogStore.read(context)
        assertTrue(log.contains("[CRASH]"))
        assertTrue(log.contains("safe_mode=true"))
        assertFalse(log.contains("private exception payload"))
        assertFalse(log.contains("55.123456"))
        assertFalse(log.contains("37.654321"))
    }

    @Test
    fun crashesOutsideSlidingWindowDoNotEnterSafeMode() {
        val start = 1_800_000_000_000L
        crashAt(start, "first crash")
        crashAt(start + 1_000L, "second crash")
        val thirdTooLate = start + CrashLoopGuard.CRASH_WINDOW_MILLIS + 2_000L
        crashAt(thirdTooLate, "third crash too late")
        assertFalse(CrashLoopGuard.isSafeModeActive(context, thirdTooLate))
        assertTrue(beaconDbEnabled())
    }

    @Test
    fun safeModeExpiresTenMinutesAfterTriggeringCrash() {
        val start = 1_800_000_000_000L
        crashAt(start, "first crash")
        crashAt(start + 1_000L, "second crash")
        val thirdCrashAt = start + 2_000L
        crashAt(thirdCrashAt, "third crash")
        assertTrue(CrashLoopGuard.isSafeModeActive(
            context, thirdCrashAt + CrashLoopGuard.SAFE_MODE_DURATION_MILLIS - 1L
        ))
        assertFalse(CrashLoopGuard.isSafeModeActive(
            context, thirdCrashAt + CrashLoopGuard.SAFE_MODE_DURATION_MILLIS
        ))
    }

    private fun crashAt(timestamp: Long, message: String) {
        CrashLoopGuard.recordUncaughtCrash(context, IllegalStateException(message), timestamp)
    }

    private fun beaconDbEnabled(): Boolean =
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)
}
