package org.cleargps

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Short, event-specific tactile feedback for Clear GPS.
 *
 * All patterns are deliberately brief. The engine respects the app toggle, Android's
 * touch-feedback setting, vibrator availability and the user's intensity preference.
 */
enum class PremiumHapticEvent {
    TAP,
    TOGGLE,
    SELECTION,
    FIX_ACQUIRED,
    SUCCESS,
    ERROR
}

class PremiumHaptics(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun intensity(): Float = preferences.getFloat(KEY_INTENSITY, DEFAULT_INTENSITY)
        .coerceIn(MIN_INTENSITY, MAX_INTENSITY)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setIntensity(value: Float) {
        preferences.edit().putFloat(KEY_INTENSITY, value.coerceIn(MIN_INTENSITY, MAX_INTENSITY)).apply()
    }

    fun play(event: PremiumHapticEvent) {
        if (!isEnabled()) return
        val motor = vibrator ?: return
        if (!motor.hasVibrator()) return
        val touchFeedbackEnabled = runCatching {
            Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1
            ) != 0
        }.getOrDefault(true)
        if (!touchFeedbackEnabled) return

        val pattern = patternFor(event, intensity())
        runCatching {
            motor.vibrate(
                VibrationEffect.createWaveform(
                    pattern.timings,
                    pattern.amplitudes,
                    -1
                )
            )
        }
    }

    internal data class Pattern(val timings: LongArray, val amplitudes: IntArray)

    internal companion object {
        const val PREFS_NAME = "clear_gps_haptics"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTENSITY = "intensity"
        const val DEFAULT_INTENSITY = 0.78f
        const val MIN_INTENSITY = 0.25f
        const val MAX_INTENSITY = 1f

        internal fun patternFor(event: PremiumHapticEvent, intensity: Float): Pattern {
            val scale = intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
            fun amp(value: Int): Int = (value * scale).roundToInt().coerceIn(1, 255)
            val timings: LongArray
            val amplitudes: IntArray
            when (event) {
                PremiumHapticEvent.TAP -> {
                    timings = longArrayOf(0, 9)
                    amplitudes = intArrayOf(0, amp(58))
                }
                PremiumHapticEvent.TOGGLE -> {
                    timings = longArrayOf(0, 17)
                    amplitudes = intArrayOf(0, amp(105))
                }
                PremiumHapticEvent.SELECTION -> {
                    timings = longArrayOf(0, 7)
                    amplitudes = intArrayOf(0, amp(42))
                }
                PremiumHapticEvent.FIX_ACQUIRED -> {
                    timings = longArrayOf(0, 18, 34, 30, 42, 54)
                    amplitudes = intArrayOf(0, amp(82), 0, amp(132), 0, amp(205))
                }
                PremiumHapticEvent.SUCCESS -> {
                    timings = longArrayOf(0, 20, 38, 48)
                    amplitudes = intArrayOf(0, amp(72), 0, amp(170))
                }
                PremiumHapticEvent.ERROR -> {
                    timings = longArrayOf(0, 48, 52, 48)
                    amplitudes = intArrayOf(0, amp(155), 0, amp(115))
                }
            }
            return Pattern(timings, amplitudes)
        }
    }
}
