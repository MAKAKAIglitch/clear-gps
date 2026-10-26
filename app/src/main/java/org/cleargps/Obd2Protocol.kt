package org.cleargps

import java.util.Locale

/** Why a current vehicle-speed sample could not be obtained. */
enum class Obd2SpeedIssue {
    PID_UNSUPPORTED,
    NO_VALID_RESPONSE,
    PID_DISABLED_FOR_SESSION
}

/** Why a supported/allowlisted PID did not produce a current value. */
enum class Obd2PidIssue {
    PID_UNSUPPORTED,
    NO_VALID_RESPONSE,
    PID_DISABLED_FOR_SESSION
}

/**
 * Per-connection circuit breaker for a PID with a supported/unknown capability.
 * Only completed responses that fail value validation count as PID failures. Transport exceptions
 * are handled by the connection layer because a timed-out byte stream may be out of sync.
 * Three consecutive bad responses disable that PID until a new connection creates a fresh breaker.
 */
internal class Obd2PidCircuitBreaker(private val maxConsecutiveFailures: Int = 3) {
    private val consecutiveFailures = mutableMapOf<Int, Int>()
    private val disabledPids = mutableSetOf<Int>()

    init {
        require(maxConsecutiveFailures in 1..10) { "Invalid PID circuit-breaker threshold." }
    }

    @Synchronized
    fun recordFailure(pid: Int): Boolean {
        require(pid in 0..0xFF) { "PID must be a byte value." }
        if (pid in disabledPids) return true
        val failures = (consecutiveFailures[pid] ?: 0) + 1
        consecutiveFailures[pid] = failures
        if (failures >= maxConsecutiveFailures) disabledPids += pid
        return pid in disabledPids
    }

    @Synchronized
    fun recordSuccess(pid: Int) {
        require(pid in 0..0xFF) { "PID must be a byte value." }
        if (pid !in disabledPids) consecutiveFailures.remove(pid)
    }

    @Synchronized
    fun isDisabled(pid: Int): Boolean {
        require(pid in 0..0xFF) { "PID must be a byte value." }
        return pid in disabledPids
    }

    @Synchronized
    fun consecutiveFailureCount(pid: Int): Int {
        require(pid in 0..0xFF) { "PID must be a byte value." }
        return consecutiveFailures[pid] ?: 0
    }

    @Synchronized
    fun reset() {
        consecutiveFailures.clear()
        disabledPids.clear()
    }
}

/**
 * Mode 01 telemetry exposed to the UI. Only vehicle speed is written to the shared state used by
 * the positioning service; the other values remain screen-local diagnostics.
 */
data class Obd2Telemetry(
    val vehicleSpeedKmh: Int? = null,
    val updatedAt: Long = 0L,
    val speedIssue: Obd2SpeedIssue? = null,
    val engineRpm: Int? = null,
    val throttlePercent: Int? = null,
    val engineLoadPercent: Int? = null,
    val manifoldPressureKpa: Int? = null,
    val coolantTemperatureCelsius: Int? = null,
    val barometricPressureKpa: Int? = null,
    val pidIssues: Map<Int, Obd2PidIssue> = emptyMap()
)

internal object Obd2Protocol {
    const val MAX_PLAUSIBLE_SPEED_KMH = 250
    private const val POSITIVE_RESPONSE_MODE_01 = 0x41
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val MAX_RESPONSE_CHARS = 2_048

