package org.cleargps

internal data class BeaconDbAutoRefreshState(
    val identityFingerprint: String?,
    val lastAttemptAtMillis: Long,
    val lastSuccessAtMillis: Long
)

internal object ExternalRadioDatabaseRefreshPolicy {
    const val CHECK_INTERVAL_MILLIS = 5_000L
    const val SNAPSHOT_INTERVAL_MILLIS = 30_000L
    const val BEACONDB_CHANGED_IDENTITY_COOLDOWN_MILLIS = 2L * 60L * 1_000L
    const val BEACONDB_SUCCESS_REFRESH_MILLIS = 15L * 60L * 1_000L
    const val BEACONDB_FAILURE_RETRY_MILLIS = 5L * 60L * 1_000L

    fun shouldCaptureSnapshot(nowElapsedMillis: Long, lastSnapshotElapsedMillis: Long): Boolean =
        lastSnapshotElapsedMillis <= 0L ||
            nowElapsedMillis < lastSnapshotElapsedMillis ||
            nowElapsedMillis - lastSnapshotElapsedMillis >= SNAPSHOT_INTERVAL_MILLIS

    fun shouldRequestBeaconDb(
        state: BeaconDbAutoRefreshState,
        currentIdentityFingerprint: String,
        nowMillis: Long
    ): Boolean {
        if (currentIdentityFingerprint.isBlank() || nowMillis <= 0L) return false
        val lastAttempt = state.lastAttemptAtMillis
        val lastSuccess = state.lastSuccessAtMillis
        if (state.identityFingerprint != currentIdentityFingerprint) {
            return lastAttempt <= 0L ||
                (nowMillis >= lastAttempt &&
                    nowMillis - lastAttempt >= BEACONDB_CHANGED_IDENTITY_COOLDOWN_MILLIS)
        }
        val lastCompletedAttempt = maxOf(lastAttempt, lastSuccess)
        if (lastCompletedAttempt <= 0L || nowMillis < lastCompletedAttempt) return false
        val retryInterval = if (lastSuccess >= lastAttempt && lastSuccess > 0L) {
            BEACONDB_SUCCESS_REFRESH_MILLIS
        } else {
            BEACONDB_FAILURE_RETRY_MILLIS
        }
        return nowMillis - lastCompletedAttempt >= retryInterval
    }
}
