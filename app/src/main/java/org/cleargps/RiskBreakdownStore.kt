package org.cleargps

import android.content.SharedPreferences
import org.cleargps.core.RiskBreakdown

/** Private, bounded summary of the latest score explanation; never stores raw sensor payloads. */
internal object RiskBreakdownStore {
    private const val MAX_AGE_NANOS = 30_000_000_000L
    private const val KEY_AVAILABLE = "risk_breakdown_available"
    private const val KEY_AT_NANOS = "risk_breakdown_at_nanos"
    private const val KEY_MOTION_EVALUATED = "risk_motion_evaluated"
    private const val KEY_MOTION_SCORE = "risk_motion_score"
    private const val KEY_MOTION_REASON = "risk_motion_reason"
    private const val KEY_CROSS_EVALUATED = "risk_cross_source_evaluated"
    private const val KEY_CROSS_SCORE = "risk_cross_source_score"
    private const val KEY_CROSS_REASON = "risk_cross_source_reason"
    private const val KEY_RADIO_EVALUATED = "risk_radio_position_evaluated"
    private const val KEY_RADIO_SCORE = "risk_radio_position_score"
    private const val KEY_RADIO_REASON = "risk_radio_position_reason"
    private const val KEY_RADIO_BONUS = "risk_radio_corroboration_bonus"
    private const val KEY_SIGNAL_EVALUATED = "risk_gnss_signal_evaluated"
    private const val KEY_SIGNAL_SCORE = "risk_gnss_signal_score"
    private const val KEY_SIGNAL_REASON = "risk_gnss_signal_reason"
    private const val KEY_MOTION_CROSS_BONUS = "risk_motion_cross_bonus"
    private const val KEY_SIGNAL_BONUS = "risk_gnss_corroboration_bonus"
    private const val KEY_COMBINED_BEFORE_ZONE = "risk_combined_before_zone"
    private const val KEY_ZONE_FLOOR_ACTIVE = "risk_zone_floor_active"
    private const val KEY_ZONE_RAISED_SCORE = "risk_zone_raised_score"
    private const val KEY_RAW_SCORE = "risk_raw_combined_score"
    private const val KEY_CLAMPED = "risk_clamped_at_hundred"
    private const val KEY_TEMPORAL_FLOOR = "risk_temporal_hold_floor"
    private const val KEY_FINAL_SCORE = "risk_breakdown_final_score"

    fun put(editor: SharedPreferences.Editor, breakdown: RiskBreakdown?, atElapsedRealtimeNanos: Long) {
        val value = breakdown?.takeIf { it.available && it.finalScore in 0..100 }
        editor.putBoolean(KEY_AVAILABLE, value != null)
            .putLong(KEY_AT_NANOS, if (value != null) atElapsedRealtimeNanos else 0L)
            .putBoolean(KEY_MOTION_EVALUATED, value?.motionEvaluated == true)
            .putInt(KEY_MOTION_SCORE, value?.motionScore ?: 0)
            .putString(KEY_MOTION_REASON, value?.motionReason.orEmpty())
            .putBoolean(KEY_CROSS_EVALUATED, value?.crossSourceEvaluated == true)
            .putInt(KEY_CROSS_SCORE, value?.crossSourceScore ?: 0)
            .putString(KEY_CROSS_REASON, value?.crossSourceReason.orEmpty())
            .putBoolean(KEY_RADIO_EVALUATED, value?.radioPositionEvaluated == true)
            .putInt(KEY_RADIO_SCORE, value?.radioPositionScore ?: 0)
            .putString(KEY_RADIO_REASON, value?.radioPositionReason.orEmpty())
            .putInt(KEY_RADIO_BONUS, value?.radioCorroborationBonus ?: 0)
            .putBoolean(KEY_SIGNAL_EVALUATED, value?.gnssSignalEvaluated == true)
            .putInt(KEY_SIGNAL_SCORE, value?.gnssSignalScore ?: 0)
            .putString(KEY_SIGNAL_REASON, value?.gnssSignalReason.orEmpty())
            .putInt(KEY_MOTION_CROSS_BONUS, value?.motionCrossSourceBonus ?: 0)
            .putInt(KEY_SIGNAL_BONUS, value?.gnssCorroborationBonus ?: 0)
            .putInt(KEY_COMBINED_BEFORE_ZONE, value?.combinedBeforeZone ?: 0)
            .putBoolean(KEY_ZONE_FLOOR_ACTIVE, value?.zoneFloorActive == true)
            .putBoolean(KEY_ZONE_RAISED_SCORE, value?.zoneRaisedScore == true)
            .putInt(KEY_RAW_SCORE, value?.rawCombinedScore ?: 0)
            .putBoolean(KEY_CLAMPED, value?.clampedAtHundred == true)
            .putBoolean(KEY_TEMPORAL_FLOOR, value?.temporalHoldFloorApplied == true)
            .putInt(KEY_FINAL_SCORE, value?.finalScore ?: 0)
    }