    // Capability pages are read-only metadata. Only the explicitly listed standard telemetry
    // PIDs are queried; VIN/Mode 09 and arbitrary PIDs remain outside this first level.
    // A page's final bitmap bit advertises the next page (00 -> 20 -> ... -> C0).
    val supportBitmapBases = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
    val telemetryPids = listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x11, 0x33)
    private val readOnlyRequestPids = supportBitmapBases.toSet() + telemetryPids

    fun requestFor(pid: Int): String {
        require(pid in readOnlyRequestPids) {
            "Only allowlisted support bitmaps and standard telemetry PIDs are permitted."
        }
        return "01" + pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
    }

    fun pidLabel(pid: Int): String = when (pid) {
        0x04 -> "нагрузка PID 04"
        0x05 -> "температура PID 05"
        0x0B -> "MAP PID 0B"
        0x0C -> "обороты PID 0C"
        0x0D -> "скорость PID 0D"
        0x11 -> "дроссель PID 11"
        0x33 -> "давление PID 33"
        else -> "PID " + pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
    }

    /**
     * Parses the capability bitmap requested by [basePid]. The returned range includes its
     * continuation marker (basePid + 0x20) when the ECU advertises another bitmap page.
     */
    fun supportedPids(response: String, basePid: Int = 0x00): Set<Int>? {
        if (basePid !in supportBitmapBases || response.length > MAX_RESPONSE_CHARS) return null
        val marker = "41" + basePid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
        val compact = response.uppercase(Locale.ROOT).filter { it in HEX_DIGITS }
        val markerAt = compact.indexOf(marker)
        if (markerAt < 0) return null
        val dataStart = markerAt + marker.length
        val dataEnd = dataStart + 8
        if (dataEnd > compact.length) return null
        val bitmap = compact.substring(dataStart, dataEnd).toLongOrNull(16) ?: return null
        return (1..32).filterTo(linkedSetOf<Int>()) { offset ->
            (bitmap and (1L shl (32 - offset))) != 0L
        }.mapTo(linkedSetOf()) { basePid + it }
    }

    /**
     * Decodes only fixed-size SAE J1979 Mode 01 responses from the allowlist.
     * Returned values are integers in the unit shown by the corresponding telemetry field.
     */
    fun decode(pid: Int, response: String): Int? {
        require(pid in telemetryPids) { "Only allowlisted standard telemetry PIDs can be decoded." }
        if (response.length > MAX_RESPONSE_CHARS) return null

        val compact = response.uppercase(Locale.ROOT).filter { it in HEX_DIGITS }
        val marker = POSITIVE_RESPONSE_MODE_01.toString(16).uppercase(Locale.ROOT).padStart(2, '0') +
            pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
        val markerAt = compact.indexOf(marker)
        if (markerAt < 0) return null

        val dataStart = markerAt + marker.length
        val dataChars = if (pid == 0x0C) 4 else 2
        val dataEnd = dataStart + dataChars
        if (dataEnd > compact.length) return null
        val a = compact.substring(dataStart, dataStart + 2).toIntOrNull(16) ?: return null

        val value = when (pid) {
            0x04 -> a * 100 / 255
            0x05 -> a - 40
            0x0B -> a
            0x0C -> {
                val twoBytes = compact.substring(dataStart, dataEnd).toLongOrNull(16) ?: return null
                (twoBytes / 4L).toInt()
            }
            0x0D -> a
            0x11 -> a * 100 / 255
            0x33 -> a
            else -> return null
        }

        return when (pid) {
            0x04, 0x11 -> value.takeIf { it in 0..100 }
            0x05 -> value.takeIf { it in -40..215 }
            0x0B, 0x33 -> value.takeIf { it in 0..255 }
            0x0C -> value.takeIf { it in 0..16_383 }
            0x0D -> value.takeIf { it in 0..MAX_PLAUSIBLE_SPEED_KMH }
            else -> null
        }
    }

    fun telemetry(
        responses: Map<Int, String>,
        pidIssues: Map<Int, Obd2PidIssue> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): Obd2Telemetry {
        val values = responses.mapNotNull { (pid, response) ->
            if (pid !in telemetryPids) return@mapNotNull null
            decode(pid, response)?.let { pid to it }
        }.toMap()
        val speedIssue = when (pidIssues[0x0D]) {
            Obd2PidIssue.PID_UNSUPPORTED -> Obd2SpeedIssue.PID_UNSUPPORTED
            Obd2PidIssue.PID_DISABLED_FOR_SESSION -> Obd2SpeedIssue.PID_DISABLED_FOR_SESSION
            Obd2PidIssue.NO_VALID_RESPONSE -> Obd2SpeedIssue.NO_VALID_RESPONSE
            null -> if (values[0x0D] == null) Obd2SpeedIssue.NO_VALID_RESPONSE else null
        }
        return Obd2Telemetry(
            vehicleSpeedKmh = values[0x0D],
            updatedAt = now,
            speedIssue = speedIssue,
            engineRpm = values[0x0C],
            throttlePercent = values[0x11],
            engineLoadPercent = values[0x04],
            manifoldPressureKpa = values[0x0B],
            coolantTemperatureCelsius = values[0x05],
            barometricPressureKpa = values[0x33],
            pidIssues = pidIssues.toMap()
        )
    }

    /** Compatibility helper for callers/tests that only have the speed response. */
    fun telemetry(
        speedResponse: String,
        speedPidSupported: Boolean? = null,
        now: Long = System.currentTimeMillis(),
        pidDisabledForSession: Boolean = false
    ): Obd2Telemetry {
        val issue = when {
            pidDisabledForSession -> Obd2PidIssue.PID_DISABLED_FOR_SESSION
            speedPidSupported == false -> Obd2PidIssue.PID_UNSUPPORTED
            decode(0x0D, speedResponse) == null -> Obd2PidIssue.NO_VALID_RESPONSE
            else -> null
        }
        val responses = if (issue == null) mapOf(0x0D to speedResponse) else emptyMap()
        return telemetry(responses, issue?.let { mapOf(0x0D to it) } ?: emptyMap(), now)
    }
}
