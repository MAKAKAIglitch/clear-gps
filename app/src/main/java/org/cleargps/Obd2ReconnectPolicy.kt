package org.cleargps

/** Bounded reconnect schedule for a user-selected, already-paired OBD-II adapter. */
internal object Obd2ReconnectPolicy {
    const val MAX_ATTEMPTS = 5
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

    fun delayBeforeAttempt(attemptIndex: Int): Long? = RETRY_DELAYS_MS.getOrNull(attemptIndex)

    /** Permission, configuration and invalid-address failures should not be retried blindly. */
    fun shouldRetry(error: Throwable): Boolean =
        error is Exception &&
            error !is SecurityException &&
            error !is IllegalStateException &&
            error !is IllegalArgumentException
}
