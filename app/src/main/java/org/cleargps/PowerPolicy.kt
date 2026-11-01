package org.cleargps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlin.math.roundToInt

internal enum class PowerTask { ACTIVE_FIX, OPTIONAL_BACKGROUND }
internal enum class PowerBlockReason { SYSTEM_POWER_SAVE, LOW_BATTERY, SCREEN_OFF }

internal data class PowerPolicySettings(
    val appSaverEnabled: Boolean = true,
    val lowBatteryThresholdPercent: Int = PowerPolicy.DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT
)

internal data class PowerEnvironment(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val isInteractive: Boolean,
    val systemPowerSaveMode: Boolean
)

internal data class PowerPolicyDecision(
    val allowed: Boolean,
    val blockReason: PowerBlockReason? = null
)

/**
 * Shared gate for optional background work. A user-enabled GPS/radio fix is never blocked.
 */
internal object PowerPolicy {
    const val DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT = 20
    const val MIN_LOW_BATTERY_THRESHOLD_PERCENT = 5
    const val MAX_LOW_BATTERY_THRESHOLD_PERCENT = 50
    const val PAUSED_OPTIONAL_WORK_RECHECK_INTERVAL_MILLIS = 60_000L

    fun evaluate(
        task: PowerTask,
        settings: PowerPolicySettings,
        environment: PowerEnvironment
    ): PowerPolicyDecision {
        if (task == PowerTask.ACTIVE_FIX) return PowerPolicyDecision(true)
        if (environment.systemPowerSaveMode) {
            return PowerPolicyDecision(false, PowerBlockReason.SYSTEM_POWER_SAVE)
        }
        if (environment.isCharging) return PowerPolicyDecision(true)
        if (!settings.appSaverEnabled) return PowerPolicyDecision(true)

        val threshold = settings.lowBatteryThresholdPercent.coerceIn(
            MIN_LOW_BATTERY_THRESHOLD_PERCENT,
            MAX_LOW_BATTERY_THRESHOLD_PERCENT
        )
        val battery = environment.batteryPercent?.takeIf { it in 0..100 }
        if (battery != null && battery <= threshold) {
            return PowerPolicyDecision(false, PowerBlockReason.LOW_BATTERY)
        }
        if (!environment.isInteractive) {
            return PowerPolicyDecision(false, PowerBlockReason.SCREEN_OFF)
        }
        return PowerPolicyDecision(true)
    }

    fun optionalBackgroundDecision(context: Context): PowerPolicyDecision {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).roundToInt().coerceIn(0, 100)
        } else null
        val status = batteryIntent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

        return evaluate(
            task = PowerTask.OPTIONAL_BACKGROUND,
            settings = PowerPolicySettings(
                appSaverEnabled = prefs.getBoolean(ClearGpsPreferences.KEY_POWER_SAVING_ENABLED, true),
                lowBatteryThresholdPercent = prefs.getInt(
                    ClearGpsPreferences.KEY_LOW_BATTERY_THRESHOLD_PERCENT,
                    DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT
                )
            ),
            environment = PowerEnvironment(
                batteryPercent = batteryPercent,
                isCharging = charging,
                isInteractive = powerManager?.isInteractive ?: true,
                systemPowerSaveMode = powerManager?.isPowerSaveMode == true
            )
        )
    }

    fun canRunOptionalBackground(context: Context): Boolean =
        runCatching { optionalBackgroundDecision(context).allowed }.getOrDefault(false)
}
