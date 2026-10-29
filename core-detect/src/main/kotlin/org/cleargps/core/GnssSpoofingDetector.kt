package org.cleargps.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized inputs are independent of Android so that the detector can be unit-tested. */
data class PositionFix(
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

data class GnssSignalSnapshot(
    val elapsedRealtimeNanos: Long,
    val satellitesVisible: Int,
    val satellitesUsedInFix: Int,
    val meanCn0DbHz: Double?,
    val cn0SpreadDb: Double?,
    val constellationCount: Int,
    val strongSignalCount: Int = 0
)

/** Ephemeral local radio-position reference; it is never persisted by the detector. */
data class RadioPositionSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val elapsedRealtimeNanos: Long
)

/**
 * A read-only OBD-II vehicle-speed sample paired with a physical GNSS fix.
 * Timestamps use elapsed realtime; query duration rejects delayed responses.
 */
data class ObdVehicleSpeedSnapshot(
    val speedKmh: Int,
    val elapsedRealtimeNanos: Long,
    val queryDurationMillis: Long
)

/** Aggregate RF metrics only; no location, wall-clock timestamp, or raw measurement payload. */
data class GnssRfSnapshot(
    val elapsedRealtimeNanos: Long,
    val signalCount: Int,
    val constellationCount: Int,
    val meanCn0DbHz: Double?,
    val meanAgcDb: Double?,
    val agcSampleCount: Int
)

/**
 * Explains the heuristic score used by this detector.
 * Component scores are internal weights, NOT probabilities or additive percentages.
 * Max-score selection, corroboration bonuses and score floors must be shown separately.
 */
data class RiskBreakdown(
    val available: Boolean = false,
    val motionEvaluated: Boolean = false,
    val motionScore: Int = 0,
    val motionReason: String? = null,
    val crossSourceEvaluated: Boolean = false,
    val crossSourceScore: Int = 0,
    val crossSourceReason: String? = null,
    val radioPositionEvaluated: Boolean = false,
    val radioPositionScore: Int = 0,
    val radioPositionReason: String? = null,
    val obdSpeedEvaluated: Boolean = false,
    val obdSpeedScore: Int = 0,
    val obdSpeedReason: String? = null,
    val gnssSignalEvaluated: Boolean = false,
    val gnssSignalScore: Int = 0,
    val gnssSignalReason: String? = null,
    val motionCrossSourceBonus: Int = 0,
    val radioCorroborationBonus: Int = 0,
    val obdSpeedCorroborationBonus: Int = 0,
    val gnssCorroborationBonus: Int = 0,
    val combinedBeforeZone: Int = 0,
    val zoneFloorActive: Boolean = false,
    val zoneRaisedScore: Boolean = false,
    val rawCombinedScore: Int = 0,
    val clampedAtHundred: Boolean = false,
    val temporalHoldFloorApplied: Boolean = false,
    val finalScore: Int = 0
) {
    init {
        require(motionScore in 0..100)
        require(crossSourceScore in 0..100)
        require(radioPositionScore in 0..50)
        require(obdSpeedScore in 0..35)
        require(gnssSignalScore in 0..100)
        require(motionCrossSourceBonus in 0..20)
        require(radioCorroborationBonus in 0..15)
        require(obdSpeedCorroborationBonus in 0..10)
        require(gnssCorroborationBonus in 0..15)
        require(combinedBeforeZone >= 0)
        require(rawCombinedScore in 0..100)
        require(finalScore in 0..100)
    }
}

data class SpoofingAssessment(
    val score: Int,
    val confirmed: Boolean,
    val reasons: List<String>,
    val breakdown: RiskBreakdown = RiskBreakdown()
)

/**
 * Multi-signal heuristic, not a probability estimator. Strong evidence can trigger immediately;
 * medium evidence needs repeated fixes; once triggered, the alert clears after three clean fixes.
 */
