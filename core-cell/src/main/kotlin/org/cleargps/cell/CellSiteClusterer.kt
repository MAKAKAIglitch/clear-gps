package org.cleargps.cell

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class CellSiteCandidate(
    val identity: CellIdentity,
    val estimate: CellSiteCoordinateEstimate
)

enum class CellSiteExclusionReason {
    UNKNOWN_MNC_WIDTH, INVALID_ESTIMATE, CONFLICTING_EVIDENCE,
    INSUFFICIENT_EVIDENCE, LOW_CONFIDENCE, HIGH_UNCERTAINTY
}

data class CellSiteGroupingConfig(
    val minimumMergeDistanceMeters: Double = 15.0,
    val maximumMergeDistanceMeters: Double = 50.0,
    val uncertaintyMultiplier: Double = 1.25,
    val minimumConfidenceScore: Double = 0.25,
    val minimumIndependentEvidenceCount: Int = 2,
    val maximumCandidateUncertaintyMeters: Double = 500.0
) {
    init {
        require(minimumMergeDistanceMeters.isFinite() && minimumMergeDistanceMeters > 0.0)
        require(maximumMergeDistanceMeters.isFinite() &&
            maximumMergeDistanceMeters >= minimumMergeDistanceMeters)
        require(uncertaintyMultiplier.isFinite() && uncertaintyMultiplier > 0.0)
        require(minimumConfidenceScore.isFinite() && minimumConfidenceScore in 0.0..1.0)
        require(minimumIndependentEvidenceCount >= 1)
        require(maximumCandidateUncertaintyMeters.isFinite() && maximumCandidateUncertaintyMeters > 0.0)
    }
}

/**
 * A conservative site proposal. The uncertainty radius encloses each member's uncertainty
 * radius around the returned centre. Confidence is the weakest member score: estimates may
 * share handset fixes, so grouping them must not artificially increase confidence.
 */
data class CellSiteGroup(
    val stableKey: String,
    val members: List<CellIdentity>,
    val latitude: Double,
    val longitude: Double,
    val uncertaintyRadiusMeters: Double,
    val confidenceScore: Double
) {
    val memberCount: Int get() = members.size
    val technologies: Set<CellTechnology> get() = members.mapTo(linkedSetOf()) { it.technology }
}

data class CellSiteGroupingResult(
    val groups: List<CellSiteGroup>,
    val excludedIdentityReasons: Map<String, CellSiteExclusionReason>,
    val duplicateIdentityKeys: List<String>
)

object CellSiteClusterer {
    private data class Cluster(val candidates: MutableList<CellSiteCandidate>)

    /**
     * Deterministic complete-link assignment: a candidate joins a group only when it is
     * geometrically compatible with every member. This prevents A~B and B~C from silently
     * creating an A~B~C chain when A and C are too far apart.
     *
     * Different RATs and operators may share a physical site. Their identities are preserved.
     */
    fun group(
        candidates: List<CellSiteCandidate>,
        config: CellSiteGroupingConfig = CellSiteGroupingConfig()
    ): CellSiteGroupingResult {
        val excluded = linkedMapOf<String, CellSiteExclusionReason>()
        val valid = ArrayList<CellSiteCandidate>()

        candidates.forEach { candidate ->
            val reason = exclusionReason(candidate, config)
            if (reason == null) valid += candidate
            else excluded[candidate.identity.stableKey] = reason
        }

        val duplicateKeys = ArrayList<String>()
        val unique = valid.groupBy { it.identity.stableKey }.toSortedMap().map { (key, sameIdentity) ->
            if (sameIdentity.size > 1) duplicateKeys += key
            sameIdentity.sortedWith(
                compareByDescending<CellSiteCandidate> { it.estimate.confidenceScore }
                    .thenByDescending { it.estimate.independentEvidenceCount }
                    .thenBy { it.estimate.uncertaintyRadiusMeters }
                    .thenBy { it.estimate.latitude }
                    .thenBy { it.estimate.longitude }
            ).first()
        }.sortedWith(
            compareByDescending<CellSiteCandidate> { it.estimate.confidenceScore }
                .thenByDescending { it.estimate.independentEvidenceCount }
                .thenBy { it.estimate.uncertaintyRadiusMeters }
                .thenBy { it.identity.stableKey }
        )

        val clusters = ArrayList<Cluster>()
        unique.forEach { candidate ->
            val compatible = clusters.mapNotNull { cluster ->
                val pairScores = cluster.candidates.mapNotNull { existing ->
                    val distance = CellSiteCoordinateEstimator.distanceMeters(
                        candidate.estimate.latitude,
                        candidate.estimate.longitude,
                        existing.estimate.latitude,
                        existing.estimate.longitude
                    )
                    val maximumDistance = allowedDistance(candidate.estimate, existing.estimate, config)
                    if (distance > maximumDistance) null else distance / maximumDistance
                }
                if (pairScores.size != cluster.candidates.size) null
                else Triple(cluster, pairScores.maxOrNull() ?: 0.0, clusterKey(cluster))
            }
            val destination = compatible.minWithOrNull(
                compareBy<Triple<Cluster, Double, String>> { it.second }.thenBy { it.third }
            )?.first

            if (destination == null) clusters += Cluster(mutableListOf(candidate))
            else destination.candidates += candidate
        }

        val groups = clusters.mapNotNull { buildGroup(it.candidates) }.sortedBy { it.stableKey }
        return CellSiteGroupingResult(groups, excluded.toSortedMap(), duplicateKeys.sorted())
    }

