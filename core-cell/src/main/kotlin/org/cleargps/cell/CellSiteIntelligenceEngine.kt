package org.cleargps.cell

/**
 * All coordinate evidence currently available for one exact radio-cell identity.
 * Multiple bundles with the same stable identity are merged before estimation.
 */
data class CellSiteEvidenceBundle(
    val identity: CellIdentity,
    val evidence: List<CellSiteCoordinateEvidence>
)

enum class CellSitePositionExclusionReason {
    UNKNOWN_MNC_WIDTH,
    NO_VALID_COORDINATE_EVIDENCE
}

data class CellSiteIntelligenceResult(
    /** Estimates are retained even when later quality gates exclude them from grouping. */
    val estimatesByIdentity: Map<String, CellSiteCoordinateEstimate>,
    val grouping: CellSiteGroupingResult,
    /** Exclusions that happened before the site-grouping stage. */
    val positionExclusions: Map<String, CellSitePositionExclusionReason>
) {
    val groups: List<CellSiteGroup> get() = grouping.groups
}

/**
 * Pure orchestration for the cell-site pipeline:
 * evidence fusion per identity -> coordinate estimate -> conservative physical-site grouping.
 *
 * This layer has no SQLite, Android, map, or network dependencies. Source adapters provide
 * evidence bundles; this engine only applies the configured estimation and grouping models.
 */
