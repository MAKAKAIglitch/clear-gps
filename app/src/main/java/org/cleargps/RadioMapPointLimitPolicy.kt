package org.cleargps

/** Maximum number of geolocated radio-point summaries to load into the map. */
internal object RadioMapPointLimitPolicy {
    const val DEFAULT_LIMIT = 50_000
    const val MAX_LIMIT = 500_000
    val LIMIT_OPTIONS = listOf(5_000, 10_000, 20_000, 50_000, 100_000, 250_000, 500_000)


    private val MAP_RADIO_TYPES = listOf("wifi", "cell", "ble")

    /**
     * Shares the map row budget between radio types. A single busy type must not evict
     * every older Wi-Fi/cell record merely because its last_seen timestamp is newer.
     */
    fun allocatePerTypeLimits(counts: Map<String, Int>, requestedLimit: Int): Map<String, Int> {
        val limit = requestedLimit.coerceIn(1, MAX_LIMIT)
        val available = MAP_RADIO_TYPES.associateWith { (counts[it] ?: 0).coerceAtLeast(0) }
        val total = available.values.sum()
        if (total <= limit) return available.filterValues { it > 0 }

        val active = available.filterValues { it > 0 }
        val result = active.keys.associateWith { 0 }.toMutableMap()
        val minimumShare = minOf(100, limit / active.size)
        active.forEach { (type, count) -> result[type] = minOf(count, minimumShare) }

        var remaining = limit - result.values.sum()
        while (remaining > 0) {
            val capacities = active.mapValues { (type, count) ->
                (count - (result[type] ?: 0)).coerceAtLeast(0)
            }.filterValues { it > 0 }
            if (capacities.isEmpty()) break
            val capacityTotal = capacities.values.sum().toDouble()
            val allocations = capacities.mapValues { (_, capacity) ->
                (remaining * capacity / capacityTotal).toInt().coerceAtMost(capacity)
            }
            val allocated = allocations.values.sum()
            if (allocated > 0) {
                allocations.forEach { (type, amount) -> result[type] = (result[type] ?: 0) + amount }
                remaining -= allocated
            }
            if (remaining > 0) {
                val nextType = capacities.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .firstOrNull()?.key ?: break
                result[nextType] = (result[nextType] ?: 0) + 1
                remaining--
            }
        }
        return result.filterValues { it > 0 }
    }

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
