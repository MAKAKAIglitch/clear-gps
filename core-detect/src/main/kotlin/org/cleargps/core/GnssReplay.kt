package org.cleargps.core

import kotlin.math.max

/**
 * Versioned replay format. Position is relative East/North metres from the first GPS fix;
 * timestamps are elapsed offsets from recording start. No absolute coordinates or wall time.
 */
enum class GnssReplayPositionSource { GPS, NETWORK }

data class GnssReplaySatellite(
    val constellationType: Int, val svid: Int, val cn0DbHz: Double?,
    val elevationDegrees: Double?, val azimuthDegrees: Double?, val usedInFix: Boolean,
    val carrierFrequencyHz: Double?, val basebandCn0DbHz: Double?
) {
    init {
        require(constellationType in 0..10 && svid in 1..4096)
        require(cn0DbHz == null || cn0DbHz.isFinite() && cn0DbHz in 0.0..100.0)
        require(elevationDegrees == null || elevationDegrees.isFinite() && elevationDegrees in -90.0..90.0)
        require(azimuthDegrees == null || azimuthDegrees.isFinite() && azimuthDegrees in 0.0..360.0)
        require(carrierFrequencyHz == null || carrierFrequencyHz.isFinite() && carrierFrequencyHz in 1.0..100_000_000_000.0)
        require(basebandCn0DbHz == null || basebandCn0DbHz.isFinite() && basebandCn0DbHz in 0.0..100.0)
    }
}

data class GnssReplayMeasurement(
    val constellationType: Int, val svid: Int, val cn0DbHz: Double?,
    val carrierFrequencyHz: Double?, val agcDb: Double?,
    val pseudorangeRateMetersPerSecond: Double?,
    val pseudorangeRateUncertaintyMetersPerSecond: Double?,
    val receivedSvTimeNanos: Long?, val receivedSvTimeUncertaintyNanos: Long?, val state: Int
) {
    init {
        require(constellationType in 0..10 && svid in 1..4096)
        require(cn0DbHz == null || cn0DbHz.isFinite() && cn0DbHz in 0.0..100.0)
        require(carrierFrequencyHz == null || carrierFrequencyHz.isFinite() && carrierFrequencyHz in 1.0..100_000_000_000.0)
        require(agcDb == null || agcDb.isFinite() && agcDb in -200.0..200.0)
        require(pseudorangeRateMetersPerSecond == null ||
            pseudorangeRateMetersPerSecond.isFinite() && pseudorangeRateMetersPerSecond in -100_000.0..100_000.0)
        require(pseudorangeRateUncertaintyMetersPerSecond == null ||
            pseudorangeRateUncertaintyMetersPerSecond.isFinite() && pseudorangeRateUncertaintyMetersPerSecond in 0.0..100_000.0)
        require(receivedSvTimeNanos == null || receivedSvTimeNanos in 0L..1_000_000_000_000_000L)
        require(receivedSvTimeUncertaintyNanos == null || receivedSvTimeUncertaintyNanos in 0L..1_000_000_000_000_000L)
        require(state >= 0)
    }
}

enum class GnssReplaySensorType { ACCELEROMETER, GYROSCOPE, MAGNETOMETER, ORIENTATION_DEGREES }

enum class GnssReplayRadioType { WIFI, CELL, BLE }

data class GnssReplayRadioObservation(
    val opaqueId: Int, val type: GnssReplayRadioType, val rssiDbm: Int,
    val frequencyMhz: Int?, val ageMillis: Long?
) {
    init {
        require(opaqueId in 1..GnssReplayCodec.MAX_RADIO_IDENTITIES)
        require(rssiDbm in -160..0)
        require(frequencyMhz == null || frequencyMhz in 1..100_000)
        require(ageMillis == null || ageMillis in 0L..(GnssReplayCodec.MAX_DURATION_NANOS / 1_000_000L))
    }
}

