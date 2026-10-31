package org.cleargps

/** Maximum number of geolocated radio-point summaries to load into the map. */
internal object RadioMapPointLimitPolicy {
    const val DEFAULT_LIMIT = 50_000
    const val MAX_LIMIT = 500_000
    val LIMIT_OPTIONS = listOf(5_000, 10_000, 20_000, 50_000, 100_000, 250_000, 500_000)

    fun normalizeLimit(requested: Int): Int =
        LIMIT_OPTIONS.firstOrNull { it == requested } ?: DEFAULT_LIMIT

    fun label(requestedLimit: Int): String = when (normalizeLimit(requestedLimit)) {
        5_000 -> "5 тыс."
        10_000 -> "10 тыс."
        20_000 -> "20 тыс."
        50_000 -> "50 тыс."
        100_000 -> "100 тыс."
        250_000 -> "250 тыс."
        else -> "500 тыс."
    }
}
