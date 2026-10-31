package org.cleargps

/** Capped exponential reconnect schedule for a user-selected OBD-II adapter. */
internal object Obd2ReconnectPolicy {
    const val MAX_BACKOFF_STAGE = 6
    private val RETRY_DELAYS_MS = longArrayOf(
        1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L
    )

    /** Retry indefinitely while enabled; only the delay is capped at one minute. */
    fun delayBeforeAttempt(attemptIndex: Int): Long? =
        if (attemptIndex < 0) null else RETRY_DELAYS_MS[attemptIndex.coerceAtMost(MAX_BACKOFF_STAGE)]

    fun nextBackoffStage(attemptIndex: Int): Int =
        if (attemptIndex < 0) 0 else (attemptIndex + 1).coerceAtMost(MAX_BACKOFF_STAGE)

    /** Permission, configuration and invalid-address failures should not be retried blindly. */
    fun shouldRetry(error: Throwable): Boolean =
        error is Exception &&
            error !is SecurityException &&
            error !is IllegalStateException &&
            error !is IllegalArgumentException
}
