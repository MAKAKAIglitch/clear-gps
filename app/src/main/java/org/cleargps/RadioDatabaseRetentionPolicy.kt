package org.cleargps

/**
 * Bounds detailed radio observations while keeping stable point summaries and their coordinates.
 * These limits affect only the local observation history table, not radio_points or scan_history.
 */
internal object RadioDatabaseRetentionPolicy {
    const val DEFAULT_LIMIT = 20_000
    const val MAX_LIMIT = 450_000
    val LIMIT_OPTIONS = listOf(5_000, 10_000, 20_000, 50_000, 100_000, 250_000, MAX_LIMIT)

    fun normalizeLimit(requested: Int): Int =
        LIMIT_OPTIONS.firstOrNull { it == requested } ?: DEFAULT_LIMIT

    fun recordsToDelete(currentCount: Int, requestedLimit: Int): Int =
        (currentCount.coerceAtLeast(0) - normalizeLimit(requestedLimit)).coerceAtLeast(0)

    fun label(requestedLimit: Int): String = when (normalizeLimit(requestedLimit)) {
        5_000 -> "5 тыс."
        10_000 -> "10 тыс."
        20_000 -> "20 тыс."
        50_000 -> "50 тыс."
        100_000 -> "100 тыс."
        250_000 -> "250 тыс."
        else -> "450 тыс."
    }
}
