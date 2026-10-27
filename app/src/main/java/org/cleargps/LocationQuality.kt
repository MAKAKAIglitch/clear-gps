package org.cleargps

import android.location.Location
import android.location.LocationManager

/**
 * Prefer a recent, accurate fix; give GPS priority over Android's network fallback
 * without allowing an obviously older GPS sample to freeze the displayed position.
 *
 * elapsedRealtimeNanos is the authoritative ordering clock for live fixes because wall time
 * can jump when the system clock is adjusted. Wall time is only a fallback for incomplete
 * synthetic/legacy Location objects; live monitor callbacks are separately required to
 * carry a valid elapsed-realtime timestamp before they are accepted.
 */
internal fun isBetterLocation(candidate: Location, previous: Location): Boolean {
    val timeDelta = measurementTimeDeltaMillis(candidate, previous)
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

private fun measurementTimeDeltaMillis(candidate: Location, previous: Location): Long {
    val candidateElapsed = candidate.elapsedRealtimeNanos
    val previousElapsed = previous.elapsedRealtimeNanos
    if (candidateElapsed > 0L && previousElapsed > 0L) {
        return (candidateElapsed - previousElapsed) / 1_000_000L
    }
    return candidate.time - previous.time
}
