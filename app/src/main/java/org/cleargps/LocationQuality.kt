package org.cleargps

import android.location.Location
import android.location.LocationManager

/**
 * Prefer a recent, accurate fix; give GPS priority over Android's network fallback
 * without allowing an obviously older GPS sample to freeze the displayed position.
 */
internal fun isBetterLocation(candidate: Location, previous: Location): Boolean {
    val timeDelta = candidate.time - previous.time
    val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
    val previousAccuracy = if (previous.hasAccuracy()) previous.accuracy else Float.MAX_VALUE
    val candidateIsGps = candidate.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)
    val previousIsGps = previous.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)

    if (candidateIsGps && !previousIsGps) {
        return timeDelta >= -120_000L
    }
    if (previousIsGps &&
        candidate.provider.equals(LocationManager.NETWORK_PROVIDER, ignoreCase = true) &&
        timeDelta < 120_000L) {
        return false
    }
    if (timeDelta > 30_000L) return true
    if (timeDelta < -120_000L) return false
    return candidateAccuracy < previousAccuracy ||
        (timeDelta >= 0L && candidateAccuracy <= previousAccuracy * 1.5f)
}
