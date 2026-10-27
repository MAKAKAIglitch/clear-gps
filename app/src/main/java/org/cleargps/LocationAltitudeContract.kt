package org.cleargps

import android.location.Location
import android.os.Build

/**
 * Copy altitude measurements without conflating WGS84 ellipsoid height and mean-sea-level
 * height. Android exposes these as separate fields starting at API 34; older releases cannot
 * represent the native MSL fields, so no geoid offset is guessed here.
 */
internal fun copyAltitudeMetadata(source: Location, target: Location) {
    if (source.hasAltitude() && source.altitude.isFinite()) {
        target.altitude = source.altitude
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        source.hasVerticalAccuracy() &&
        source.verticalAccuracyMeters.isFinite() &&
        source.verticalAccuracyMeters >= 0f
    ) {
        target.verticalAccuracyMeters = source.verticalAccuracyMeters
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (source.hasMslAltitude() && source.mslAltitudeMeters.isFinite()) {
            target.mslAltitudeMeters = source.mslAltitudeMeters
        }
        if (source.hasMslAltitudeAccuracy() &&
            source.mslAltitudeAccuracyMeters.isFinite() &&
            source.mslAltitudeAccuracyMeters >= 0f
        ) {
            target.mslAltitudeAccuracyMeters = source.mslAltitudeAccuracyMeters
        }
    }
}