sealed class GnssReplayEvent {
    abstract val offsetNanos: Long
    data class Signal(override val offsetNanos: Long, val snapshot: GnssSignalSnapshot,
                      val satellites: List<GnssReplaySatellite>) : GnssReplayEvent()
    data class Rf(override val offsetNanos: Long, val snapshot: GnssRfSnapshot,
                  val measurements: List<GnssReplayMeasurement>) : GnssReplayEvent()
    data class Sensor(
        override val offsetNanos: Long,
        val type: GnssReplaySensorType,
        val calibrated: Boolean,
        val x: Double, val y: Double, val z: Double,
        val biasX: Double?, val biasY: Double?, val biasZ: Double?
    ) : GnssReplayEvent() {
        init {
            require(offsetNanos in 0L..GnssReplayCodec.MAX_DURATION_NANOS)
            require(x.isFinite() && y.isFinite() && z.isFinite())
            if (type == GnssReplaySensorType.ORIENTATION_DEGREES) {
                require(x in 0.0..360.0 && y in -180.0..180.0 && z in -180.0..180.0)
            } else require(kotlin.math.abs(x) <= 100_000.0 && kotlin.math.abs(y) <= 100_000.0 && kotlin.math.abs(z) <= 100_000.0)
            require(biasX == null || biasX.isFinite() && kotlin.math.abs(biasX) <= 100_000.0)
            require(biasY == null || biasY.isFinite() && kotlin.math.abs(biasY) <= 100_000.0)
            require(biasZ == null || biasZ.isFinite() && kotlin.math.abs(biasZ) <= 100_000.0)
        }
    }
    data class RadioSnapshot(
        override val offsetNanos: Long, val observations: List<GnssReplayRadioObservation>
    ) : GnssReplayEvent() {
        init {
            require(offsetNanos in 0L..GnssReplayCodec.MAX_DURATION_NANOS)
            require(observations.size in 0..GnssReplayCodec.MAX_RADIO_OBSERVATIONS_PER_FRAME)
            require(observations.map { it.opaqueId }.distinct().size == observations.size)
        }
    }
    data class Position(
        override val offsetNanos: Long, val source: GnssReplayPositionSource,
        val eastMeters: Double, val northMeters: Double, val accuracyMeters: Double,
        val speedMetersPerSecond: Double?, val speedAccuracyMetersPerSecond: Double?,
        val bearingDegrees: Double?, val bearingAccuracyDegrees: Double?,
        val inRiskZone: Boolean, val recordedRiskScore: Int,
        val recordedState: String, val recordedDecisionSource: String
    ) : GnssReplayEvent() {
        init {
            require(offsetNanos in 0L..GnssReplayCodec.MAX_DURATION_NANOS)
            require(eastMeters.isFinite() && eastMeters in -10_000_000.0..10_000_000.0)
            require(northMeters.isFinite() && northMeters in -10_000_000.0..10_000_000.0)
            require(accuracyMeters.isFinite() && accuracyMeters in 0.0..10_000_000.0)
            require(speedMetersPerSecond == null || speedMetersPerSecond.isFinite() && speedMetersPerSecond in 0.0..100_000.0)
            require(speedAccuracyMetersPerSecond == null || speedAccuracyMetersPerSecond.isFinite() && speedAccuracyMetersPerSecond in 0.0..100_000.0)
            require(bearingDegrees == null || bearingDegrees.isFinite() && bearingDegrees in 0.0..360.0)
            require(bearingAccuracyDegrees == null || bearingAccuracyDegrees.isFinite() && bearingAccuracyDegrees in 0.0..360.0)
            require(recordedRiskScore in 0..100)
            require(recordedState in ALLOWED_STATES)
            require(recordedDecisionSource in ALLOWED_DECISION_SOURCES)
        }
    }
    companion object {
        val ALLOWED_STATES = setOf("WAITING", "NORMAL", "SUSPECT", "ZONE_RISK", "CORRECTING", "NO_FALLBACK")
        val ALLOWED_DECISION_SOURCES = setOf(
            "GPS", "NETWORK", "NONE", "LAST_TRUSTED", "LOCAL_RADIO", "LOCAL_RADIO_STALE",
            "MANUAL_ANCHOR", "BEACONDB", "GNSS_SIGNAL_ONLY", "EXTERNAL_MOCK"
        )
    }
}

