package org.cleargps.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized inputs are independent of Android so that the detector can be unit-tested. */
internal data class PositionFix(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val elapsedRealtimeNanos: Long,
    val speedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val bearingAccuracyDegrees: Double? = null
)

internal data class GnssSignalSnapshot(
    val elapsedRealtimeNanos: Long,
    val satellitesVisible: Int,
    val satellitesUsedInFix: Int,
    val meanCn0DbHz: Double?,
    val cn0SpreadDb: Double?,
    val constellationCount: Int
)

internal data class SpoofingAssessment(
    val score: Int,
    val confirmed: Boolean,
    val reasons: List<String>
)

/**
 * Multi-signal heuristic, not a probability estimator. Strong evidence can trigger immediately;
 * medium evidence needs repeated fixes; once triggered, the alert clears after three clean fixes.
 */
internal class GnssSpoofingDetector {
    private var previousGps: PositionFix? = null
    private var currentSignal: GnssSignalSnapshot? = null
    private var recentSignalAlertUntilNanos = 0L
    private var recentSignalAlertReason: String? = null
    private var active = false
    private var highRiskStreak = 0
    private var cleanStreak = 0

    fun observeGnssSignal(snapshot: GnssSignalSnapshot) {
        if (snapshot.elapsedRealtimeNanos <= 0L) return
        val old = currentSignal
        if (old != null && snapshot.elapsedRealtimeNanos <= old.elapsedRealtimeNanos) return
        if (old != null) {
            val interval = (snapshot.elapsedRealtimeNanos - old.elapsedRealtimeNanos) / 1_000_000_000.0
            if (interval in 0.2..12.0) {
                val oldMean = old.meanCn0DbHz
                val newMean = snapshot.meanCn0DbHz
                if (old.satellitesVisible >= 4 && snapshot.satellitesVisible >= 4 &&
                    oldMean != null && newMean != null && abs(newMean - oldMean) >= 14.0) {
                    latchSignalAlert(snapshot.elapsedRealtimeNanos, "Резко изменился средний уровень сигналов GNSS")
                }
                val usedDelta = abs(snapshot.satellitesUsedInFix - old.satellitesUsedInFix)
                val relativeShift = usedDelta.toDouble() /
                    max(1, max(snapshot.satellitesUsedInFix, old.satellitesUsedInFix))
                if (usedDelta >= 5 && relativeShift >= 0.65 &&
                    old.satellitesUsedInFix >= 4 && snapshot.satellitesVisible >= 5) {
                    latchSignalAlert(snapshot.elapsedRealtimeNanos, "Резко изменился состав спутников, используемых в фиксе")
                }
                if (old.constellationCount >= 3 && snapshot.constellationCount == 1 &&
                    snapshot.satellitesVisible >= 6) {
                    latchSignalAlert(snapshot.elapsedRealtimeNanos, "Внезапно пропало разнообразие GNSS-созвездий")
                }
            }
        }
        currentSignal = snapshot
    }

    private fun latchSignalAlert(atNanos: Long, reason: String) {
        recentSignalAlertUntilNanos = max(recentSignalAlertUntilNanos, atNanos + 10_000_000_000L)
        recentSignalAlertReason = reason
    }

