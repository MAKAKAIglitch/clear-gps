package org.cleargps

import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteCoordinateEvidence
import org.cleargps.cell.CellSiteEvidenceBundle
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.CellEvidenceSource
import org.cleargps.cell.CellSiteCoordinateEstimator

data class OpenStreetMapCellSiteEvidenceConfig(
    val minimumMatchDistanceMeters: Double = 50.0,
    val maximumMatchDistanceMeters: Double = 500.0,
    val maximumSiteUncertaintyMeters: Double = 500.0,
    val minimumSiteConfidenceScore: Double = 0.30,
    val uncertaintyMultiplier: Double = 1.5,
    val minimumAmbiguityGapMeters: Double = 75.0,
    val minimumEvidenceUncertaintyMeters: Double = 35.0
) {
    init {
        require(minimumMatchDistanceMeters.isFinite() && minimumMatchDistanceMeters > 0.0)
        require(maximumMatchDistanceMeters.isFinite() &&
            maximumMatchDistanceMeters >= minimumMatchDistanceMeters)
        require(maximumSiteUncertaintyMeters.isFinite() && maximumSiteUncertaintyMeters > 0.0)
        require(minimumSiteConfidenceScore.isFinite() && minimumSiteConfidenceScore in 0.0..1.0)
        require(uncertaintyMultiplier.isFinite() && uncertaintyMultiplier > 0.0)
        require(minimumAmbiguityGapMeters.isFinite() && minimumAmbiguityGapMeters >= 0.0)
        require(minimumEvidenceUncertaintyMeters.isFinite() && minimumEvidenceUncertaintyMeters > 0.0)
    }
}

/**
 * Matches cached OSM communication structures to already-localized cell-site candidates.
 *
 * OSM evidence is not used to bootstrap a coarse/ambiguous OpenCellID-only position. The
 * existing site candidate must already have bounded uncertainty and adequate confidence.
 * A structure is assigned only to one clearly nearest site; all OSM catalogue records share
 * one dependency group so they cannot inflate independent-evidence counts.
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
                group.members.isNotEmpty() &&
                    group.members.all { it.mncLengthKnown } &&
                    group.latitude.isFinite() && group.latitude in -90.0..90.0 &&
                    group.longitude.isFinite() && group.longitude in -180.0..180.0 &&
                    group.uncertaintyRadiusMeters.isFinite() &&
                    group.uncertaintyRadiusMeters in 1.0..config.maximumSiteUncertaintyMeters &&
                    group.confidenceScore.isFinite() &&
                    group.confidenceScore >= config.minimumSiteConfidenceScore &&
                    group.confidenceScore <= 1.0
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
                // Locked sites remain spatial obstacles during matching. Otherwise an OSM
                // object at a user-fixed tower could be misassigned to a nearby unlocked site.
                // If two distinct sites are almost equally close, the object is ambiguous.
                if (nearby.size > 1 &&
                    nearby[1].distanceMeters - nearby[0].distanceMeters < config.minimumAmbiguityGapMeters
                ) return@mapNotNull null
                nearby.first().takeUnless { it.group.lockedByUser }
            }
            .toList()

        // Keep one clearly nearest representation per site. Multiple nearby OSM objects are
        // not independent surveys of the same mast and must not improve its confidence.
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
            val conservativeUncertainty = maxOf(
                match.osmObject.coordinateUncertaintyMeters,
                match.group.uncertaintyRadiusMeters * config.uncertaintyMultiplier,
                config.minimumEvidenceUncertaintyMeters
            )
            val evidence = CellSiteCoordinateEvidence(
                evidenceId = "osm:${match.osmObject.stableKey}",
                source = CellEvidenceSource.OSM_COMMUNICATIONS_STRUCTURE,
                latitude = match.osmObject.latitude,
                longitude = match.osmObject.longitude,
                uncertaintyMeters = conservativeUncertainty,
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