data class GnssReplayDecodeResult(
    val events: List<GnssReplayEvent>, val skippedLines: Int, val hasFooter: Boolean, val error: String? = null
)

data class GnssReplaySummary(
    val gpsFixes: Int, val networkFixes: Int, val signalFrames: Int, val rfFrames: Int,
    val rawSatelliteRecords: Int, val rawMeasurementRecords: Int, val evaluatedFixes: Int,
    val peakRiskScore: Int, val confirmedAssessments: Int, val skippedLines: Int,
    val hasFooter: Boolean, val error: String? = null, val sensorSamples: Int = 0,
    val radioSnapshots: Int = 0, val radioObservations: Int = 0
)

object GnssReplayCodec {
    const val LEGACY_FORMAT_HEADER = "CLEAR_GPS_REPLAY|1|ENU_RELATIVE|OFFSET_ONLY"
    const val FORMAT_HEADER = "CLEAR_GPS_REPLAY|2|ENU_RELATIVE|OFFSET_ONLY|RADIO_OPAQUE_IDS"
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_LINES = 50_000
    const val MAX_EVENTS = 25_000
    const val MAX_DURATION_NANOS = 604_800_000_000_000L
    const val MAX_RADIO_IDENTITIES = 2_048
    const val MAX_RADIO_OBSERVATIONS_PER_FRAME = 96

    fun isSupportedHeader(header: String?): Boolean =
        header?.removePrefix("\uFEFF") == FORMAT_HEADER ||
            header?.removePrefix("\uFEFF") == LEGACY_FORMAT_HEADER

    fun encodeHeader() = FORMAT_HEADER
    fun encodeFooter(offsetNanos: Long, eventCount: Int, reason: String): String {
        require(offsetNanos in 0L..MAX_DURATION_NANOS && eventCount in 0..MAX_EVENTS)
        require(reason in setOf("USER_STOP", "SERVICE_STOP", "SIZE_LIMIT", "INTERRUPTED"))
        return "END|$offsetNanos|$eventCount|$reason"
    }

