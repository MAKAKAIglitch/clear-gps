package org.cleargps.core

/** User feedback labels a heuristic event; it is not ground truth. */
enum class RiskFeedbackLabel { FALSE_ALARM, SUSPECTED_SPOOFING }

/** Deliberately excludes coordinates, radio identifiers and raw measurements. */
data class RiskFeedbackRecord(
    val timestampEpochMillis: Long,
    val label: RiskFeedbackLabel,
    val filterState: String,
    val riskScore: Int,
    val sourceCode: String,
    val motionScore: Int?,
    val crossSourceScore: Int?,
    val gnssSignalScore: Int?,
    val inRiskZone: Boolean,
    val temporalHoldFloorApplied: Boolean,
    val reasonCodes: List<String>
) {
    init {
        require(timestampEpochMillis > 0L)
        require(filterState in RiskFeedbackCodec.ALLOWED_STATES)
        require(riskScore in 0..100)
        require(sourceCode in RiskFeedbackCodec.ALLOWED_SOURCES)
        require(motionScore == null || motionScore in 0..100)
        require(crossSourceScore == null || crossSourceScore in 0..100)
        require(gnssSignalScore == null || gnssSignalScore in 0..100)
        require(reasonCodes.all { it in RiskFeedbackCodec.ALLOWED_REASON_CODES })
    }
}

object RiskFeedbackCodec {
    const val MAX_RECORDS = 200
    private const val STORAGE_VERSION = "1"

    internal val ALLOWED_STATES = setOf(
        "WAITING", "NORMAL", "SUSPECT", "ZONE_RISK", "CORRECTING", "NO_FALLBACK", "UNKNOWN"
    )
    internal val ALLOWED_SOURCES = setOf(
        "GPS", "NETWORK", "GNSS_SIGNAL_ONLY", "LOCAL_RADIO", "LOCAL_RADIO_STALE",
        "MANUAL_ANCHOR", "BEACONDB", "EXTERNAL_MOCK", "NONE", "UNKNOWN"
    )
    internal val ALLOWED_REASON_CODES = setOf(
        "MOTION_JUMP", "MOTION_SPEED", "NETWORK_DIVERGENCE", "GNSS_SIGNAL",
        "RISK_ZONE", "ALERT_HELD", "EXTERNAL_MOCK"
    )

    fun normalizeState(value: String): String = value.takeIf { it in ALLOWED_STATES } ?: "UNKNOWN"
    fun normalizeSource(value: String): String = value.takeIf { it in ALLOWED_SOURCES } ?: "UNKNOWN"

    /** Maps known detector text to fixed tags; arbitrary input is never copied into the record. */
    fun classifyReasons(reasons: List<String>, source: String, breakdown: RiskBreakdown?): List<String> {
        val codes = linkedSetOf<String>()
        if (normalizeSource(source) == "EXTERNAL_MOCK") codes += "EXTERNAL_MOCK"
        if (breakdown?.zoneFloorActive == true) codes += "RISK_ZONE"
        if (breakdown?.temporalHoldFloorApplied == true) codes += "ALERT_HELD"
        for (reason in reasons) {
            val text = reason.lowercase()
            when {
                "зоне риска" in text -> codes += "RISK_ZONE"
                "удерживается" in text -> codes += "ALERT_HELD"
                "скачок координат" in text || "неправдоподобный скачок" in text -> codes += "MOTION_JUMP"
                "скорост" in text || "движение" in text -> codes += "MOTION_SPEED"
                "сетевой позицией" in text -> codes += "NETWORK_DIVERGENCE"
                "gnss" in text || "спутник" in text || "сигнал" in text ||
                    "созвезд" in text || "c/n₀" in text || "agc" in text -> codes += "GNSS_SIGNAL"
            }
        }
        return codes.toList().sorted()
    }

    /** Compact private ledger protocol; invalid rows are ignored one by one. */
    fun encodeStorage(records: List<RiskFeedbackRecord>): String =
        records.takeLast(MAX_RECORDS).joinToString("\n") { record ->
            listOf(
                STORAGE_VERSION, record.timestampEpochMillis.toString(), record.label.name,
                record.filterState, record.riskScore.toString(), record.sourceCode,
                record.motionScore?.toString() ?: "-", record.crossSourceScore?.toString() ?: "-",
                record.gnssSignalScore?.toString() ?: "-", if (record.inRiskZone) "1" else "0",
                if (record.temporalHoldFloorApplied) "1" else "0",
                record.reasonCodes.distinct().sorted().joinToString(",")
            ).joinToString("|")
        }