object CellSiteIntelligenceEngine {
    fun calculate(
        bundles: List<CellSiteEvidenceBundle>,
        estimatorConfig: CellSiteEstimatorConfig = CellSiteEstimatorConfig(),
        groupingConfig: CellSiteGroupingConfig = CellSiteGroupingConfig(),
        additionalCandidates: List<CellSiteCandidate> = emptyList()
    ): CellSiteIntelligenceResult {
        val estimates = linkedMapOf<String, CellSiteCoordinateEstimate>()
        val exclusions = linkedMapOf<String, CellSitePositionExclusionReason>()
        val candidates = ArrayList<CellSiteCandidate>()

        val bundlesByIdentity = bundles.groupBy { it.identity.stableKey }
        val additionalByIdentity = additionalCandidates.groupBy { it.identity.stableKey }
        val identityKeys = (bundlesByIdentity.keys + additionalByIdentity.keys).toSortedSet()
        identityKeys.forEach { identityKey ->
            val sameIdentityBundles = bundlesByIdentity[identityKey].orEmpty()
            val sameIdentityCandidates = additionalByIdentity[identityKey].orEmpty()
            val identity = sameIdentityBundles.firstOrNull()?.identity
                ?: sameIdentityCandidates.firstOrNull()?.identity
                ?: return@forEach
            if (!identity.mncLengthKnown) {
                exclusions[identityKey] = CellSitePositionExclusionReason.UNKNOWN_MNC_WIDTH
                return@forEach
            }

            val combinedEvidence = sameIdentityBundles.asSequence()
                .flatMap { it.evidence.asSequence() }
                .sortedWith(
                    compareBy<CellSiteCoordinateEvidence> { it.evidenceId }
                        .thenBy { it.source.name }
                        .thenBy { it.latitude }
                        .thenBy { it.longitude }
                )
                .distinctBy { it.evidenceId }
                .toList()
            val extraEstimates = sameIdentityCandidates.map { it.estimate }
            val validLocalCandidates = sameIdentityCandidates
                .filter { candidate ->
                    val estimate = candidate.estimate
                    estimate.latitude.isFinite() && estimate.latitude in -90.0..90.0 &&
                        estimate.longitude.isFinite() && estimate.longitude in -180.0..180.0 &&
                        estimate.uncertaintyRadiusMeters.isFinite() && estimate.uncertaintyRadiusMeters > 0.0 &&
                        estimate.confidenceScore.isFinite() && estimate.confidenceScore in 0.0..1.0
                }
                .sortedWith(
                    compareBy<CellSiteCandidate> { it.estimate.uncertaintyRadiusMeters }
                        .thenByDescending { it.estimate.confidenceScore }
                        .thenBy { it.estimate.algorithmVersion }
                )
            val osmEvidence = combinedEvidence.filter {
                it.source == CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE
            }
            val nonOsmEstimate = CellSiteCoordinateEstimator.estimate(
                combinedEvidence.filterNot {
                    it.source == CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE
                },
                estimatorConfig
            )

            // A matched OSM mast must refine the local estimate, not compete with it as a
            // separate "best coordinate". The previous selection-only path almost always kept
            // the more precise LTE-TA candidate unchanged, so OSM had no visible effect.
            val osmFusedEstimate = if (osmEvidence.isNotEmpty() && validLocalCandidates.isNotEmpty()) {
                val primary = validLocalCandidates.first().estimate
                val localEvidence = CellSiteCoordinateEvidence(
                    evidenceId = "local-ta:$identityKey:${primary.algorithmVersion}",
                    source = if (primary.lockedByUser) {
                        CellEvidenceSource.USER_VERIFIED
                    } else {
                        CellEvidenceSource.LOCAL_TIMING_ADVANCE
                    },
                    latitude = primary.latitude,
                    longitude = primary.longitude,
                    uncertaintyMeters = primary.uncertaintyRadiusMeters,
                    reliability = if (primary.lockedByUser) 1.0 else {
                        primary.confidenceScore.coerceIn(0.05, 1.0)
                    },
                    dependencyGroup = "local-ta:$identityKey:${primary.algorithmVersion}",
                    lockedByUser = primary.lockedByUser
                )
                CellSiteCoordinateEstimator.estimate(
                    osmEvidence + localEvidence,
                    estimatorConfig
                )?.let { fused ->
                    val conflictsWithOtherLocalCandidates = validLocalCandidates.drop(1).any { other ->
                        CellSiteCoordinateEstimator.distanceMeters(
                            primary.latitude, primary.longitude,
                            other.estimate.latitude, other.estimate.longitude
                        ) > primary.uncertaintyRadiusMeters + other.estimate.uncertaintyRadiusMeters
                    }
                    fused.copy(
                        confidenceScore = if (fused.conflictDetected || conflictsWithOtherLocalCandidates) {
                            minOf(primary.confidenceScore, fused.confidenceScore)
                        } else {
                            maxOf(primary.confidenceScore, fused.confidenceScore)
                        },
                        uncertaintyRadiusMeters = if (conflictsWithOtherLocalCandidates) {
                            maxOf(
                                fused.uncertaintyRadiusMeters,
                                validLocalCandidates.maxOf { it.estimate.uncertaintyRadiusMeters }
                            )
                        } else fused.uncertaintyRadiusMeters,
                        independentEvidenceCount = maxOf(
                            primary.independentEvidenceCount,
                            fused.independentEvidenceCount
                        ),
                        acceptedEvidenceIds = (primary.acceptedEvidenceIds + fused.acceptedEvidenceIds).distinct(),
                        conflictDetected = fused.conflictDetected || conflictsWithOtherLocalCandidates,
                        lockedByUser = primary.lockedByUser,
                        algorithmVersion = "cell-site-osm-fusion-1"
                    )
                }
            } else null

            val externalEstimate = if (osmFusedEstimate == null) {
                CellSiteCoordinateEstimator.estimate(combinedEvidence, estimatorConfig)
            } else null
            val allEstimates = if (osmFusedEstimate != null) {
                listOfNotNull(osmFusedEstimate, nonOsmEstimate)
            } else {
                listOfNotNull(externalEstimate) + extraEstimates
            }
            if (allEstimates.isEmpty()) {
                exclusions[identityKey] = CellSitePositionExclusionReason.NO_VALID_COORDINATE_EVIDENCE
                return@forEach
            }

            val lockedEstimates = allEstimates.filter { it.lockedByUser }
            val selected = (lockedEstimates.ifEmpty { allEstimates }).minWithOrNull(
                compareBy<CellSiteCoordinateEstimate> { it.uncertaintyRadiusMeters }
                    .thenByDescending { it.confidenceScore }
                    .thenByDescending { it.independentEvidenceCount }
                    .thenBy { it.latitude }
                    .thenBy { it.longitude }
            ) ?: return@forEach
            val conflicting = allEstimates.any { other ->
                other !== selected && CellSiteCoordinateEstimator.distanceMeters(
                    selected.latitude, selected.longitude, other.latitude, other.longitude
                ) > selected.uncertaintyRadiusMeters + other.uncertaintyRadiusMeters
            }
            val estimate = if (conflicting) selected.copy(
                conflictDetected = true,
                uncertaintyRadiusMeters = maxOf(
                    selected.uncertaintyRadiusMeters,
                    allEstimates.maxOf { it.uncertaintyRadiusMeters }
                ),
                confidenceScore = minOf(selected.confidenceScore, allEstimates.minOf { it.confidenceScore })
            ) else selected

            estimates[identityKey] = estimate
            candidates += CellSiteCandidate(identity, estimate)
        }

        return CellSiteIntelligenceResult(
            estimatesByIdentity = estimates.toMap(),
            grouping = CellSiteClusterer.group(candidates, groupingConfig),
            positionExclusions = exclusions.toMap()
        )
    }
}