    fun assess(
        gps: PositionFix,
        network: PositionFix?,
        inRiskZone: Boolean,
        nowElapsedRealtimeNanos: Long,
        advanceState: Boolean
    ): SpoofingAssessment {
        val evidence = LinkedHashMap<String, Int>()
        var motionScore = 0
        var motionReason: String? = null
        var crossSourceScore = 0
        var signalScore = 0
        var signalReason: String? = null

        val previous = previousGps
        if (previous != null && gps.elapsedRealtimeNanos > previous.elapsedRealtimeNanos) {
            val dt = (gps.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
            val oldAccuracy = previous.accuracyMeters
            val newAccuracy = gps.accuracyMeters
            if (dt in 0.2..60.0 && oldAccuracy != null && newAccuracy != null &&
                oldAccuracy in 0.0..150.0 && newAccuracy in 0.0..150.0) {
                val distance = distanceMeters(previous.latitude, previous.longitude, gps.latitude, gps.longitude)
                // Discount a conservative uncertainty allowance before evaluating physical motion.
                val uncertaintyAllowance = max(60.0, 2.0 * (oldAccuracy + newAccuracy))
                val effectiveSpeed = max(0.0, distance - uncertaintyAllowance) / dt
                when {
                    effectiveSpeed > 130.0 -> {
                        motionScore = 90
                        motionReason = "Физически неправдоподобный скачок координат между GNSS-фиксами"
                    }
                    effectiveSpeed > 90.0 -> {
                        motionScore = 76
                        motionReason = "Очень резкий скачок координат между GNSS-фиксами"
                    }
                    effectiveSpeed > 55.0 -> {
                        motionScore = 55
                        motionReason = "Скорость перемещения по координатам необычно высокая"
                    }
                }

                val reportedSpeed = gps.speedMetersPerSecond
                val stationaryLimit = max(3.0, min(12.0, (oldAccuracy + newAccuracy) * 0.2))
                if (dt >= 4.0 && distance <= stationaryLimit &&
                    reportedSpeed != null && reportedSpeed >= 12.0 &&
                    oldAccuracy <= 30.0 && newAccuracy <= 30.0 && motionScore < 40) {
                    motionScore = 40
                    motionReason = "Координаты почти не меняются, хотя GNSS сообщает движение"
                }

                // Speed and displacement may share a receiver, so this is supporting evidence.
                if (dt in 1.0..30.0 && distance >= 50.0 &&
                    oldAccuracy <= 60.0 && newAccuracy <= 60.0 && reportedSpeed != null) {
                    val derivedSpeed = distance / dt
                    val speedAccuracy = gps.speedAccuracyMetersPerSecond?.coerceIn(0.0, 20.0) ?: 5.0
                    val tolerance = max(18.0, speedAccuracy * 3.0 + (oldAccuracy + newAccuracy) / dt)
                    val mismatch = abs(derivedSpeed - reportedSpeed)
                    if (mismatch > tolerance * 2.0 && motionScore < 45) {
                        motionScore = 45
                        motionReason = "Скорость в фиксе противоречит смещению координат"
                    } else if (mismatch > tolerance && motionScore < 30) {
                        motionScore = 30
                        motionReason = "Есть расхождение скорости и смещения координат"
                    }
                }

                // Course consistency is weak evidence because turns and handset orientation matter.
                val bearing = gps.bearingDegrees
                val bearingAccuracy = gps.bearingAccuracyDegrees
                if (distance >= 100.0 && reportedSpeed != null && reportedSpeed >= 15.0 &&
                    bearing != null && bearingAccuracy != null && bearingAccuracy <= 20.0) {
                    val track = initialBearingDegrees(previous.latitude, previous.longitude, gps.latitude, gps.longitude)
                    if (angularDifference(bearing, track) >= 125.0 && motionScore < 35) {
                        motionScore = 35
                        motionReason = "Направление движения не согласуется с траекторией координат"
                    }
                }
            }
        }

        val receiverSpeed = gps.speedMetersPerSecond
        if (receiverSpeed != null && receiverSpeed > 400.0 && motionScore < 78) {
            motionScore = 78
            motionReason = "GNSS сообщает экстремальную скорость движения"
        } else if (receiverSpeed != null && receiverSpeed > 300.0 && motionScore < 35) {
            // Aircraft can be fast: alone, this can never confirm spoofing.
            motionScore = 35
            motionReason = "GNSS сообщает необычно высокую скорость"
        }
        if (motionScore > 0 && motionReason != null) evidence[motionReason] = motionScore

        if (network != null && !network.provider.equals("gps", ignoreCase = true) &&
            isFresh(network, nowElapsedRealtimeNanos, 45_000_000_000L) &&
            goodAccuracy(gps, 250.0) && goodAccuracy(network, 500.0)) {
            val separation = distanceMeters(gps.latitude, gps.longitude, network.latitude, network.longitude)
            val ga = gps.accuracyMeters ?: Double.MAX_VALUE
            val na = network.accuracyMeters ?: Double.MAX_VALUE
            val limit = max(250.0, 3.0 * sqrt(ga * ga + na * na))
            if (separation > limit) {
                crossSourceScore = if (separation > limit * 2.5) 76 else 65
                evidence["GPS заметно расходится с независимой сетевой позицией"] = crossSourceScore
            }
        }

        val signal = currentSignal
        if (signal != null && isSignalFresh(signal, nowElapsedRealtimeNanos)) {
            if (signal.satellitesVisible >= 8 && signal.cn0SpreadDb != null &&
                signal.cn0SpreadDb in 0.0..1.2) {
                signalScore += 10
                signalReason = "Необычно одинаковый уровень сигналов видимых спутников"
            }
            if (nowElapsedRealtimeNanos >= signal.elapsedRealtimeNanos &&
                nowElapsedRealtimeNanos <= recentSignalAlertUntilNanos &&
                recentSignalAlertReason != null) {
                signalScore += 15
                signalReason = recentSignalAlertReason
            }
        }
        signalScore = signalScore.coerceAtMost(25)
        if (signalScore > 0 && signalReason != null) evidence[signalReason] = signalScore

        var rawScore = max(motionScore, crossSourceScore)
        if (motionScore > 0 && crossSourceScore > 0) rawScore += 20
        if (signalScore > 0 && (motionScore > 0 || crossSourceScore > 0)) rawScore += min(15, signalScore)
        else if (signalScore > 0) rawScore = max(rawScore, signalScore)
        if (inRiskZone) {
            rawScore = max(rawScore, 75)
            evidence["Текущая GPS-позиция находится в вручную отмеченной зоне риска"] = 75
        }
        rawScore = rawScore.coerceIn(0, 100)

        val corroborated = (motionScore > 0 && crossSourceScore > 0) ||
            (crossSourceScore > 0 && signalScore >= 10) ||
            (motionScore >= 55 && signalScore >= 10)

        if (inRiskZone) {
            active = true
            highRiskStreak = 0
            cleanStreak = 0
        } else if (advanceState) {
            when {
                rawScore >= 92 || (rawScore >= 85 && motionScore >= 75) ||
                    (rawScore >= 75 && corroborated) -> {
                    active = true
                    highRiskStreak = 0
                    cleanStreak = 0
                }
                rawScore >= 60 -> {
                    highRiskStreak += 1
                    cleanStreak = 0
                    if (highRiskStreak >= 2) active = true
                }
                rawScore < 25 -> {
                    highRiskStreak = 0
                    cleanStreak += 1
                    if (cleanStreak >= 3) active = false
                }
                else -> {
                    highRiskStreak = 0
                    cleanStreak = 0
                }
            }
        }

        if (active && rawScore < 60) {
            evidence["Тревога удерживается до трёх последовательных спокойных фиксов"] = 60
        }
        if (advanceState && gps.elapsedRealtimeNanos > (previousGps?.elapsedRealtimeNanos ?: 0L)) {
            previousGps = gps
        }

        val score = if (active && rawScore < 60) max(rawScore, 60) else rawScore
        val reasons = evidence.entries.sortedByDescending { it.value }.map { it.key }.distinct().take(4)
        return SpoofingAssessment(score, active, reasons)
    }

    private fun goodAccuracy(fix: PositionFix, maxAccuracy: Double): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy.isFinite() && accuracy in 0.0..maxAccuracy
    }

    private fun isFresh(fix: PositionFix, now: Long, maxAge: Long): Boolean =
        fix.elapsedRealtimeNanos > 0L && now >= fix.elapsedRealtimeNanos &&
            now - fix.elapsedRealtimeNanos <= maxAge

    private fun isSignalFresh(signal: GnssSignalSnapshot, now: Long): Boolean =
        signal.elapsedRealtimeNanos > 0L && now >= signal.elapsedRealtimeNanos &&
            now - signal.elapsedRealtimeNanos <= 10_000_000_000L

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(p1) * cos(p2) * sin(dLon / 2.0) * sin(dLon / 2.0)
            return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        private fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLon = Math.toRadians(lon2 - lon1)
            val y = sin(dLon) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
            return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
        }

        private fun angularDifference(first: Double, second: Double): Double {
            val delta = abs((first - second) % 360.0)
            return min(delta, 360.0 - delta)
        }
    }
}
