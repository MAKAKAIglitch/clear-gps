package org.cleargps.core

import android.location.Location
import android.os.Build

enum class FilterState { WAITING, NORMAL, ZONE_RISK, CORRECTING, NO_FALLBACK }

data class FilterDecision(
    val state: FilterState,
    val riskScore: Int,
    val inRiskZone: Boolean,
    val source: String,
    val outputLocation: Location?
)

class LocationFilterEngine {
    private var gps: Location? = null
    private var network: Location? = null
    private var trusted: Location? = null
    private var previousGps: Location? = null
    private var gpsInZone = false
    private var decision = FilterDecision(FilterState.WAITING, 0, false, "NONE", null)

    fun currentDecision() = decision.copy(outputLocation = decision.outputLocation?.let(::Location))

    fun observe(location: Location, inRiskZone: Boolean): FilterDecision {
        if (isMockLocation(location)) return currentDecision()
        val isGps = location.provider.equals("gps", true)
        if (isGps) {
            previousGps = gps?.let(::Location)
            gps = Location(location)
            gpsInZone = inRiskZone
        } else {
            network = Location(location)
        }
        decision = evaluate(System.currentTimeMillis(), isGps)
        return currentDecision()
    }

    private fun evaluate(now: Long, changedGps: Boolean): FilterDecision {
        val currentGps = gps
        val currentNetwork = network
        if (currentGps == null) {
            val fallback = currentNetwork?.takeIf { fresh(it, now, 45_000L) && usable(it) }
            return FilterDecision(if (fallback == null) FilterState.WAITING else FilterState.NORMAL,
                0, false, if (fallback == null) "NONE" else "NETWORK", fallback?.let(::Location))
        }

        var score = if (gpsInZone) 45 else 0
        val previous = previousGps
        if (changedGps && previous != null) {
            val dt = currentGps.time - previous.time
            if (dt in 500L..30_000L) {
                if (previous.distanceTo(currentGps) / (dt / 1000.0) > 100.0) score += 65
            } else if (dt <= 0 && previous.distanceTo(currentGps) > 100f) {
                score += 65
            }
        }
        if (currentGps.hasSpeed() && currentGps.speed > 100f) score += 65

        if (currentNetwork != null && fresh(currentNetwork, now, 45_000L) && usable(currentNetwork) &&
            currentGps.hasAccuracy() && currentGps.accuracy <= 1_000f) {
            val limit = maxOf(250f, 2.5f * (currentGps.accuracy + currentNetwork.accuracy))
            if (currentGps.distanceTo(currentNetwork) > limit) score += 60
        }
        score = score.coerceIn(0, 100)

        val suspicious = gpsInZone || score >= 60
        if (!suspicious) {
            trusted = Location(currentGps)
            return FilterDecision(FilterState.NORMAL, score, false, "GPS", Location(currentGps))
        }

        val fallback = currentNetwork?.takeIf { fresh(it, now, 45_000L) && usable(it) }
            ?: trusted?.takeIf { fresh(it, now, 120_000L) && !isMockLocation(it) }
        return FilterDecision(
            if (fallback == null) FilterState.NO_FALLBACK
            else if (gpsInZone) FilterState.ZONE_RISK else FilterState.CORRECTING,
            score, gpsInZone, if (fallback == null) "NONE" else sourceOf(fallback), fallback?.let(::Location)
        )
    }

    private fun usable(location: Location) =
        !isMockLocation(location) && location.hasAccuracy() && location.accuracy <= 2_000f

    private fun sourceOf(location: Location) = if (location.provider.equals("gps", true)) "LAST_TRUSTED" else "NETWORK"
    private fun fresh(location: Location, now: Long, maxAge: Long) = now >= location.time && now - location.time <= maxAge

    companion object {
        @Suppress("DEPRECATION")
        fun isMockLocation(location: Location) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
    }
}
