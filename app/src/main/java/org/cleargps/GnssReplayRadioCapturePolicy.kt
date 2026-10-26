package org.cleargps

/**
 * Battery-aware policy for radio data collected only as part of an explicitly enabled replay.
 * Auto is the only combined mode that currently consumes GNSS and can additionally sample radio.
 */
internal object GnssReplayRadioCapturePolicy {
    const val MIN_INTERVAL_MILLIS = 15_000L
    private const val MAX_CONFIGURED_INTERVAL_SECONDS = 30

    fun shouldCapture(
        monitoring: Boolean,
        recording: Boolean,
        positionMode: String?
    ): Boolean = monitoring && recording && positionMode == "auto"

    fun intervalMillis(configuredSeconds: Int): Long =
        maxOf(configuredSeconds.coerceIn(1, MAX_CONFIGURED_INTERVAL_SECONDS) * 1_000L, MIN_INTERVAL_MILLIS)
}
