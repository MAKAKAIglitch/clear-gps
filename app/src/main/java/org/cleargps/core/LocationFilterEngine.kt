package org.cleargps.core

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

enum class FilterState { WAITING, NORMAL, ZONE_RISK, CORRECTING, NO_FALLBACK }

data class FilterDecision(
    val state: FilterState,
    val riskScore: Int,
    val inRiskZone: Boolean,
    val source: String,
    val outputLocation: Location?,
    val reasons: List<String> = emptyList()
)

class LocationFilterEngine {
    private var gps: Location? = null
    private var network: Location? = null
    private var trusted: Location? = null
    private var gpsInZone = false
    private val detector = GnssSpoofingDetector()
    private var decision = FilterDecision(FilterState.WAITING, 0, false, "NONE", null)

    fun currentDecision() = decision.copy(
        outputLocation = decision.outputLocation?.let(::Location),
        reasons = decision.reasons.toList()
    )

    internal fun observeGnssSignal(snapshot: GnssSignalSnapshot) {
        detector.observeGnssSignal(snapshot)
    }

    fun observe(location: Location, inRiskZone: Boolean): FilterDecision {
        // Ignore our own optional mock-location output to avoid a feedback loop.
        if (isMockLocation(location) || !validLocation(location)) return currentDecision()
        val isGps = location.provider.equals(LocationManager.GPS_PROVIDER, true)
        if (isGps) {
            val previous = gps
            if (previous != null && location.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) {
                // Do not let reordered provider callbacks roll state backward.
                return currentDecision()
            }
            gps = Location(location)
            gpsInZone = inRiskZone
        } else {
            val previous = network
            if (previous != null && location.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) {
                return currentDecision()
            }
            network = Location(location)
        }
        decision = evaluate(SystemClock.elapsedRealtimeNanos(), isGps)
        return currentDecision()
    }

    private fun evaluate(nowNanos: Long, changedGps: Boolean): FilterDecision {
        val currentGps = gps?.takeIf { fresh(it, nowNanos, GPS_MAX_AGE_MS) }
        val currentNetwork = network
        if (currentGps == null) {
            val fallback = currentNetwork?.takeIf { fresh(it, nowNanos, NETWORK_MAX_AGE_MS) && usable(it) }
            return FilterDecision(
                if (fallback == null) FilterState.WAITING else FilterState.NORMAL,
                0, false, if (fallback == null) "NONE" else "NETWORK", fallback?.let(::Location)
            )
        }

        val assessment = detector.assess(
            gps = currentGps.toPositionFix(),
            network = currentNetwork?.toPositionFix(),
            inRiskZone = gpsInZone,
            nowElapsedRealtimeNanos = nowNanos,
            advanceState = changedGps
        )
        // Use an alternate source immediately for a high-risk candidate; temporal confirmation
        // and recovery are managed separately so brief anomalies cannot rapidly flip the alert.
        val suspicious = assessment.confirmed || gpsInZone || assessment.score >= CORRECTION_CANDIDATE_SCORE
        if (!suspicious) {
            if (assessment.score < CORRECTION_CANDIDATE_SCORE) trusted = Location(currentGps)
            return FilterDecision(
                FilterState.NORMAL, assessment.score, false, "GPS", Location(currentGps), assessment.reasons
            )
        }

        val fallback = currentNetwork?.takeIf {
            fresh(it, nowNanos, NETWORK_MAX_AGE_MS) && usable(it)
        } ?: trusted?.takeIf {
            fresh(it, nowNanos, TRUSTED_MAX_AGE_MS) && !isMockLocation(it)
        }
        return FilterDecision(
            when {
                gpsInZone -> FilterState.ZONE_RISK
                fallback == null -> FilterState.NO_FALLBACK
                else -> FilterState.CORRECTING
            },
            assessment.score,
            gpsInZone,
            if (fallback == null) "NONE" else sourceOf(fallback),
            fallback?.let(::Location),
            assessment.reasons
        )
    }

    private fun validLocation(location: Location): Boolean =
        location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.elapsedRealtimeNanos > 0L &&
            location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0f

    private fun usable(location: Location) =
        !isMockLocation(location) && validLocation(location) && location.accuracy <= MAX_FALLBACK_ACCURACY_METERS

    private fun sourceOf(location: Location) =
        if (location.provider.equals(LocationManager.GPS_PROVIDER, true)) "LAST_TRUSTED" else "NETWORK"

    private fun fresh(location: Location, nowNanos: Long, maxAgeMs: Long): Boolean {
        val timestamp = location.elapsedRealtimeNanos
        return timestamp > 0L && nowNanos >= timestamp &&
            nowNanos - timestamp <= maxAgeMs * 1_000_000L
    }

    private fun Location.toPositionFix() = PositionFix(
        provider = provider ?: "",
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy() && accuracy.isFinite()) accuracy.toDouble() else null,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        speedMetersPerSecond = if (hasSpeed() && speed.isFinite()) speed.toDouble() else null,
        speedAccuracyMetersPerSecond = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hasSpeedAccuracy() && speedAccuracyMetersPerSecond.isFinite()) speedAccuracyMetersPerSecond.toDouble() else null,
        bearingDegrees = if (hasBearing() && bearing.isFinite()) bearing.toDouble() else null,
        bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hasBearingAccuracy() && bearingAccuracyDegrees.isFinite()) bearingAccuracyDegrees.toDouble() else null
    )

    companion object {
        private const val GPS_MAX_AGE_MS = 20_000L
        private const val NETWORK_MAX_AGE_MS = 45_000L
        private const val TRUSTED_MAX_AGE_MS = 120_000L
        private const val MAX_FALLBACK_ACCURACY_METERS = 2_000f
        private const val CORRECTION_CANDIDATE_SCORE = 60

        @Suppress("DEPRECATION")
        fun isMockLocation(location: Location) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
    }
}
