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
    const val BLE_MIN_REQUEST_INTERVAL_MS = 6_000L
    const val BLE_MAX_CACHE_AGE_MS = 15_000L
    const val BLE_FRESH_AGE_MS = 6_000L
    const val CELL_INFO_MAX_AGE_MS = 5L * 60L * 1_000L
    const val CELL_INFO_FRESH_AGE_MS = 30_000L
    private const val WIFI_STALE_DRIFT_METERS_PER_SECOND = 35.0
    private const val BLE_STALE_DRIFT_METERS_PER_SECOND = 25.0
    private const val CELL_STALE_DRIFT_METERS_PER_SECOND = 5.0
    private const val MAX_MOCK_ACCURACY_METERS = 5_000f

    fun isCellInfoFresh(timestampElapsedMillis: Long, nowElapsedMillis: Long): Boolean {
        if (timestampElapsedMillis <= 0L || nowElapsedMillis < timestampElapsedMillis) return false
        return nowElapsedMillis - timestampElapsedMillis <= CELL_INFO_MAX_AGE_MS
    }

    fun cellAgePenaltyMeters(ageMillis: Long?, cellSignalCount: Int, totalSignalCount: Int): Double =
        cachedRadioAgePenaltyMeters(
            ageMillis = ageMillis,
            cachedSignalCount = cellSignalCount,
            totalSignalCount = totalSignalCount,
            freshAgeMillis = CELL_INFO_FRESH_AGE_MS,
            driftMetersPerSecond = CELL_STALE_DRIFT_METERS_PER_SECOND
        ).coerceAtMost(1_000.0)

    fun shouldRequestWifiScan(nowElapsedMs: Long, lastRequestElapsedMs: Long): Boolean =
        lastRequestElapsedMs <= 0L ||
            nowElapsedMs < lastRequestElapsedMs ||
            nowElapsedMs - lastRequestElapsedMs >= WIFI_MIN_REQUEST_INTERVAL_MS

    fun shouldRequestBleScan(nowElapsedMs: Long, lastRequestElapsedMs: Long): Boolean =
        lastRequestElapsedMs <= 0L ||
            nowElapsedMs < lastRequestElapsedMs ||
            nowElapsedMs - lastRequestElapsedMs >= BLE_MIN_REQUEST_INTERVAL_MS

    fun wifiAgePenaltyMeters(ageMillis: Long?, wifiSignalCount: Int, totalSignalCount: Int): Double =
        cachedRadioAgePenaltyMeters(
            ageMillis = ageMillis,
            cachedSignalCount = wifiSignalCount,
            totalSignalCount = totalSignalCount,
            freshAgeMillis = WIFI_FRESH_AGE_MS,
            driftMetersPerSecond = WIFI_STALE_DRIFT_METERS_PER_SECOND
        )

    fun bleAgePenaltyMeters(ageMillis: Long?, bleSignalCount: Int, totalSignalCount: Int): Double =
        cachedRadioAgePenaltyMeters(
            ageMillis = ageMillis,
            cachedSignalCount = bleSignalCount,
            totalSignalCount = totalSignalCount,
            freshAgeMillis = BLE_FRESH_AGE_MS,
            driftMetersPerSecond = BLE_STALE_DRIFT_METERS_PER_SECOND
        )

    private fun cachedRadioAgePenaltyMeters(
        ageMillis: Long?,
        cachedSignalCount: Int,
        totalSignalCount: Int,
        freshAgeMillis: Long,
        driftMetersPerSecond: Double
    ): Double {
        if (ageMillis == null || ageMillis <= freshAgeMillis || cachedSignalCount <= 0) return 0.0
        val total = totalSignalCount.coerceAtLeast(cachedSignalCount).coerceAtLeast(1)
        val cachedShare = (cachedSignalCount.toDouble() / total).coerceIn(0.0, 1.0)
        val staleSeconds = (ageMillis - freshAgeMillis).coerceAtLeast(0L) / 1_000.0
        return (staleSeconds * driftMetersPerSecond * cachedShare).coerceAtMost(50_000.0)
    }

    fun canRetainLastEstimate(
        estimateUpdatedAtMillis: Long,
        nowMillis: Long,
        matchedCount: Int,
        accuracyMeters: Double
    ): Boolean {
        val ageMillis = nowMillis - estimateUpdatedAtMillis
        return ageMillis in 0..45_000L && matchedCount >= 2 &&
            accuracyMeters.isFinite() && accuracyMeters in 1.0..1_000.0
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