    fun encode(event: GnssReplayEvent): List<String> {
        require(event.offsetNanos in 0L..MAX_DURATION_NANOS)
        return when (event) {
            is GnssReplayEvent.Signal -> {
                val s = event.snapshot
                require(s.satellitesVisible in 0..512 && s.satellitesUsedInFix in 0..s.satellitesVisible)
                require(s.constellationCount in 0..10 && s.strongSignalCount in 0..512)
                require(s.meanCn0DbHz == null || s.meanCn0DbHz.isFinite() && s.meanCn0DbHz in 0.0..100.0)
                require(s.cn0SpreadDb == null || s.cn0SpreadDb.isFinite() && s.cn0SpreadDb in 0.0..100.0)
                val lines = mutableListOf(listOf(
                    "S", event.offsetNanos, s.satellitesVisible, s.satellitesUsedInFix,
                    d(s.meanCn0DbHz), d(s.cn0SpreadDb), s.constellationCount, s.strongSignalCount
                ).joinToString("|"))
                event.satellites.take(512).forEach { sat -> lines += listOf(
                    "V", event.offsetNanos, sat.constellationType, sat.svid, d(sat.cn0DbHz),
                    d(sat.elevationDegrees), d(sat.azimuthDegrees), b(sat.usedInFix),
                    d(sat.carrierFrequencyHz), d(sat.basebandCn0DbHz)
                ).joinToString("|") }
                lines
            }
            is GnssReplayEvent.Rf -> {
                val s = event.snapshot
                require(s.signalCount in 0..512 && s.constellationCount in 0..10 && s.agcSampleCount in 0..512)
                require(s.meanCn0DbHz == null || s.meanCn0DbHz.isFinite() && s.meanCn0DbHz in 0.0..100.0)
                require(s.meanAgcDb == null || s.meanAgcDb.isFinite() && s.meanAgcDb in -200.0..200.0)
                val lines = mutableListOf(listOf(
                    "R", event.offsetNanos, s.signalCount, s.constellationCount,
                    d(s.meanCn0DbHz), d(s.meanAgcDb), s.agcSampleCount
                ).joinToString("|"))
                event.measurements.take(512).forEach { m -> lines += listOf(
                    "M", event.offsetNanos, m.constellationType, m.svid, d(m.cn0DbHz),
                    d(m.carrierFrequencyHz), d(m.agcDb), d(m.pseudorangeRateMetersPerSecond),
                    d(m.pseudorangeRateUncertaintyMetersPerSecond),
                    m.receivedSvTimeNanos?.toString() ?: "-",
                    m.receivedSvTimeUncertaintyNanos?.toString() ?: "-", m.state
                ).joinToString("|") }
                lines
            }
            is GnssReplayEvent.Sensor -> listOf(listOf(
                "I", event.offsetNanos, event.type.name, b(event.calibrated), d(event.x), d(event.y), d(event.z),
                d(event.biasX), d(event.biasY), d(event.biasZ)
            ).joinToString("|"))
            is GnssReplayEvent.RadioSnapshot -> {
                require(event.observations.size in 1..MAX_RADIO_OBSERVATIONS_PER_FRAME)
                val lines = mutableListOf(listOf("Q", event.offsetNanos, event.observations.size).joinToString("|"))
                event.observations.forEach { o ->
                    lines += listOf("O", event.offsetNanos, o.opaqueId, o.type.name, o.rssiDbm,
                        o.frequencyMhz?.toString() ?: "-", o.ageMillis?.toString() ?: "-").joinToString("|")
                }
                lines
            }
            is GnssReplayEvent.Position -> listOf(listOf(
                "P", event.offsetNanos, event.source.name, d(event.eastMeters), d(event.northMeters),
                d(event.accuracyMeters), d(event.speedMetersPerSecond), d(event.speedAccuracyMetersPerSecond),
                d(event.bearingDegrees), d(event.bearingAccuracyDegrees), b(event.inRiskZone),
                event.recordedRiskScore, event.recordedState, event.recordedDecisionSource
            ).joinToString("|"))
        }
    }

    fun encodeDocument(events: List<GnssReplayEvent>, stopReason: String = "USER_STOP"): String {
        require(events.size <= MAX_EVENTS)
        val lines = mutableListOf(encodeHeader())
        var last = -1L
        events.forEach { event ->
            require(event.offsetNanos >= last) { "Replay events must be chronological." }
            lines += encode(event)
            last = event.offsetNanos
            require(lines.size <= MAX_LINES)
        }
        lines += encodeFooter(last.coerceAtLeast(0L), events.size, stopReason)
        val text = lines.joinToString("\n", postfix = "\n")
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Replay is larger than 2 MiB." }
        return text
    }

