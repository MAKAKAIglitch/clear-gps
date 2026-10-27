package org.cleargps

/**
 * Pure filtering rules for the source → local day → radio-type browser.
 *
 * Source-name search is independent. Data filters (date, radio type, minimum
 * strongest RSSI and minimum observation count) narrow segments, then days and
 * sources are retained only when they contain a matching segment.
 */
data class RadioSourceFilterPolicy(
    val sourceNameQuery: String = "",
    val dateQuery: String = "",
    val radioType: String = "all",
    val minimumRssi: Int = -110,
    val minimumObservations: Int = 1
) {
    private val normalizedSourceQuery: String
        get() = sourceNameQuery.trim()

    private val normalizedDateQuery: String
        get() = dateQuery.trim()

    val hasDataFilters: Boolean
        get() = normalizedDateQuery.isNotEmpty() ||
            radioType != "all" ||
            minimumRssi > DEFAULT_MINIMUM_RSSI ||
            minimumObservations > DEFAULT_MINIMUM_OBSERVATIONS

    fun matchesSegment(segment: RadioSourceSegmentRecord): Boolean =
        (radioType == "all" || segment.radioType == radioType) &&
            (normalizedDateQuery.isEmpty() || segment.dayKey.contains(normalizedDateQuery)) &&
            segment.maxRssi >= minimumRssi &&
            segment.observationCount >= minimumObservations

    fun matchesDay(dayKey: String, matchingSegments: List<RadioSourceSegmentRecord>): Boolean =
        (normalizedDateQuery.isEmpty() || dayKey.contains(normalizedDateQuery)) &&
            (!hasDataFilters || matchingSegments.any { it.dayKey == dayKey })

    fun matchesSource(source: RadioSourceRecord, hasMatchingDay: Boolean): Boolean =
        source.name.contains(normalizedSourceQuery, ignoreCase = true) &&
            (!hasDataFilters || hasMatchingDay)

    companion object {
        const val DEFAULT_MINIMUM_RSSI = -110
        const val DEFAULT_MINIMUM_OBSERVATIONS = 1
    }
}
