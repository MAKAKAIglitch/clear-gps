package org.cleargps

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional, app-private diagnostic log. It deliberately avoids radio identifiers and exact
 * coordinates so that copied logs are useful without needlessly exporting location history.
 */
internal object DiagnosticLogStore {
    const val KEY_ENABLED = "diagnostic_logging_enabled"
    const val KEY_MAX_KB = "diagnostic_log_max_kb"
    const val DEFAULT_MAX_KB = 256
    const val MIN_MAX_KB = 64
    const val MAX_MAX_KB = 512

    private const val LOG_DIRECTORY = "diagnostics"
    private const val LOG_FILE = "clear-gps.log"
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun maxKb(context: Context): Int =
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_KB, DEFAULT_MAX_KB).coerceIn(MIN_MAX_KB, MAX_MAX_KB)

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (enabled) {
                prefs.edit().putBoolean(KEY_ENABLED, true).apply()
                appendLine(context, "INFO", "DIAGNOSTICS", "Подробное логирование включено", force = true)
            } else {
                appendLine(context, "INFO", "DIAGNOSTICS", "Подробное логирование выключено", force = true)
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            }
        }
    }

    fun setMaxKb(context: Context, requestedKb: Int) {
        val normalized = requestedKb.coerceIn(MIN_MAX_KB, MAX_MAX_KB)
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_KB, normalized).apply()
        synchronized(lock) { trimFile(context, maxBytes(context)) }
        log(context, "INFO", "DIAGNOSTICS", "Лимит журнала установлен: $normalized КБ")
    }

    fun log(context: Context, category: String, message: String, level: String = "INFO") {
        if (!isEnabled(context)) return
        synchronized(lock) {
            appendLine(context, level, category, message, force = false)
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        val file = logFile(context)
        if (!file.isFile) return@synchronized "Журнал пуст. Включи подробное логирование и повтори проблему."
        try {
            val raw = file.readText(Charsets.UTF_8)
            val sanitized = raw.split('\n').joinToString("\n") { sanitizeMessage(it) }
            if (sanitized != raw) {
                // Older app versions may have written an identifier before the privacy guard existed.
                runCatching { file.writeText(sanitized, Charsets.UTF_8) }
            }
            sanitized.ifBlank { "Журнал пуст." }
        } catch (error: Exception) {
            "Не удалось прочитать журнал: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        val wasEnabled = isEnabled(context)
        try {
            val file = logFile(context)
            if (file.exists()) file.writeText("", Charsets.UTF_8)
            // Respect the logging-off switch: clearing logs while disabled must leave the file empty.
            if (wasEnabled) appendLine(context, "INFO", "DIAGNOSTICS", "Журнал очищен", force = true)
        } catch (_: Exception) {
            // Diagnostics must never affect location tracking or app startup.
        }
    }

    fun sizeBytes(context: Context): Long = synchronized(lock) {
        try { logFile(context).length() } catch (_: Exception) { 0L }
    }

    private fun appendLine(
        context: Context,
        level: String,
        category: String,
        message: String,
        force: Boolean
    ) {
        if (!force && !isEnabled(context)) return
        try {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            val safeCategory = category.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24).ifBlank { "APP" }
            val safeMessage = sanitizeMessage(message).take(700)
            val timestamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
            val line = "$timestamp [$level] [$safeCategory] $safeMessage\n".toByteArray(Charsets.UTF_8)
            // Append cheaply while there is headroom. Trimming on every line after the cap was
            // reached would rewrite hundreds of kilobytes every second on a live GNSS feed.
            FileOutputStream(file, true).use { output -> output.write(line) }
            val limitBytes = maxBytes(context)
            if (file.length() > limitBytes) {
                // Trim to 75% so subsequent log lines append cheaply until the next rotation.
                trimFile(context, (limitBytes * 3) / 4)
            }
        } catch (_: Exception) {
            // A full disk or inaccessible file must not crash the foreground service.
        }
    }

    private fun trimFile(context: Context, limitBytes: Int) {
        try {
            val file = logFile(context)
            if (file.isFile && file.length() > limitBytes) file.writeBytes(trimToMaxBytes(file.readBytes(), limitBytes))
        } catch (_: Exception) {
        }
    }

    private fun maxBytes(context: Context): Int = maxKb(context) * 1024

    private fun logFile(context: Context): File =
        File(File(context.filesDir, LOG_DIRECTORY), LOG_FILE)

    /**
     * Redacts radio identifiers and precise coordinates at the storage boundary.
     * Callers may include exception messages, so the log sink must enforce the privacy contract
     * rather than relying on every call site to remember it.
     */
    internal fun sanitizeMessage(message: String): String {
        var safe = message.replace('\r', ' ').replace('\n', ' ')
        safe = radioLabelRegex.replace(safe) { match ->
            match.groupValues[1] + match.groupValues[2] + "[REDACTED]"
        }
        safe = cellIdentifierRegex.replace(safe, "[CELL-ID REDACTED]")
        safe = macAddressRegex.replace(safe, "[RADIO-ID REDACTED]")
        safe = labeledCoordinateRegex.replace(safe) { match ->
            match.groupValues[1] + match.groupValues[2] + " " +
                roundCoordinateForLog(match.groupValues[3])
        }
        safe = coordinatePairRegex.replace(safe) { match ->
            roundCoordinateForLog(match.groupValues[1]) + ", " +
                roundCoordinateForLog(match.groupValues[2])
        }
        return safe
    }

    private val radioLabelRegex = Regex(
        """(?i)\b(SSID|BSSID|MAC(?:\s+address)?|device(?:\s+name|\s+address)?)\s*([:=])\s*("[^"]*"|'[^']*'|[^,;|]+)"""
    )
    private val cellIdentifierRegex = Regex("""(?i)\b(?:gsm|wcdma|lte|nr):\d{1,3}:\d{1,3}:\d+:\d+\b""")
    private val macAddressRegex = Regex("""(?i)\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b""")
    private val labeledCoordinateRegex = Regex("""(?i)\b(lat(?:itude)?|lon(?:gitude)?|lng)\s*([:=])\s*(-?\d{1,3}(?:\.\d+)?)""")
    private val coordinatePairRegex = Regex("""(?<![\w.])(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?![\w.])""")

    private fun roundCoordinateForLog(raw: String): String =
        raw.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.ROOT, "%.2f", it) }
            ?: "[COORDINATE REDACTED]"

    /** Keep the newest bytes, advancing to the next complete line where possible. */
    internal fun trimToMaxBytes(content: ByteArray, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || content.isEmpty()) return byteArrayOf()
        if (content.size <= maxBytes) return content
        var start = content.size - maxBytes
        while (start < content.size && content[start] != '\n'.code.toByte()) start++
        if (start < content.size) start++
        return if (start >= content.size) content.copyOfRange(content.size - maxBytes, content.size)
        else content.copyOfRange(start, content.size)
    }
}
