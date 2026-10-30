package org.cleargps

import android.content.Context
import android.content.Context.MODE_PRIVATE
import java.time.Instant
import java.time.ZoneOffset

internal data class OpenCellIdUsageSnapshot(
    val utcDay: String,
    val requestsUsed: Int,
    val requestsRemaining: Int,
    val serverLimitReached: Boolean,
    val nextBatchAllowedAtMillis: Long
)

internal enum class OpenCellIdBudgetBlock {
    NONE, DAILY_BUDGET, SERVER_LIMIT, COOLDOWN
}

/**
 * A conservative per-installation quota guard. OpenCellID's 1,000-credit allowance is shared
 * across a user's API keys, so Clear GPS intentionally caps itself at 100 attempted requests/day.
 * Every attempted HTTP lookup is reserved before the socket is opened; failed connections still
 * count against this local safety budget because request delivery cannot always be established.
 */
internal object OpenCellIdUsageBudget {
    const val DAILY_APP_BUDGET = 100
    private const val PREFS_NAME = "opencellid_usage_budget"
    private const val KEY_DAY = "utc_day"
    private const val KEY_USED = "requests_used"
    private const val KEY_SERVER_LIMIT = "server_limit_reached"
    private const val KEY_LAST_BATCH = "last_batch_at"
    private const val BATCH_COOLDOWN_MILLIS = 15L * 60L * 1_000L

    @Synchronized
    fun snapshot(context: Context, nowMillis: Long = System.currentTimeMillis()): OpenCellIdUsageSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val today = utcDay(nowMillis)
        ensureDay(prefs, today)
        val used = prefs.getInt(KEY_USED, 0).coerceIn(0, DAILY_APP_BUDGET)
        return OpenCellIdUsageSnapshot(
            utcDay = today,
            requestsUsed = used,
            requestsRemaining = (DAILY_APP_BUDGET - used).coerceAtLeast(0),
            serverLimitReached = prefs.getBoolean(KEY_SERVER_LIMIT, false),
            nextBatchAllowedAtMillis = prefs.getLong(KEY_LAST_BATCH, 0L)
                .takeIf { it > 0L }?.plus(BATCH_COOLDOWN_MILLIS) ?: 0L
        )
    }

    @Synchronized
    fun tryStartBatch(context: Context, nowMillis: Long = System.currentTimeMillis()): OpenCellIdBudgetBlock {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val today = utcDay(nowMillis)
        ensureDay(prefs, today)
        if (prefs.getBoolean(KEY_SERVER_LIMIT, false)) return OpenCellIdBudgetBlock.SERVER_LIMIT
        if (prefs.getInt(KEY_USED, 0) >= DAILY_APP_BUDGET) return OpenCellIdBudgetBlock.DAILY_BUDGET
        val lastBatch = prefs.getLong(KEY_LAST_BATCH, 0L)
        if (lastBatch > 0L && (nowMillis < lastBatch || nowMillis - lastBatch < BATCH_COOLDOWN_MILLIS)) {
            return OpenCellIdBudgetBlock.COOLDOWN
        }
        check(prefs.edit().putLong(KEY_LAST_BATCH, nowMillis).commit()) {
            "Не удалось сохранить защитный интервал OpenCellID."
        }
        return OpenCellIdBudgetBlock.NONE
    }

    @Synchronized
    fun reserveRequest(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        ensureDay(prefs, utcDay(nowMillis))
        if (prefs.getBoolean(KEY_SERVER_LIMIT, false)) return false
        val used = prefs.getInt(KEY_USED, 0).coerceIn(0, DAILY_APP_BUDGET)
        if (used >= DAILY_APP_BUDGET) return false
        return prefs.edit().putInt(KEY_USED, used + 1).commit()
    }

    @Synchronized
    fun markServerLimitReached(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        ensureDay(prefs, utcDay(nowMillis))
        prefs.edit().putBoolean(KEY_SERVER_LIMIT, true).commit()
    }

    @Synchronized
    internal fun clearForTests(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().commit()
    }

    private fun ensureDay(prefs: android.content.SharedPreferences, today: String) {
        if (prefs.getString(KEY_DAY, null) != today) {
            prefs.edit()
                .putString(KEY_DAY, today)
                .putInt(KEY_USED, 0)
                .putBoolean(KEY_SERVER_LIMIT, false)
                .remove(KEY_LAST_BATCH)
                .commit()
        }
    }

    private fun utcDay(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
}