    fun write(prefs: SharedPreferences, breakdown: RiskBreakdown?, atElapsedRealtimeNanos: Long) {
        val editor = prefs.edit()
        put(editor, breakdown, atElapsedRealtimeNanos)
        editor.apply()
    }

    fun clear(prefs: SharedPreferences) = write(prefs, null, 0L)

    fun read(prefs: SharedPreferences, nowElapsedRealtimeNanos: Long): RiskBreakdown? {
        if (!prefs.getBoolean(KEY_AVAILABLE, false)) return null
        val at = prefs.getLong(KEY_AT_NANOS, 0L)
        if (nowElapsedRealtimeNanos <= 0L || at <= 0L ||
            nowElapsedRealtimeNanos < at || nowElapsedRealtimeNanos - at > MAX_AGE_NANOS
        ) return null
        val motionScore = prefs.getInt(KEY_MOTION_SCORE, -1)
        val crossScore = prefs.getInt(KEY_CROSS_SCORE, -1)
        val radioScore = prefs.getInt(KEY_RADIO_SCORE, 0)
        val radioBonus = prefs.getInt(KEY_RADIO_BONUS, 0)
        val signalScore = prefs.getInt(KEY_SIGNAL_SCORE, -1)
        val motionBonus = prefs.getInt(KEY_MOTION_CROSS_BONUS, -1)
        val signalBonus = prefs.getInt(KEY_SIGNAL_BONUS, -1)
        val combinedBeforeZone = prefs.getInt(KEY_COMBINED_BEFORE_ZONE, -1)
        val rawScore = prefs.getInt(KEY_RAW_SCORE, -1)
        val finalScore = prefs.getInt(KEY_FINAL_SCORE, -1)
        if (motionScore !in 0..100 || crossScore !in 0..100 || radioScore !in 0..50 ||
            radioBonus !in 0..15 || signalScore !in 0..100 ||
            motionBonus !in 0..20 || signalBonus !in 0..15 || combinedBeforeZone !in 0..150 ||
            rawScore !in 0..100 || finalScore !in 0..100
        ) return null

        return RiskBreakdown(
            available = true,
            motionEvaluated = prefs.getBoolean(KEY_MOTION_EVALUATED, false),
            motionScore = motionScore,
            motionReason = prefs.getString(KEY_MOTION_REASON, null)?.takeIf { it.isNotBlank() },
            crossSourceEvaluated = prefs.getBoolean(KEY_CROSS_EVALUATED, false),
            crossSourceScore = crossScore,
            crossSourceReason = prefs.getString(KEY_CROSS_REASON, null)?.takeIf { it.isNotBlank() },
            radioPositionEvaluated = prefs.getBoolean(KEY_RADIO_EVALUATED, false),
            radioPositionScore = radioScore,
            radioPositionReason = prefs.getString(KEY_RADIO_REASON, null)?.takeIf { it.isNotBlank() },
            gnssSignalEvaluated = prefs.getBoolean(KEY_SIGNAL_EVALUATED, false),
            gnssSignalScore = signalScore,
            gnssSignalReason = prefs.getString(KEY_SIGNAL_REASON, null)?.takeIf { it.isNotBlank() },
            motionCrossSourceBonus = motionBonus,
            radioCorroborationBonus = radioBonus,
            gnssCorroborationBonus = signalBonus,
            combinedBeforeZone = combinedBeforeZone,
            zoneFloorActive = prefs.getBoolean(KEY_ZONE_FLOOR_ACTIVE, false),
            zoneRaisedScore = prefs.getBoolean(KEY_ZONE_RAISED_SCORE, false),
            rawCombinedScore = rawScore,
            clampedAtHundred = prefs.getBoolean(KEY_CLAMPED, false),
            temporalHoldFloorApplied = prefs.getBoolean(KEY_TEMPORAL_FLOOR, false),
            finalScore = finalScore
        )
    }
}
