package org.cleargps

/**
 * Describes why GPS-only mode has not received its first physical GNSS location.
 * This is diagnostic state only: it does not synthesize or lower the requirements for a fix.
 */
internal object GpsFixAcquisitionPolicy {
    const val FIRST_FIX_TIMEOUT_MS = 30_000L
    const val CHECK_INTERVAL_MS = 5_000L
    const val LAST_KNOWN_MAX_AGE_NANOS = 15_000_000_000L

    fun requiresPreciseLocationPermission(positionModeKey: String): Boolean =
        positionModeKey == "gps" || positionModeKey == "gps_obd2"

    fun waitingStatus(
        hasFineLocationPermission: Boolean,
        providerAvailable: Boolean,
        providerEnabled: Boolean,
        elapsedSinceTrackingStartedMs: Long
    ): String = when {
        !hasFineLocationPermission ->
            "Для режима «Только GPS» требуется разрешение «Точное местоположение»."
        !providerAvailable ->
            "GPS-провайдер недоступен на этом устройстве."
        !providerEnabled ->
            "GPS выключен в системных настройках геолокации."
        elapsedSinceTrackingStartedMs >= FIRST_FIX_TIMEOUT_MS ->
            "GPS не выдал координаты за 30 секунд. Проверь включение геолокации и попробуй получить сигнал под открытым небом."
        else ->
            "GPS включён: ожидание первого спутникового фикса…"
    }
}
