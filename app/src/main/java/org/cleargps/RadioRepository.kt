package org.cleargps

import org.cleargps.cell.CellSiteEvidenceBundle
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteEstimatorConfig
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellSiteGroupingConfig
import org.cleargps.cell.CellSiteIntelligenceEngine
import org.cleargps.cell.CellSiteIntelligenceResult

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
    val sortField: RadioPointSortField = RadioPointSortField.LAST_SEEN,
    val descending: Boolean = true
) {
    // SQL fragments come only from the closed enum; user input stays parameterized.
    val orderBySql: String
        get() {
            val direction = if (descending) "DESC" else "ASC"
            val nullsLast = if (sortField == RadioPointSortField.ACCURACY) {
                "CASE WHEN accuracy IS NULL THEN 1 ELSE 0 END ASC, "
            } else {
                ""
            }
            val tieBreakers = if (sortField == RadioPointSortField.LAST_SEEN) {
                ", id DESC"
            } else {
                ", last_seen DESC, id DESC"
            }
            return nullsLast + sortField.sqlExpression + " " + direction + tieBreakers
        }
}

internal class RadioRepository(
    private val database: LocalRadioDatabase
) {
    fun loadLocalPoints(
        query: String = "",
        limit: Int = DEFAULT_LIST_LIMIT,
        sort: RadioPointSort = RadioPointSort(),
        minimumSightings: Int = 1
    ): List<RadioPointRecord> =
        if (query.isBlank()) {
            database.listPoints(normalizeListLimit(limit), sort, minimumSightings)
        } else {
            database.searchPoints(query, normalizeListLimit(limit), sort, minimumSightings)
        }

    fun loadMapPoints(limit: Int): List<RadioPointRecord> =
        database.listMapPoints(limit)

    fun loadOpenCellIdCellSiteEvidence(
        identities: Collection<CellIdentity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<CellSiteEvidenceBundle> =
        OpenCellIdCellSiteEvidenceAdapter.toEvidenceBundles(
            identities = identities,
            records = database.listOpenCellIdCells(limit = 1_000),
            nowMillis = nowMillis
        )

    fun calculateCellSites(
        evidence: List<CellSiteEvidenceBundle>,
        estimatorConfig: CellSiteEstimatorConfig = CellSiteEstimatorConfig(),
        groupingConfig: CellSiteGroupingConfig = CellSiteGroupingConfig()
    ): CellSiteIntelligenceResult =
        CellSiteIntelligenceEngine.calculate(evidence, estimatorConfig, groupingConfig)

    /** Calculates and persists a complete candidate snapshot from adapter-supplied evidence. */
    fun calculateAndSaveCellSites(
        evidence: List<CellSiteEvidenceBundle>,
        estimatorConfig: CellSiteEstimatorConfig = CellSiteEstimatorConfig(),
        groupingConfig: CellSiteGroupingConfig = CellSiteGroupingConfig(),
        calculatedAtMillis: Long = System.currentTimeMillis()
    ): CellSiteIntelligenceResult {
        val result = calculateCellSites(evidence, estimatorConfig, groupingConfig)
        database.saveCellSiteGroups(result.groups, calculatedAtMillis)
        return result
    }

    /**
     * Recalculates candidates from the existing OpenCellID cache only.
     * This method performs no network calls and does not request or start GPS.
     *
     * OpenCellID positions have a 5 km uncertainty floor. Such results are stored as low-
     * confidence candidates for later corroboration, not treated as surveyed mast locations.
     */
    fun calculateAndSaveCachedOpenCellIdCellSites(
        nowMillis: Long = System.currentTimeMillis(),
        estimatorConfig: CellSiteEstimatorConfig = CellSiteEstimatorConfig(),
        groupingConfig: CellSiteGroupingConfig = CellSiteGroupingConfig(
            minimumConfidenceScore = 0.18,
            minimumIndependentEvidenceCount = 1,
            maximumCandidateUncertaintyMeters = 10_000.0
        )
    ): CellSiteIntelligenceResult {
        require(nowMillis >= 0L)
        val identities = database.listCellIdentitiesWithObservationHistory()
        val evidence = loadOpenCellIdCellSiteEvidence(identities, nowMillis)
        return calculateAndSaveCellSites(
            evidence = evidence,
            estimatorConfig = estimatorConfig,
            groupingConfig = groupingConfig,
            calculatedAtMillis = nowMillis
        )
    }

    fun loadCellSites(limit: Int = 2_000): List<CellSiteRecord> =
        database.listCellSites(limit)

    fun saveCellSites(
        groups: List<CellSiteGroup>,
        calculatedAtMillis: Long = System.currentTimeMillis()
    ): CellSitePersistenceResult =
        database.saveCellSiteGroups(groups, calculatedAtMillis)

    fun loadCellSiteBatches(limit: Int = 50): List<CellSiteBatchRecord> =
        database.listCellSiteBatches(limit)

    fun lockCellSiteCoordinate(
        siteId: Long,
        latitude: Double,
        longitude: Double,
        uncertaintyMeters: Double
    ): Boolean = database.lockCellSiteCoordinate(siteId, latitude, longitude, uncertaintyMeters)

    fun unlockCellSiteCoordinate(siteId: Long): Boolean =
        database.unlockCellSiteCoordinate(siteId)

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
