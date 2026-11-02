package org.cleargps

import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteCoordinateEvidence
import org.cleargps.cell.CellSiteEvidenceBundle
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellEvidenceSource
import org.cleargps.cell.CellSiteCoordinateEstimator

data class OpenStreetMapCellSiteEvidenceConfig(
    val minimumMatchDistanceMeters: Double = 100.0,
    val maximumMatchDistanceMeters: Double = 500.0,
    val uncertaintyMultiplier: Double = 1.5,
    val minimumAmbiguityGapMeters: Double = 75.0
) {
    init {
        require(minimumMatchDistanceMeters.isFinite() && minimumMatchDistanceMeters > 0.0)
        require(maximumMatchDistanceMeters.isFinite() &&
            maximumMatchDistanceMeters >= minimumMatchDistanceMeters)
        require(uncertaintyMultiplier.isFinite() && uncertaintyMultiplier > 0.0)
        require(minimumAmbiguityGapMeters.isFinite() && minimumAmbiguityGapMeters >= 0.0)
    }
}

/**
 * Matches cached OSM communication structures to existing cell-site candidates.
 *
 * Matching is strictly spatial and conservative: an OSM object is assigned only to one clearly
 * nearest site. It is not used to create a new tower identity, and objects tagged explicitly as
 * not mobile-phone capable are ignored. All OSM objects share one dependency group because
 * they originate from the same community catalogue and must not inflate independent evidence.
 */
internal object OpenStreetMapCellSiteEvidenceAdapter {
    private data class Match(
        val group: CellSiteGroup,
        val osmObject: OpenStreetMapCellSiteObject,
        val distanceMeters: Double
    )

    fun toEvidenceBundles(
        groups: Collection<CellSiteGroup>,
        objects: Collection<OpenStreetMapCellSiteObject>,
        config: OpenStreetMapCellSiteEvidenceConfig = OpenStreetMapCellSiteEvidenceConfig()
    ): List<CellSiteEvidenceBundle> {
        val validGroups = groups.distinctBy { it.stableKey }
            .filter { group ->
                !group.lockedByUser &&
                    group.members.isNotEmpty() &&
                    group.members.all { it.mncLengthKnown } &&
                    group.latitude.isFinite() && group.latitude in -90.0..90.0 &&
                    group.longitude.isFinite() && group.longitude in -180.0..180.0 &&
                    group.uncertaintyRadiusMeters.isFinite() && group.uncertaintyRadiusMeters > 0.0
            }
        if (validGroups.isEmpty()) return emptyList()

        val matches = objects.asSequence()
            .distinctBy { it.stableKey }
            .filter { it.mobilePhoneCapable != false }
            .filter {
                it.osmId > 0L &&
                    it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                    it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                    it.coordinateUncertaintyMeters.isFinite() && it.coordinateUncertaintyMeters > 0.0
            }
            .mapNotNull { osmObject ->
                val nearby = validGroups.mapNotNull { group ->
                    val distance = CellSiteCoordinateEstimator.distanceMeters(
                        group.latitude, group.longitude,
                        osmObject.latitude, osmObject.longitude
                    )
                    val allowedDistance = (
                        group.uncertaintyRadiusMeters +
                            osmObject.coordinateUncertaintyMeters * config.uncertaintyMultiplier
                        ).coerceIn(
                            config.minimumMatchDistanceMeters,
                            config.maximumMatchDistanceMeters
                        )
                    if (distance > allowedDistance) null else Match(group, osmObject, distance)
                }.sortedWith(
                    compareBy<Match> { it.distanceMeters }.thenBy { it.group.stableKey }
                )
                if (nearby.isEmpty()) return@mapNotNull null
                // If two separate cell sites are almost equally close, the OSM object is ambiguous.
                if (nearby.size > 1 &&
                    nearby[1].distanceMeters - nearby[0].distanceMeters < config.minimumAmbiguityGapMeters
                ) return@mapNotNull null
                nearby.first()
            }
            .toList()

        // A cell site may have several OSM representations (for example a node and a way).
        // Keep only one clearly nearest object per site; all remain in the same OSM dependency group.
        val selected = matches.groupBy { it.group.stableKey }.values.mapNotNull { siteMatches ->
            val ordered = siteMatches.sortedWith(
                compareBy<Match> { it.distanceMeters }.thenBy { it.osmObject.stableKey }
            )
            if (ordered.size > 1 &&
                ordered[1].distanceMeters - ordered[0].distanceMeters < config.minimumAmbiguityGapMeters
            ) null else ordered.first()
        }

        val bundles = LinkedHashMap<String, MutableList<CellSiteCoordinateEvidence>>()
        selected.forEach { match ->
            val evidence = CellSiteCoordinateEvidence(
                evidenceId = "osm:${match.osmObject.stableKey}",
                source = CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE,
                latitude = match.osmObject.latitude,
                longitude = match.osmObject.longitude,
                uncertaintyMeters = match.osmObject.coordinateUncertaintyMeters,
                dependencyGroup = "osm:communications-catalogue"
            )
            match.group.members.forEach { identity ->
                bundles.getOrPut(identity.stableKey) { ArrayList() }.add(evidence)
            }
        }

        val identityByKey = validGroups.flatMap { it.members }
            .distinctBy { it.stableKey }
            .associateBy { it.stableKey }
        return bundles.entries.mapNotNull { (key, evidence) ->
            identityByKey[key]?.let { CellSiteEvidenceBundle(it, evidence) }
        }.sortedBy { it.identity.stableKey }
    }
}
