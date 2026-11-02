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

internal enum class RadioPointSortField(
    val key: String,
    val title: String,
    internal val sqlExpression: String
) {
    LAST_SEEN("last_seen", "Последнее обнаружение", "last_seen"),
    FIRST_SEEN("first_seen", "Первое обнаружение", "first_seen"),
    SIGHTINGS("sightings", "Наблюдения", "sightings"),
    SIGNAL("signal", "Сигнал", "last_rssi"),
    ACCURACY("accuracy", "Точность", "accuracy"),
    NAME("name", "Имя / SSID", "COALESCE(NULLIF(label, ''), NULLIF(ssid, ''), identifier) COLLATE NOCASE")
}

internal data class RadioPointSort(
    val field: RadioPointSortField = RadioPointSortField.LAST_SEEN,
    val descending: Boolean = true
) {
    // SQL fragments come only from the closed enum; user input stays parameterized.
    val orderBySql: String
        get() {
            val direction = if (descending) "DESC" else "ASC"
            val nullsLast = if (field == RadioPointSortField.ACCURACY) {
                "CASE WHEN accuracy IS NULL THEN 1 ELSE 0 END ASC, "
            } else {
                ""
            }
            val tieBreakers = if (field == RadioPointSortField.LAST_SEEN) {
                ", id DESC"
            } else {
                ", last_seen DESC, id DESC"
            }
            return nullsLast + field.sqlExpression + " " + direction + tieBreakers
        }
}

internal class RadioRepository(
    private val database: LocalRadioDatabase
) {
    fun loadLocalPoints(
        query: String = "",
        limit: Int = DEFAULT_LIST_LIMIT,
        sort: RadioPointSort = RadioPointSort()
    ): List<RadioPointRecord> =
        if (query.isBlank()) {
            database.listPoints(normalizeListLimit(limit), sort)
        } else {
            database.searchPoints(query, normalizeListLimit(limit), sort)
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
