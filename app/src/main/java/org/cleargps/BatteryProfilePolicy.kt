package org.cleargps

/** User-selected cadence profile. It does not throttle raw GNSS measurement callbacks. */
enum class BatteryProfile(
    val key: String,
    val title: String,
    val description: String
) {
    ECO("eco", "Eco", "Реже обновляет фиксы и запускает радиопоиск; Android всё равно управляет фактической частотой сканов."),
    NORMAL("normal", "Normal", "Использует заданный интервал."),
    MAX("max", "Max", "Чаще запрашивает фиксы и радиопоиск; расход энергии может быть выше.");

    companion object {
        fun fromKey(key: String?): BatteryProfile =
            values().firstOrNull { it.key == key } ?: NORMAL

        fun isKnownKey(key: String?): Boolean = values().any { it.key == key }
    }
}

/**
 * Pure interval policy used by both the activity's visible scan loop and the monitor service.
 *
 * First apply movement adaptation (halve the configured interval above 25 km/h when enabled),
 * then apply the profile. Eco doubles that interval, Normal leaves it unchanged, and Max halves
 * it. The effective interval is always in 1..60 seconds. Raw GNSS callbacks stay available to the
 * detector regardless of this request-cadence profile.
 */
internal object BatteryProfilePolicy {
    fun profiledIntervalSeconds(configuredSeconds: Int, profile: BatteryProfile): Int =
        intervalSeconds(configuredSeconds, speedKmh = null, adaptive = false, profile = profile)

    fun intervalSeconds(
        configuredSeconds: Int,
        speedKmh: Float?,
        adaptive: Boolean,
        profile: BatteryProfile
    ): Int {
        val configured = configuredSeconds.coerceIn(1, 30)
        val movingFast = speedKmh != null && speedKmh.isFinite() && speedKmh > 25f
        val movementAdjusted = if (adaptive && movingFast) {
            (configured / 2).coerceAtLeast(1)
        } else {
            configured
        }
        return when (profile) {
            BatteryProfile.ECO -> (movementAdjusted * 2).coerceIn(1, 60)
            BatteryProfile.NORMAL -> movementAdjusted
            BatteryProfile.MAX -> (movementAdjusted / 2).coerceAtLeast(1)
        }
    }
}