    fun decodeStorage(payload: String): List<RiskFeedbackRecord> =
        payload.lineSequence().filter { it.isNotBlank() }.toList().takeLast(MAX_RECORDS)
            .mapNotNull(::decodeLine).takeLast(MAX_RECORDS)

    private fun decodeLine(line: String): RiskFeedbackRecord? {
        return try {
            val fields = line.split('|')
            if (fields.size != 12 || fields[0] != STORAGE_VERSION) return null
            val state = fields[3].takeIf { it in ALLOWED_STATES } ?: return null
            val source = fields[5].takeIf { it in ALLOWED_SOURCES } ?: return null
            if (fields[9] !in setOf("0", "1") || fields[10] !in setOf("0", "1")) return null
            val reasons = if (fields[11].isBlank()) emptyList() else fields[11].split(',')
            if (reasons.any { it !in ALLOWED_REASON_CODES }) return null
            RiskFeedbackRecord(
                timestampEpochMillis = fields[1].toLong(),
                label = RiskFeedbackLabel.valueOf(fields[2]),
                filterState = state,
                riskScore = fields[4].toInt(),
                sourceCode = source,
                motionScore = fields[6].toOptionalInt(),
                crossSourceScore = fields[7].toOptionalInt(),
                gnssSignalScore = fields[8].toOptionalInt(),
                inRiskZone = fields[9] == "1",
                temporalHoldFloorApplied = fields[10] == "1",
                reasonCodes = reasons.distinct().sorted()
            )
        } catch (_: NumberFormatException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun String.toOptionalInt(): Int? = if (this == "-") null else toInt()

    /** Export omits wall-clock timestamps and caps inter-record gaps to one day. */
    fun toExportJson(records: List<RiskFeedbackRecord>): String {
        val bounded = records.takeLast(MAX_RECORDS)
        val entries = bounded.mapIndexed { index, record ->
            val previous = bounded.getOrNull(index - 1)
            val gapSeconds = if (previous == null) 0L else
                ((record.timestampEpochMillis - previous.timestampEpochMillis) / 1_000L).coerceIn(0L, 86_400L)
            buildString {
                append("    {\n")
                append("      \"sequence\": ").append(index + 1).append(",\n")
                append("      \"secondsSincePrevious\": ").append(gapSeconds).append(",\n")
                append("      \"label\": ").append(jsonString(record.label.name)).append(",\n")
                append("      \"state\": ").append(jsonString(record.filterState)).append(",\n")
                append("      \"riskScore\": ").append(record.riskScore).append(",\n")
                append("      \"source\": ").append(jsonString(record.sourceCode)).append(",\n")
                append("      \"motionScore\": ").append(record.motionScore?.toString() ?: "null").append(",\n")
                append("      \"crossSourceScore\": ").append(record.crossSourceScore?.toString() ?: "null").append(",\n")
                append("      \"gnssSignalScore\": ").append(record.gnssSignalScore?.toString() ?: "null").append(",\n")
                append("      \"inRiskZone\": ").append(record.inRiskZone).append(",\n")
                append("      \"temporalHoldFloorApplied\": ").append(record.temporalHoldFloorApplied).append(",\n")
                append("      \"reasonCodes\": [")
                append(record.reasonCodes.distinct().sorted().joinToString(", ") { jsonString(it) })
                append("]\n    }")
            }
        }
        return buildString {
            append("{\n  \"schemaVersion\": 1,\n")
            append("  \"privacy\": {\n")
            append("    \"coordinatesIncluded\": false,\n")
            append("    \"radioIdentifiersIncluded\": false,\n")
            append("    \"rawMeasurementsIncluded\": false,\n")
            append("    \"absoluteTimestampsIncluded\": false\n  },\n")
            append("  \"entries\": [")
            if (entries.isNotEmpty()) append("\n").append(entries.joinToString(",\n")).append("\n  ")
            append("]\n}\n")
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