class GnssSpoofingDetector {
    private var previousGps: PositionFix? = null
    private var currentRadioPosition: RadioPositionSnapshot? = null
    private var lastRadioPositionAssessedAtNanos = 0L
    private var lastRadioPositionDisagreementAtNanos = 0L
    private var radioPositionDisagreementStreak = 0
    private var currentObdSpeed: ObdVehicleSpeedSnapshot? = null
    private var lastObdSpeedAssessedAtNanos = 0L
    private var lastObdSpeedDisagreementAtNanos = 0L
    private var obdSpeedDisagreementStreak = 0
    private var currentSignal: GnssSignalSnapshot? = null
    private var recentSignalAlertUntilNanos = 0L
    private var recentSignalAlertAtNanos = 0L
    private var recentSignalAlertReason: String? = null
    private var recentSignalAlertScore = 0
    private var previousRf: GnssRfSnapshot? = null
    private var underusedStrongSignalStreak = 0
    private var rfPowerMismatchStreak = 0
    private var active = false
    private var highRiskStreak = 0
    private var cleanStreak = 0
    private val deviceBaseline = GnssDeviceBaseline()

    /**
     * Accepts only a newer, validated local radio fix. Its disagreement is supporting evidence:
     * this source is deliberately capped below the detector's standalone confirmation threshold.
     */
    fun observeRadioPosition(snapshot: RadioPositionSnapshot) {
        if (snapshot.elapsedRealtimeNanos <= 0L ||
            !snapshot.latitude.isFinite() || snapshot.latitude !in -90.0..90.0 ||
            !snapshot.longitude.isFinite() || snapshot.longitude !in -180.0..180.0 ||
            !snapshot.accuracyMeters.isFinite() || snapshot.accuracyMeters !in 1.0..250.0
        ) return
        val old = currentRadioPosition
        if (old != null && snapshot.elapsedRealtimeNanos <= old.elapsedRealtimeNanos) return
        currentRadioPosition = snapshot
    }

    /** New read-only standard PID 0D sample; duplicates never add evidence. */
    fun observeObdVehicleSpeed(snapshot: ObdVehicleSpeedSnapshot) {
        if (snapshot.speedKmh !in 0..MAX_PLAUSIBLE_OBD_SPEED_KMH ||
            snapshot.elapsedRealtimeNanos <= 0L ||
            snapshot.queryDurationMillis !in 0L..OBD_SPEED_MAX_QUERY_DURATION_MILLIS
        ) return
        val old = currentObdSpeed
        if (old != null && snapshot.elapsedRealtimeNanos <= old.elapsedRealtimeNanos) return
        currentObdSpeed = snapshot
    }

    /** Clears ephemeral OBD evidence after a stale sample or disconnect. */
    fun clearObdSpeedEvidence() {
        currentObdSpeed = null
        lastObdSpeedAssessedAtNanos = 0L
        lastObdSpeedDisagreementAtNanos = 0L
        obdSpeedDisagreementStreak = 0
    }

