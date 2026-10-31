package org.cleargps

/**
 * Shared read path for the local radio-point dataset.
 *
 * The list and map are different projections of the same radio_points table:
 * - listPoints includes located and unlocated records so imported or incomplete radios remain visible;
 * - mapPoints includes only records with valid coordinates and applies its independent render limit.
 *
 * BeaconDB estimates and OpenCellID cell catalogue rows are intentionally exposed by their own
 * UI/data flows; neither is materialized as a local radio_points row.
 */
internal data class RadioPointCounts(
    val total: Int,
    val geolocated: Int
)

internal class RadioRepository(
    private val database: LocalRadioDatabase
) {
    fun loadLocalPoints(
        query: String = "",
        limit: Int = DEFAULT_LIST_LIMIT
    ): List<RadioPointRecord> =
        if (query.isBlank()) {
            database.listPoints(normalizeListLimit(limit))
        } else {
            database.searchPoints(query, normalizeListLimit(limit))
        }

    fun loadMapPoints(limit: Int): List<RadioPointRecord> =
        database.listMapPoints(limit)

    fun pointCounts(): RadioPointCounts = RadioPointCounts(
        total = database.pointCount(),
        geolocated = database.mapPointCount()
    )

    private fun normalizeListLimit(limit: Int): Int =
        limit.coerceIn(1, MAX_LIST_LIMIT)

    companion object {
        const val DEFAULT_LIST_LIMIT = 5_000
        const val MAX_LIST_LIMIT = 5_000
    }
}