    fun decode(text: String): GnssReplayDecodeResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
            return GnssReplayDecodeResult(emptyList(), 0, false, "Replay exceeds the 2 MiB size limit.")
        val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.size > MAX_LINES) return GnssReplayDecodeResult(emptyList(), 0, false, "Replay has too many lines.")
        val header = lines.firstOrNull()?.removePrefix("\uFEFF")
        if (header != FORMAT_HEADER && header != LEGACY_FORMAT_HEADER)
            return GnssReplayDecodeResult(emptyList(), 0, false, "Unsupported or missing replay header.")
        val isV2 = header == FORMAT_HEADER
        val events = mutableListOf<GnssReplayEvent>()
        var skipped = 0
        var footer = false
        var closed = false
        var lastOffset = -1L
        var pendingRadioRows = 0
        var expectedRadioRows = 0
        for (line in lines.drop(1)) {
            if (closed) { skipped++; continue }
            val fields = line.split('|')
            val rowType = fields.firstOrNull()
            if (pendingRadioRows > 0 && rowType != "O") {
                val incomplete = events.lastOrNull() as? GnssReplayEvent.RadioSnapshot
                if (incomplete != null && incomplete.observations.size < expectedRadioRows) {
                    skipped += 1 + incomplete.observations.size
                    events.removeAt(events.lastIndex)
                }
                pendingRadioRows = 0
                expectedRadioRows = 0
            }
            try {
                when (fields.firstOrNull()) {
                    "END" -> {
                        require(fields.size == 4)
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        fields.at(2).intIn(0, MAX_EVENTS)
                        require(fields.at(3) in setOf("USER_STOP", "SERVICE_STOP", "SIZE_LIMIT", "INTERRUPTED"))
                        require(offset >= lastOffset.coerceAtLeast(0L))
                        footer = true
                        closed = true
                    }
                    "S" -> {
                        if (events.size >= MAX_EVENTS) { skipped++; continue }
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(offset >= lastOffset)
                        val visible = fields.at(2).intIn(0, 512)
                        val used = fields.at(3).intIn(0, visible)
                        val mean = fields.at(4).doubleOrNull(0.0, 100.0)
                        val spread = fields.at(5).doubleOrNull(0.0, 100.0)
                        val constellations = fields.at(6).intIn(0, 10)
                        val strong = fields.at(7).intIn(0, 512)
                        events += GnssReplayEvent.Signal(
                            offset, GnssSignalSnapshot(offset + 1, visible, used, mean, spread, constellations, strong), emptyList()
                        )
                        lastOffset = offset
                    }
                    "V" -> {
                        val parent = events.lastOrNull() as? GnssReplayEvent.Signal
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(parent != null && parent.offsetNanos == offset)
                        val sat = GnssReplaySatellite(
                            fields.at(2).intIn(0, 10), fields.at(3).intIn(1, 4096),
                            fields.at(4).doubleOrNull(0.0, 100.0),
                            fields.at(5).doubleOrNull(-90.0, 90.0),
                            fields.at(6).doubleOrNull(0.0, 360.0), fields.at(7).boolValue(),
                            fields.at(8).doubleOrNull(1.0, 100_000_000_000.0),
                            fields.at(9).doubleOrNull(0.0, 100.0)
                        )
                        events[events.lastIndex] = parent!!.copy(satellites = parent.satellites + sat)
                    }
                    "R" -> {
                        if (events.size >= MAX_EVENTS) { skipped++; continue }
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(offset >= lastOffset)
                        val signalCount = fields.at(2).intIn(0, 512)
                        val constellations = fields.at(3).intIn(0, 10)
                        val cn0 = fields.at(4).doubleOrNull(0.0, 100.0)
                        val agc = fields.at(5).doubleOrNull(-200.0, 200.0)
                        val agcCount = fields.at(6).intIn(0, 512)
                        events += GnssReplayEvent.Rf(offset, GnssRfSnapshot(offset + 1, signalCount, constellations, cn0, agc, agcCount), emptyList())
                        lastOffset = offset
                    }
                    "M" -> {
                        val parent = events.lastOrNull() as? GnssReplayEvent.Rf
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(parent != null && parent.offsetNanos == offset)
                        val m = GnssReplayMeasurement(
                            fields.at(2).intIn(0, 10), fields.at(3).intIn(1, 4096),
                            fields.at(4).doubleOrNull(0.0, 100.0),
                            fields.at(5).doubleOrNull(1.0, 100_000_000_000.0),
                            fields.at(6).doubleOrNull(-200.0, 200.0),
                            fields.at(7).doubleOrNull(-100_000.0, 100_000.0),
                            fields.at(8).doubleOrNull(0.0, 100_000.0),
                            fields.at(9).longOrNull(0L, 1_000_000_000_000_000L),
                            fields.at(10).longOrNull(0L, 1_000_000_000_000_000L),
                            fields.at(11).intIn(0, Int.MAX_VALUE)
                        )
                        events[events.lastIndex] = parent!!.copy(measurements = parent.measurements + m)
                    }
                    "I" -> {
                        if (events.size >= MAX_EVENTS) { skipped++; continue }
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(offset >= lastOffset)
                        events += GnssReplayEvent.Sensor(offset, GnssReplaySensorType.valueOf(fields.at(2)), fields.at(3).boolValue(),
                            fields.at(4).requiredDouble(-100_000.0, 100_000.0), fields.at(5).requiredDouble(-100_000.0, 100_000.0),
                            fields.at(6).requiredDouble(-100_000.0, 100_000.0), fields.at(7).doubleOrNull(-100_000.0, 100_000.0),
                            fields.at(8).doubleOrNull(-100_000.0, 100_000.0), fields.at(9).doubleOrNull(-100_000.0, 100_000.0))
                        lastOffset = offset
                    }
                    "Q" -> {
                        require(isV2)
                        if (events.size >= MAX_EVENTS) { skipped++; continue }
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(offset >= lastOffset)
                        val count = fields.at(2).intIn(1, MAX_RADIO_OBSERVATIONS_PER_FRAME)
                        events += GnssReplayEvent.RadioSnapshot(offset, emptyList())
                        pendingRadioRows = count
                        expectedRadioRows = count
                        lastOffset = offset
                    }
                    "O" -> {
                        require(isV2)
                        val parent = events.lastOrNull() as? GnssReplayEvent.RadioSnapshot
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(parent != null && parent.offsetNanos == offset && pendingRadioRows > 0)
                        require(parent.observations.size < expectedRadioRows)
                        val observation = GnssReplayRadioObservation(
                            fields.at(2).intIn(1, MAX_RADIO_IDENTITIES), GnssReplayRadioType.valueOf(fields.at(3)),
                            fields.at(4).intIn(-160, 0), fields.at(5).takeIf { it != "-" }?.intIn(1, 100_000),
                            fields.at(6).takeIf { it != "-" }?.longIn(0L, MAX_DURATION_NANOS / 1_000_000L)
                        )
                        events[events.lastIndex] = parent!!.copy(observations = parent.observations + observation)
                        pendingRadioRows--
                    }
                    "P" -> {
                        if (events.size >= MAX_EVENTS) { skipped++; continue }
                        val offset = fields.at(1).longIn(0L, MAX_DURATION_NANOS)
                        require(offset >= lastOffset)
                        val position = GnssReplayEvent.Position(
                            offset, GnssReplayPositionSource.valueOf(fields.at(2)),
                            fields.at(3).requiredDouble(-10_000_000.0, 10_000_000.0),
                            fields.at(4).requiredDouble(-10_000_000.0, 10_000_000.0),
                            fields.at(5).requiredDouble(0.0, 10_000_000.0),
                            fields.at(6).doubleOrNull(0.0, 100_000.0),
                            fields.at(7).doubleOrNull(0.0, 100_000.0),
                            fields.at(8).doubleOrNull(0.0, 360.0),
                            fields.at(9).doubleOrNull(0.0, 360.0),
                            fields.at(10).boolValue(), fields.at(11).intIn(0, 100),
                            fields.at(12).takeIf { it in GnssReplayEvent.ALLOWED_STATES }
                                ?: throw IllegalArgumentException("Invalid state."),
                            fields.at(13).takeIf { it in GnssReplayEvent.ALLOWED_DECISION_SOURCES }
                                ?: throw IllegalArgumentException("Invalid source.")
                        )
                        events += position
                        lastOffset = offset
                    }
                    else -> skipped++
                }
            } catch (_: RuntimeException) {
                skipped++
            }
        }
        if (pendingRadioRows > 0) {
            val incomplete = events.lastOrNull() as? GnssReplayEvent.RadioSnapshot
            if (incomplete != null && incomplete.observations.size < expectedRadioRows) {
                skipped += 1 + incomplete.observations.size
                events.removeAt(events.lastIndex)
            }
        }
        return GnssReplayDecodeResult(events, skipped, footer)
    }

    private fun List<String>.at(index: Int): String {
        require(index in indices)
        return this[index]
    }
    private fun String.intIn(min: Int, max: Int): Int =
        (toIntOrNull() ?: throw IllegalArgumentException("Invalid integer.")).also { require(it in min..max) }
    private fun String.longIn(min: Long, max: Long): Long =
        (toLongOrNull() ?: throw IllegalArgumentException("Invalid long.")).also { require(it in min..max) }
    private fun String.doubleOrNull(min: Double, max: Double): Double? =
        if (this == "-") null else requiredDouble(min, max)
    private fun String.requiredDouble(min: Double, max: Double): Double =
        (toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid number."))
            .also { require(it in min..max) }
    private fun String.longOrNull(min: Long, max: Long): Long? = if (this == "-") null else longIn(min, max)
    private fun String.boolValue(): Boolean = when (this) {
        "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("Invalid boolean.")
    }
    private fun d(v: Double?): String = v?.takeIf { it.isFinite() }?.toString() ?: "-"
    private fun b(v: Boolean) = if (v) "1" else "0"
}