    fun observeGnssSignal(snapshot: GnssSignalSnapshot) {
        if (snapshot.elapsedRealtimeNanos <= 0L ||
            !GnssEnvironmentClassifier.isValidSnapshot(snapshot)
        ) return
        val old = currentSignal
        if (old != null && snapshot.elapsedRealtimeNanos <= old.elapsedRealtimeNanos) return
        val intervalSeconds = old?.let {
            (snapshot.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1_000_000_000.0
        }
        val hasContinuousPreviousSample = intervalSeconds?.let { it in 0.2..12.0 } == true
        if (old != null) {
            val interval = intervalSeconds ?: Double.NaN
            if (hasContinuousPreviousSample) {
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

        // Strong RF with a repeated collapse in satellites used for navigation is suspicious,
        // but not proof: geometry and receiver issues can produce a similar pattern.
        val underusedStrongSignals =
            snapshot.satellitesVisible >= 8 &&
                snapshot.strongSignalCount >= 5 &&
                snapshot.meanCn0DbHz?.let { it.isFinite() && it >= 32.0 } == true &&
                snapshot.satellitesUsedInFix <= max(2, snapshot.strongSignalCount / 4)
        if (underusedStrongSignals) {
            // A delayed callback or a long GNSS outage breaks the evidence streak.
            // Sparse snapshots must not be treated as consecutive receiver behaviour.
            underusedStrongSignalStreak = if (hasContinuousPreviousSample) {
                underusedStrongSignalStreak + 1
            } else {
                1
            }
            if (underusedStrongSignalStreak >= 2) {
                latchSignalAlert(
                    snapshot.elapsedRealtimeNanos,
                    "Сильные сигналы GNSS сохраняются, но спутники почти не используются для фикса",
                    35
                )
            }
        } else {
            underusedStrongSignalStreak = 0
        }
        currentSignal = snapshot
    }

    /**
     * Compare aggregate RF metrics between epochs, accepting only sane and fresh monotonic samples.
     * Rejected snapshots leave the prior sample and evidence streak untouched.
     */
    fun observeGnssMeasurements(
        snapshot: GnssRfSnapshot,
        nowElapsedRealtimeNanos: Long
    ): Boolean {
        if (!isUsableRfSnapshot(snapshot, nowElapsedRealtimeNanos)) return false
        val old = previousRf
        if (old != null && snapshot.elapsedRealtimeNanos <= old.elapsedRealtimeNanos) return false
        if (old != null) {
            val interval = (snapshot.elapsedRealtimeNanos - old.elapsedRealtimeNanos) / 1_000_000_000.0
            val oldCn0 = old.meanCn0DbHz
            val newCn0 = snapshot.meanCn0DbHz
            val oldAgc = old.meanAgcDb
            val newAgc = snapshot.meanAgcDb
            if (interval in 0.2..8.0 &&
                old.signalCount >= 6 && snapshot.signalCount >= 6 &&
                old.constellationCount >= 2 && snapshot.constellationCount >= 2 &&
                old.agcSampleCount >= 4 && snapshot.agcSampleCount >= 4 &&
                oldCn0 != null && newCn0 != null && oldCn0.isFinite() && newCn0.isFinite() &&
                oldAgc != null && newAgc != null && oldAgc.isFinite() && newAgc.isFinite()
            ) {
                val cn0Delta = newCn0 - oldCn0
                val agcDelta = newAgc - oldAgc
                if (abs(cn0Delta) >= 5.0 && abs(agcDelta) >= 2.5 && cn0Delta * agcDelta < 0.0) {
                    rfPowerMismatchStreak++
                    if (rfPowerMismatchStreak >= 2) {
                        latchSignalAlert(
                            snapshot.elapsedRealtimeNanos,
                            "Повторяются противоположные скачки C/N₀ и AGC",
                            30
                        )
                    }
                } else {
                    rfPowerMismatchStreak = 0
                }
            } else {
                rfPowerMismatchStreak = 0
            }
        }
        previousRf = snapshot
        return true
    }

    private fun isUsableRfSnapshot(
        snapshot: GnssRfSnapshot,
        nowElapsedRealtimeNanos: Long
    ): Boolean {
        val measuredAt = snapshot.elapsedRealtimeNanos
        if (nowElapsedRealtimeNanos <= 0L ||
            measuredAt <= 0L ||
            measuredAt > nowElapsedRealtimeNanos ||
            nowElapsedRealtimeNanos - measuredAt > RF_SNAPSHOT_MAX_AGE_NANOS
        ) return false
        if (snapshot.signalCount !in 0..MAX_RF_SIGNAL_COUNT ||
            snapshot.constellationCount !in 0..MAX_RF_CONSTELLATION_COUNT ||
            snapshot.agcSampleCount !in 0..MAX_RF_SIGNAL_COUNT ||
            snapshot.agcSampleCount > snapshot.signalCount
        ) return false
        if (snapshot.meanCn0DbHz?.let { !it.isFinite() || it !in 0.0..MAX_RF_CN0_DB_HZ } == true) return false
        if (snapshot.meanAgcDb?.let { !it.isFinite() || it !in MIN_RF_AGC_DB..MAX_RF_AGC_DB } == true) return false
        return true
    }

    /**
     * Returns read-only reception diagnostics for UI/logging. This does not alter risk, state,
     * baseline learning, or mock publication.
     */
    fun receptionDiagnostics(nowElapsedRealtimeNanos: Long): GnssReceptionDiagnostics =
        GnssReceptionDiagnostics(
            environment = GnssEnvironmentClassifier.classify(currentSignal, nowElapsedRealtimeNanos),
            baselineDeviation = GnssBaselineDeviationAnalyzer.assess(
                signal = currentSignal,
                rf = previousRf,
                baseline = deviceBaseline.snapshot(),
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
        )

    /** Returns aggregate, coordinate-free baseline statistics for local persistence/diagnostics. */
    fun deviceBaselineSnapshot(): GnssDeviceBaselineSnapshot = deviceBaseline.snapshot()

    /** Restores a validated aggregate baseline; never restores position or raw radio data. */
    fun restoreDeviceBaseline(snapshot: GnssDeviceBaselineSnapshot): Boolean = deviceBaseline.restore(snapshot)

    /** Signal-only evidence can warn, but is never sufficient to confirm spoofing. */
    fun assessSignalOnly(nowElapsedRealtimeNanos: Long): SpoofingAssessment {
        val reason = recentSignalAlertReason
        val activeAlert = recentSignalAlertAtNanos > 0L &&
            nowElapsedRealtimeNanos >= recentSignalAlertAtNanos &&
            nowElapsedRealtimeNanos <= recentSignalAlertUntilNanos &&
            reason != null && recentSignalAlertScore > 0
        return if (activeAlert) {
            val score = recentSignalAlertScore.coerceIn(0, 50)
            SpoofingAssessment(
                score = score,
                confirmed = false,
                reasons = listOf(reason!!),
                breakdown = RiskBreakdown(
                    available = true,
                    gnssSignalEvaluated = true,
                    gnssSignalScore = score,
                    gnssSignalReason = reason,
                    combinedBeforeZone = score,
                    rawCombinedScore = score,
                    finalScore = score
                )
            )
        } else {
            SpoofingAssessment(0, false, emptyList())
        }
    }

    private fun latchSignalAlert(atNanos: Long, reason: String, score: Int = 15) {
        if (atNanos <= 0L) return
        val alertStillActive = recentSignalAlertAtNanos > 0L &&
            atNanos >= recentSignalAlertAtNanos && atNanos <= recentSignalAlertUntilNanos
        if (!alertStillActive || score >= recentSignalAlertScore) {
            recentSignalAlertAtNanos = atNanos
            recentSignalAlertReason = reason
            recentSignalAlertScore = score.coerceIn(0, 50)
        }
        recentSignalAlertUntilNanos = max(recentSignalAlertUntilNanos, atNanos + 10_000_000_000L)
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
        var motionEvaluated = false
        var crossSourceScore = 0
        var crossSourceReason: String? = null
        var crossSourceEvaluated = false
        var radioPositionScore = 0
        var radioPositionReason: String? = null
        var radioPositionEvaluated = false
        var obdSpeedScore = 0
        var obdSpeedReason: String? = null
        var obdSpeedEvaluated = false
        var signalScore = 0
        var signalReason: String? = null
        var signalEvaluated = false

        val previous = previousGps
        if (previous != null && gps.elapsedRealtimeNanos > previous.elapsedRealtimeNanos) {
            val dt = (gps.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
            val oldAccuracy = previous.accuracyMeters
            val newAccuracy = gps.accuracyMeters
            // A long observation gap can happen in tunnels, parking or urban canyons.
            // Without a continuous track, displacement alone is not enough to call a jump spoofing.
            if (dt in 0.2..60.0 && oldAccuracy != null && newAccuracy != null &&
                oldAccuracy in 0.0..150.0 && newAccuracy in 0.0..150.0) {
                motionEvaluated = true
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
        if (receiverSpeed != null && receiverSpeed.isFinite() && receiverSpeed >= 0.0) {
            motionEvaluated = true
        }
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
            crossSourceEvaluated = true
            val separation = distanceMeters(gps.latitude, gps.longitude, network.latitude, network.longitude)
            val ga = gps.accuracyMeters ?: Double.MAX_VALUE
            val na = network.accuracyMeters ?: Double.MAX_VALUE
            val limit = max(250.0, 3.0 * sqrt(ga * ga + na * na))
            if (separation > limit) {
                crossSourceScore = if (separation > limit * 2.5) 76 else 65
                val reason = "GPS заметно расходится с независимой сетевой позицией"
                crossSourceReason = reason
                evidence[reason] = crossSourceScore
            }
        }

        val radioPosition = currentRadioPosition
        if (radioPosition != null &&
            isRadioPositionFresh(radioPosition, nowElapsedRealtimeNanos) &&
            goodAccuracy(gps, 150.0) &&
            abs(gps.elapsedRealtimeNanos - radioPosition.elapsedRealtimeNanos) <= RADIO_GNSS_PAIRING_WINDOW_NANOS
        ) {
            radioPositionEvaluated = true
            val separation = distanceMeters(
                gps.latitude, gps.longitude, radioPosition.latitude, radioPosition.longitude
            )
            val gpsAccuracy = gps.accuracyMeters ?: Double.MAX_VALUE
            val radioAccuracy = radioPosition.accuracyMeters
            val allowedSeparation = max(150.0, 3.0 * sqrt(
                gpsAccuracy * gpsAccuracy + radioAccuracy * radioAccuracy
            ))
            if (radioPosition.elapsedRealtimeNanos > lastRadioPositionAssessedAtNanos) {
                if (separation > allowedSeparation) {
                    val interval = radioPosition.elapsedRealtimeNanos - lastRadioPositionDisagreementAtNanos
                    radioPositionDisagreementStreak = if (
                        radioPositionDisagreementStreak > 0 &&
                        interval in RADIO_OBSERVATION_MIN_INTERVAL_NANOS..RADIO_OBSERVATION_MAX_INTERVAL_NANOS
                    ) {
                        (radioPositionDisagreementStreak + 1).coerceAtMost(3)
                    } else {
                        1
                    }
                    lastRadioPositionDisagreementAtNanos = radioPosition.elapsedRealtimeNanos
                } else {
                    radioPositionDisagreementStreak = 0
                    lastRadioPositionDisagreementAtNanos = 0L
                }
                lastRadioPositionAssessedAtNanos = radioPosition.elapsedRealtimeNanos
            }
            if (separation > allowedSeparation) {
                radioPositionScore = when (radioPositionDisagreementStreak) {
                    0, 1 -> 25
                    2 -> 35
                    else -> 45
                }
                val reason = if (radioPositionDisagreementStreak >= 3) {
                    "Повторяются расхождения физического GPS и локальной радио-оценки"
                } else {
                    "GPS расходится с локальной радио-оценкой сильнее совокупной погрешности"
                }
                radioPositionReason = reason
                evidence[reason] = radioPositionScore
            } else {
                radioPositionReason = "GPS и локальная радио-оценка согласуются в пределах погрешности"
            }
        }

        val obdSpeed = currentObdSpeed
        if (obdSpeed != null &&
            isObdVehicleSpeedFresh(obdSpeed, nowElapsedRealtimeNanos) &&
            gps.provider.equals("gps", ignoreCase = true) &&
            goodAccuracy(gps, OBD_GNSS_MAX_POSITION_ACCURACY_METERS) &&
            gps.speedMetersPerSecond?.let { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_GNSS_SPEED_MPS } == true &&
            gps.speedAccuracyMetersPerSecond?.let {
                it.isFinite() && it in 0.0..OBD_GNSS_MAX_SPEED_ACCURACY_MPS
            } == true &&
            gps.elapsedRealtimeNanos >= obdSpeed.elapsedRealtimeNanos &&
            gps.elapsedRealtimeNanos - obdSpeed.elapsedRealtimeNanos <= OBD_GNSS_PAIRING_WINDOW_NANOS
        ) {
            obdSpeedEvaluated = true
            val gnssSpeedKmh = gps.speedMetersPerSecond!! * 3.6
            val highestSpeedKmh = max(obdSpeed.speedKmh.toDouble(), gnssSpeedKmh)
            val differenceKmh = abs(obdSpeed.speedKmh - gnssSpeedKmh)
            val disagreementLimitKmh = max(
                OBD_SPEED_MIN_DISAGREEMENT_KMH,
                highestSpeedKmh * OBD_SPEED_RELATIVE_DISAGREEMENT
            )
            val comparableSpeed = highestSpeedKmh >= OBD_SPEED_MIN_COMPARABLE_KMH
            val disagrees = comparableSpeed && differenceKmh > disagreementLimitKmh
            val newObdSample = obdSpeed.elapsedRealtimeNanos > lastObdSpeedAssessedAtNanos
            if (newObdSample) {
                if (disagrees) {
                    val interval = obdSpeed.elapsedRealtimeNanos - lastObdSpeedDisagreementAtNanos
                    obdSpeedDisagreementStreak = if (
                        obdSpeedDisagreementStreak > 0 &&
                        interval in OBD_SPEED_MIN_SAMPLE_INTERVAL_NANOS..OBD_SPEED_MAX_SAMPLE_INTERVAL_NANOS
                    ) {
                        (obdSpeedDisagreementStreak + 1).coerceAtMost(3)
                    } else {
                        1
                    }
                    lastObdSpeedDisagreementAtNanos = obdSpeed.elapsedRealtimeNanos
                } else if (comparableSpeed) {
                    obdSpeedDisagreementStreak = 0
                    lastObdSpeedDisagreementAtNanos = 0L
                }
                lastObdSpeedAssessedAtNanos = obdSpeed.elapsedRealtimeNanos
            } else if (comparableSpeed && !disagrees) {
                // One OBD observation cannot increase the streak, but a newer consistent
                // physical-GNSS pair must still clear the preceding disagreement.
                obdSpeedDisagreementStreak = 0
                lastObdSpeedDisagreementAtNanos = 0L
            }
            if (disagrees) {
                obdSpeedScore = when (obdSpeedDisagreementStreak) {
                    0, 1 -> 15
                    2 -> 25
                    else -> 35
                }
                obdSpeedReason = if (obdSpeedDisagreementStreak >= 3) {
                    "Повторяется крупное расхождение скорости физического GNSS и OBD-II"
                } else {
                    "Скорости физического GNSS и OBD-II заметно расходятся"
                }
                evidence[obdSpeedReason!!] = obdSpeedScore
            } else {
                obdSpeedReason = if (!comparableSpeed) {
                    "Скорость ниже порога надёжного сравнения; вклад нейтрален"
                } else {
                    "Скорости физического GNSS и OBD-II согласуются в пределах допуска"
                }
            }
        }

        val signal = currentSignal
        if (signal != null && isSignalFresh(signal, nowElapsedRealtimeNanos)) {
            signalEvaluated = true
            if (signal.satellitesVisible >= 8 && signal.cn0SpreadDb != null &&
                signal.cn0SpreadDb in 0.0..1.2) {
                signalScore += 10
                signalReason = "Необычно одинаковый уровень сигналов видимых спутников"
            }
            if (nowElapsedRealtimeNanos >= signal.elapsedRealtimeNanos &&
                nowElapsedRealtimeNanos >= recentSignalAlertAtNanos &&
                nowElapsedRealtimeNanos <= recentSignalAlertUntilNanos &&
                recentSignalAlertReason != null) {
                signalScore = max(signalScore, recentSignalAlertScore)
                signalReason = recentSignalAlertReason
            }
        }
        signalScore = signalScore.coerceAtMost(35)
        if (signalScore > 0 && signalReason != null) evidence[signalReason] = signalScore

        var rawScore = max(max(max(motionScore, crossSourceScore), radioPositionScore), obdSpeedScore)
        val motionCrossSourceBonus = if (motionScore > 0 && crossSourceScore > 0) 20 else 0
        rawScore += motionCrossSourceBonus
        val radioCorroborationBonus =
            if (radioPositionScore > 0 &&
                (motionScore > 0 || crossSourceScore > 0 || signalScore >= 10 || obdSpeedScore >= 15)
            ) min(15, radioPositionScore / 3) else 0
        if (radioCorroborationBonus > 0) rawScore += radioCorroborationBonus
        val obdSpeedCorroborationBonus =
            if (obdSpeedScore > 0 &&
                (motionScore > 0 || crossSourceScore > 0 || radioPositionScore > 0 || signalScore >= 10)
            ) min(10, obdSpeedScore / 4) else 0
        if (obdSpeedCorroborationBonus > 0) rawScore += obdSpeedCorroborationBonus
        val gnssCorroborationBonus =
            if (signalScore > 0 &&
                (motionScore > 0 || crossSourceScore > 0 || radioPositionScore > 0 || obdSpeedScore > 0)
            ) min(15, signalScore) else 0
        if (gnssCorroborationBonus > 0) rawScore += gnssCorroborationBonus
        else if (signalScore > 0) rawScore = max(rawScore, signalScore)
        val combinedBeforeZone = rawScore
        if (inRiskZone) {
            rawScore = max(rawScore, 75)
            evidence["Текущая GPS-позиция находится в вручную отмеченной зоне риска"] = 75
        }
        val clampedAtHundred = rawScore > 100
        rawScore = rawScore.coerceIn(0, 100)

        // Weak direction/speed-consistency hints must not turn network divergence into a
        // confirmed alert. Require a physically extreme trajectory or independent signal evidence.
        val corroborated = (motionScore >= 76 && crossSourceScore > 0) ||
            (crossSourceScore > 0 && signalScore >= 10) ||
            (motionScore >= 55 && signalScore >= 10) ||
            (radioPositionScore >= 35 &&
                (motionScore >= 55 || crossSourceScore > 0 || signalScore >= 10 || obdSpeedScore >= 25)) ||
            (obdSpeedScore >= 25 &&
                (motionScore >= 55 || crossSourceScore > 0 || radioPositionScore >= 25 || signalScore >= 10))

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

        val temporalHoldFloorApplied = active && rawScore < 60
        val score = if (temporalHoldFloorApplied) max(rawScore, 60) else rawScore
        val reasons = evidence.entries.sortedByDescending { it.value }.map { it.key }.distinct().take(4)

        // Train only from a new GPS assessment that remains clearly normal and outside risk zones.
        // Recent signal-only anomaly hints are also excluded even when they are below alert threshold.
        val signalAlertActive = recentSignalAlertAtNanos > 0L &&
            nowElapsedRealtimeNanos >= recentSignalAlertAtNanos &&
            nowElapsedRealtimeNanos <= recentSignalAlertUntilNanos
        if (advanceState) {
            deviceBaseline.observe(
                signal = currentSignal,
                rf = previousRf,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                isNormal = !active && score < 25 && !signalAlertActive,
                inRiskZone = inRiskZone
            )
        }
        val breakdown = RiskBreakdown(
            available = true,
            motionEvaluated = motionEvaluated,
            motionScore = motionScore,
            motionReason = motionReason,
            crossSourceEvaluated = crossSourceEvaluated,
            crossSourceScore = crossSourceScore,
            crossSourceReason = crossSourceReason,
            radioPositionEvaluated = radioPositionEvaluated,
            radioPositionScore = radioPositionScore,
            radioPositionReason = radioPositionReason,
            obdSpeedEvaluated = obdSpeedEvaluated,
            obdSpeedScore = obdSpeedScore,
            obdSpeedReason = obdSpeedReason,
            gnssSignalEvaluated = signalEvaluated,
            gnssSignalScore = signalScore,
            gnssSignalReason = signalReason,
            motionCrossSourceBonus = motionCrossSourceBonus,
            radioCorroborationBonus = radioCorroborationBonus,
            obdSpeedCorroborationBonus = obdSpeedCorroborationBonus,
            gnssCorroborationBonus = gnssCorroborationBonus,
            combinedBeforeZone = combinedBeforeZone,
            zoneFloorActive = inRiskZone,
            zoneRaisedScore = inRiskZone && combinedBeforeZone < 75,
            rawCombinedScore = rawScore,
            clampedAtHundred = clampedAtHundred,
            temporalHoldFloorApplied = temporalHoldFloorApplied,
            finalScore = score
        )
        return SpoofingAssessment(score, active, reasons, breakdown)
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

    private fun isRadioPositionFresh(snapshot: RadioPositionSnapshot, now: Long): Boolean =
        snapshot.elapsedRealtimeNanos > 0L && now >= snapshot.elapsedRealtimeNanos &&
            now - snapshot.elapsedRealtimeNanos <= RADIO_POSITION_MAX_AGE_NANOS

    private fun isObdVehicleSpeedFresh(snapshot: ObdVehicleSpeedSnapshot, now: Long): Boolean =
        snapshot.elapsedRealtimeNanos > 0L && now >= snapshot.elapsedRealtimeNanos &&
            now - snapshot.elapsedRealtimeNanos <= OBD_SPEED_MAX_AGE_NANOS &&
            snapshot.speedKmh in 0..MAX_PLAUSIBLE_OBD_SPEED_KMH &&
            snapshot.queryDurationMillis in 0L..OBD_SPEED_MAX_QUERY_DURATION_MILLIS

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val RADIO_POSITION_MAX_AGE_NANOS = 45_000_000_000L
        private const val RADIO_GNSS_PAIRING_WINDOW_NANOS = 45_000_000_000L
        private const val RADIO_OBSERVATION_MIN_INTERVAL_NANOS = 5_000_000_000L
        private const val RADIO_OBSERVATION_MAX_INTERVAL_NANOS = 90_000_000_000L
        private const val MAX_PLAUSIBLE_OBD_SPEED_KMH = 250
        private const val MAX_PLAUSIBLE_GNSS_SPEED_MPS = 80.0
        private const val OBD_SPEED_MAX_QUERY_DURATION_MILLIS = 1_500L
        private const val OBD_SPEED_MAX_AGE_NANOS = 5_000_000_000L
        private const val RF_SNAPSHOT_MAX_AGE_NANOS = 10_000_000_000L
        private const val MAX_RF_SIGNAL_COUNT = 128
        private const val MAX_RF_CONSTELLATION_COUNT = 8
        private const val MAX_RF_CN0_DB_HZ = 100.0
        private const val MIN_RF_AGC_DB = -200.0
        private const val MAX_RF_AGC_DB = 200.0
        private const val OBD_GNSS_PAIRING_WINDOW_NANOS = 2_500_000_000L
        private const val OBD_GNSS_MAX_POSITION_ACCURACY_METERS = 100.0
        private const val OBD_GNSS_MAX_SPEED_ACCURACY_MPS = 2.5
        private const val OBD_SPEED_MIN_COMPARABLE_KMH = 20.0
        private const val OBD_SPEED_MIN_DISAGREEMENT_KMH = 15.0
        private const val OBD_SPEED_RELATIVE_DISAGREEMENT = 0.35
        private const val OBD_SPEED_MIN_SAMPLE_INTERVAL_NANOS = 250_000_000L
        private const val OBD_SPEED_MAX_SAMPLE_INTERVAL_NANOS = 5_000_000_000L

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
