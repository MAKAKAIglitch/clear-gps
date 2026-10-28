package org.cleargps

import org.cleargps.core.ZoneGeometry
import org.cleargps.core.ZonePoint
import org.cleargps.fusion.PositionMeasurement

/**
 * Conservatively prevents Auto-mode radio fusion from admitting a radio uncertainty area that
 * intersects a configured spoofing/risk zone. If zones exist but the fix is too coarse for the
 * documented ZoneGeometry accuracy expansion, it is not used for fusion.
 */
internal object RadioFusionZonePolicy {
    const val MAX_ZONE_CHECK_ACCURACY_METERS = 500.0

    fun allowsFusion(
        measurement: PositionMeasurement,
        enabledZones: List<List<ZonePoint>>
    ): Boolean {
        if (!measurement.latitude.isFinite() || measurement.latitude !in -90.0..90.0 ||
            !measurement.longitude.isFinite() || measurement.longitude !in -180.0..180.0 ||
            !measurement.horizontalUncertaintyMeters.isFinite() ||
            measurement.horizontalUncertaintyMeters <= 0.0
        ) return false

        if (enabledZones.isEmpty()) return true
        if (measurement.horizontalUncertaintyMeters > MAX_ZONE_CHECK_ACCURACY_METERS) return false

        val point = ZonePoint(measurement.latitude, measurement.longitude)
        return enabledZones.none { polygon ->
            ZoneGeometry.contains(point, polygon, measurement.horizontalUncertaintyMeters)
        }
    }
}
