package org.cleargps

/**
 * Pure timing/uncertainty policy for publishing one explicitly requested BeaconDB estimate.
 * Kept separate from the Android service so the two-minute bound and stale-fix penalty can
 * be tested without launching a foreground service or installing mock providers.
 */
internal object BeaconDbMockPublicationPolicy {
    const val OUTPUT_WINDOW_MS = 120_000L
    const val REPUBLISH_INTERVAL_MS = 5_000L
    const val ACCURACY_GROWTH_METERS_PER_SECOND = 15.0

    fun hasExpired(nowElapsedMillis: Long, deadlineElapsedMillis: Long): Boolean =
        nowElapsedMillis >= deadlineElapsedMillis

    fun nextDelayMillis(nowElapsedMillis: Long, deadlineElapsedMillis: Long): Long =
        (deadlineElapsedMillis - nowElapsedMillis)
            .coerceAtLeast(1L)
            .coerceAtMost(REPUBLISH_INTERVAL_MS)

    fun effectiveAccuracyMeters(baseAccuracyMeters: Double, ageMillis: Long): Double {
        require(baseAccuracyMeters.isFinite() && baseAccuracyMeters > 0.0) {
            "Base accuracy must be finite and positive."
        }
        val ageSeconds = ageMillis.coerceAtLeast(0L) / 1_000.0
        return (baseAccuracyMeters + ageSeconds * ACCURACY_GROWTH_METERS_PER_SECOND)
            .coerceIn(1.0, 100_000.0)
    }
}
