package org.cleargps

/** Keeps cellular modem refresh requests bounded while allowing an explicit BeaconDB lookup to refresh. */
internal object CellInfoRefreshPolicy {
    const val MIN_REQUEST_INTERVAL_MS = 30_000L
    const val CALLBACK_TIMEOUT_MS = 3_000L

    fun shouldRequestUpdate(
        nowElapsedMillis: Long,
        lastRequestElapsedMillis: Long,
        force: Boolean = false
    ): Boolean =
        force ||
            lastRequestElapsedMillis <= 0L ||
            nowElapsedMillis < lastRequestElapsedMillis ||
            nowElapsedMillis - lastRequestElapsedMillis >= MIN_REQUEST_INTERVAL_MS
}
