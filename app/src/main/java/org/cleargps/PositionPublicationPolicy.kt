package org.cleargps

import org.cleargps.core.FilterState

/**
 * Separates "the detector has not confirmed spoofing yet" from "this coordinate is safe to publish".
 * A fresh independent network/radio fallback may still be used; GPS-backed candidates fail closed.
 */
internal object PositionPublicationPolicy {
    const val GPS_PUBLICATION_HOLD_SCORE = 60

    fun shouldHideLiveGpsCoordinate(
        state: FilterState,
        riskScore: Int,
        inRiskZone: Boolean
    ): Boolean =
        inRiskZone || riskScore >= GPS_PUBLICATION_HOLD_SCORE || state != FilterState.NORMAL

    fun shouldHoldGpsBackedOutput(
        state: FilterState,
        riskScore: Int,
        inRiskZone: Boolean,
        source: String
    ): Boolean {
        val gpsBackedSource = source.equals("GPS", ignoreCase = true) ||
            source.equals("LAST_TRUSTED", ignoreCase = true)
        if (!gpsBackedSource) return false
        return inRiskZone || riskScore >= GPS_PUBLICATION_HOLD_SCORE || state != FilterState.NORMAL
    }

    fun gpsCoordinateHoldReason(
        state: FilterState,
        riskScore: Int,
        inRiskZone: Boolean
    ): String? = when {
        inRiskZone || state == FilterState.ZONE_RISK ->
            "GPS-координата удерживается: точка попала в отмеченную зону риска. Ждём независимый безопасный источник или чистые фиксы."
        riskScore >= GPS_PUBLICATION_HOLD_SCORE ->
            "GPS-координата удерживается фильтром: риск $riskScore/100. Прямая GPS-публикация разрешается только после снижения риска и нормального состояния."
        state == FilterState.SUSPECT ->
            "GPS-координата удерживается: фильтр обнаружил подозрительные признаки и ждёт повторных проверок."
        state == FilterState.CORRECTING ->
            "Прямая GPS-координата удерживается; используется независимый резервный источник, если он доступен."
        state == FilterState.NO_FALLBACK ->
            "GPS-координата удерживается: доверенный резервный источник не найден."
        else -> null
    }
}
