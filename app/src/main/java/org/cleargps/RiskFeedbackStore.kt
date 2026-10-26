package org.cleargps

import android.content.SharedPreferences
import org.cleargps.core.RiskFeedbackCodec
import org.cleargps.core.RiskFeedbackRecord

/** Stores a small local-only ledger; exporting it is always an explicit user action. */
internal object RiskFeedbackStore {
    private const val KEY_LEDGER = "risk_feedback_ledger_v1"
    private val lock = Any()

    fun count(prefs: SharedPreferences): Int = synchronized(lock) {
        RiskFeedbackCodec.decodeStorage(prefs.getString(KEY_LEDGER, "").orEmpty()).size
    }

    fun append(prefs: SharedPreferences, record: RiskFeedbackRecord) = synchronized(lock) {
        val existing = RiskFeedbackCodec.decodeStorage(prefs.getString(KEY_LEDGER, "").orEmpty())
        val updated = (existing + record).takeLast(RiskFeedbackCodec.MAX_RECORDS)
        prefs.edit().putString(KEY_LEDGER, RiskFeedbackCodec.encodeStorage(updated)).apply()
    }

    fun clear(prefs: SharedPreferences) = synchronized(lock) {
        prefs.edit().remove(KEY_LEDGER).apply()
    }

    fun exportJson(prefs: SharedPreferences): String = synchronized(lock) {
        RiskFeedbackCodec.toExportJson(RiskFeedbackCodec.decodeStorage(prefs.getString(KEY_LEDGER, "").orEmpty()))
    }
}