    private fun exclusionReason(
        candidate: CellSiteCandidate,
        config: CellSiteGroupingConfig
    ): CellSiteExclusionReason? {
        val identity = candidate.identity
        val estimate = candidate.estimate
        if (!identity.mncLengthKnown) return CellSiteExclusionReason.UNKNOWN_MNC_WIDTH
        if (!estimate.latitude.isFinite() || estimate.latitude !in -90.0..90.0 ||
            !estimate.longitude.isFinite() || estimate.longitude !in -180.0..180.0 ||
            !estimate.uncertaintyRadiusMeters.isFinite() || estimate.uncertaintyRadiusMeters <= 0.0 ||
            !estimate.confidenceScore.isFinite() || estimate.confidenceScore !in 0.0..1.0
        ) return CellSiteExclusionReason.INVALID_ESTIMATE
        if (estimate.conflictDetected) return CellSiteExclusionReason.CONFLICTING_EVIDENCE
        if (estimate.independentEvidenceCount < config.minimumIndependentEvidenceCount) {
            return CellSiteExclusionReason.INSUFFICIENT_EVIDENCE
        }
        if (estimate.confidenceScore < config.minimumConfidenceScore) {
            return CellSiteExclusionReason.LOW_CONFIDENCE
        }
        if (estimate.uncertaintyRadiusMeters > config.maximumCandidateUncertaintyMeters) {
            return CellSiteExclusionReason.HIGH_UNCERTAINTY
        }
        return null
    }

    private fun allowedDistance(
        first: CellSiteCoordinateEstimate,
        second: CellSiteCoordinateEstimate,
        config: CellSiteGroupingConfig
    ): Double {
        val combinedUncertainty = sqrt(
            first.uncertaintyRadiusMeters * first.uncertaintyRadiusMeters +
                second.uncertaintyRadiusMeters * second.uncertaintyRadiusMeters
        )
        return (combinedUncertainty * config.uncertaintyMultiplier)
            .coerceIn(config.minimumMergeDistanceMeters, config.maximumMergeDistanceMeters)
    }

    private fun clusterKey(cluster: Cluster): String =
        cluster.candidates.map { it.identity.stableKey }.sorted().joinToString("|")

    private fun buildGroup(candidates: List<CellSiteCandidate>): CellSiteGroup? {
        if (candidates.isEmpty()) return null
        val members = candidates.map { it.identity }.sortedBy { it.stableKey }

        // Weighted spherical mean is stable across the date line and high latitudes.
        var x = 0.0
        var y = 0.0
        var z = 0.0
        candidates.forEach { candidate ->
            val estimate = candidate.estimate
            val weight = estimate.confidenceScore.coerceAtLeast(0.01) /
                (estimate.uncertaintyRadiusMeters * estimate.uncertaintyRadiusMeters)
            val latitude = Math.toRadians(estimate.latitude)
            val longitude = Math.toRadians(estimate.longitude)
            x += weight * cos(latitude) * cos(longitude)
            y += weight * cos(latitude) * sin(longitude)
            z += weight * sin(latitude)
        }
        val horizontal = sqrt(x * x + y * y)
        if (!horizontal.isFinite() || !z.isFinite() ||
            (horizontal < 1e-15 && kotlin.math.abs(z) < 1e-15)
        ) return null

        val latitude = Math.toDegrees(kotlin.math.atan2(z, horizontal))
        val longitude = normalizeLongitude(Math.toDegrees(kotlin.math.atan2(y, x)))
        if (!latitude.isFinite() || !longitude.isFinite()) return null

        // Keep a conservative bound; cell estimates may share correlated handset-location error.
        val uncertainty = candidates.maxOf { candidate ->
            candidate.estimate.uncertaintyRadiusMeters +
                CellSiteCoordinateEstimator.distanceMeters(
                    latitude, longitude, candidate.estimate.latitude, candidate.estimate.longitude
                )
        }
        val confidence = candidates.minOf { it.estimate.confidenceScore }
        val stableKey = "site:" + members.joinToString("~") { it.stableKey }
        return CellSiteGroup(stableKey, members, latitude, longitude, uncertainty, confidence)
    }

    private fun normalizeLongitude(value: Double): Double {
        val normalized = ((value + 540.0) % 360.0) - 180.0
        return if (normalized == -180.0 && value > 0.0) 180.0 else normalized
    }
}
