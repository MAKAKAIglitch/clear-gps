package org.cleargps

/**
 * Battery-aware policy for radio data collected only as part of an explicitly enabled replay.
 * Auto is the only combined mode that currently consumes GNSS and can additionally sample radio.
 */
internal object GnssReplayRadioCapturePolicy {
    const val MIN_INTERVAL_MILLIS = 15_000L
    const val REPEAT_SUPPRESSION_MILLIS = 30_000L
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val MAX_CONFIGURED_INTERVAL_SECONDS = 30

    fun shouldCapture(
        monitoring: Boolean,
        recording: Boolean,
        positionMode: String?
    ): Boolean = monitoring && recording && positionMode == "auto"

    fun intervalMillis(configuredSeconds: Int): Long =
        maxOf(configuredSeconds.coerceIn(1, MAX_CONFIGURED_INTERVAL_SECONDS) * 1_000L, MIN_INTERVAL_MILLIS)

    /** Applied inside the recorder so manual scans cannot bypass the rate limit. */
    fun shouldRecordFrame(
        lastFrameAtNanos: Long,
        nowNanos: Long,
        sameSignature: Boolean
    ): Boolean {
        if (nowNanos <= 0L) return false
        if (lastFrameAtNanos <= 0L) return true
        if (nowNanos < lastFrameAtNanos) return false
        val elapsedNanos = nowNanos - lastFrameAtNanos
        if (elapsedNanos < MIN_INTERVAL_MILLIS * NANOS_PER_MILLI) return false
        if (sameSignature && elapsedNanos < REPEAT_SUPPRESSION_MILLIS * NANOS_PER_MILLI) return false
        return true
    }
}
