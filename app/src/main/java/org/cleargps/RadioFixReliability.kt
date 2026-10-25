package org.cleargps

/**
 * Policy helpers for radio fixes. Android limits Wi-Fi scan requests, so the app must not
 * hammer startScan() at the selected mock-update interval. A cached scan is usable only for
 * a bounded period and its uncertainty grows with age.
 */
internal object RadioFixReliability {
    const val WIFI_MIN_REQUEST_INTERVAL_MS = 30_000L
    const val WIFI_MAX_CACHE_AGE_MS = 45_000L
    const val WIFI_FRESH_AGE_MS = 15_000L
    private const val WIFI_STALE_DRIFT_METERS_PER_SECOND = 35.0
    private const val MAX_MOCK_ACCURACY_METERS = 5_000f

    fun shouldRequestWifiScan(nowElapsedMs: Long, lastRequestElapsedMs: Long): Boolean =
        lastRequestElapsedMs <= 0L ||
            (nowElapsedMs >= lastRequestElapsedMs &&
                nowElapsedMs - lastRequestElapsedMs >= WIFI_MIN_REQUEST_INTERVAL_MS)

    fun wifiAgePenaltyMeters(ageMillis: Long?, wifiSignalCount: Int, totalSignalCount: Int): Double {
        if (ageMillis == null || ageMillis <= WIFI_FRESH_AGE_MS || wifiSignalCount <= 0) return 0.0
        val total = totalSignalCount.coerceAtLeast(wifiSignalCount).coerceAtLeast(1)
        val wifiShare = (wifiSignalCount.toDouble() / total).coerceIn(0.0, 1.0)
        val staleSeconds = (ageMillis - WIFI_FRESH_AGE_MS).coerceAtLeast(0L) / 1_000.0
        return (staleSeconds * WIFI_STALE_DRIFT_METERS_PER_SECOND * wifiShare)
            .coerceAtMost(50_000.0)
    }

    fun mockHoldWindowMillis(updateIntervalMillis: Long): Long {
        val interval = updateIntervalMillis.coerceIn(1_000L, 30_000L)
        return maxOf(15_000L, interval * 2L).coerceAtMost(60_000L)
    }

    fun shouldHoldLastRadioMockFix(
        lastConfirmedAtMillis: Long,
        nowMillis: Long,
        updateIntervalMillis: Long
    ): Boolean {
        if (lastConfirmedAtMillis <= 0L || nowMillis < lastConfirmedAtMillis) return false
        return nowMillis - lastConfirmedAtMillis <= mockHoldWindowMillis(updateIntervalMillis)
    }

    fun mockAccuracyWithAge(baseAccuracyMeters: Float, ageMillis: Long): Float {
        val base = baseAccuracyMeters.takeIf { it.isFinite() && it > 0f } ?: 100f
        val ageSeconds = ageMillis.coerceAtLeast(0L) / 1_000f
        return (base + ageSeconds * (WIFI_STALE_DRIFT_METERS_PER_SECOND.toFloat()))
            .coerceIn(1f, MAX_MOCK_ACCURACY_METERS)
    }
}