class GnssReplayRunner {
    fun replay(decoded: GnssReplayDecodeResult): GnssReplaySummary {
        if (decoded.error != null) return GnssReplaySummary(0,0,0,0,0,0,0,0,0,decoded.skippedLines,decoded.hasFooter,decoded.error)
        val detector = GnssSpoofingDetector()
        var gps: PositionFix? = null
        var network: PositionFix? = null
        var inZone = false
        var gpsFixes=0; var networkFixes=0; var signals=0; var rf=0
        var satellites=0; var measurements=0; var evaluated=0; var peak=0; var confirmed=0; var sensorSamples=0
        var radioSnapshots=0; var radioObservations=0
        for (event in decoded.events) when (event) {
            is GnssReplayEvent.Signal -> {
                detector.observeGnssSignal(event.snapshot)
                signals++
                satellites += event.satellites.size
            }
            is GnssReplayEvent.Rf -> {
                detector.observeGnssMeasurements(event.snapshot)
                rf++
                measurements += event.measurements.size
            }
            is GnssReplayEvent.Sensor -> { sensorSamples++ }
            is GnssReplayEvent.RadioSnapshot -> {
                radioSnapshots++
                radioObservations += event.observations.size
            }
            is GnssReplayEvent.Position -> {
                val t = event.offsetNanos + 1L
                val position = PositionFix(
                    provider = if (event.source == GnssReplayPositionSource.GPS) "gps" else "network",
                    latitude = event.northMeters / METERS_PER_DEGREE_EQUATOR,
                    longitude = event.eastMeters / METERS_PER_DEGREE_EQUATOR,
                    accuracyMeters = event.accuracyMeters, elapsedRealtimeNanos = t,
                    speedMetersPerSecond = event.speedMetersPerSecond,
                    speedAccuracyMetersPerSecond = event.speedAccuracyMetersPerSecond,
                    bearingDegrees = event.bearingDegrees, bearingAccuracyDegrees = event.bearingAccuracyDegrees
                )
                if (event.source == GnssReplayPositionSource.GPS) {
                    gps = position
                    inZone = event.inRiskZone
                    gpsFixes++
                } else {
                    network = position
                    networkFixes++
                }
                val currentGps = gps ?: continue
                val assessment = detector.assess(
                    currentGps, network, inZone, t, advanceState = event.source == GnssReplayPositionSource.GPS
                )
                if (event.source == GnssReplayPositionSource.GPS) evaluated++
                peak = max(peak, assessment.score)
                if (assessment.confirmed) confirmed++
            }
        }
        return GnssReplaySummary(gpsFixes,networkFixes,signals,rf,satellites,measurements,evaluated,peak,confirmed,
            decoded.skippedLines,decoded.hasFooter,sensorSamples=sensorSamples,
            radioSnapshots=radioSnapshots,radioObservations=radioObservations)
    }
    companion object { private const val METERS_PER_DEGREE_EQUATOR = 111_194.9266 }
}
