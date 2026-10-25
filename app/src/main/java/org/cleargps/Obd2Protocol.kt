package org.cleargps

import java.util.Locale

/**
 * Standard OBD-II Mode 01 read-only telemetry.
 *
 * Requests are restricted to standard Mode 01 read PIDs: one capability bitmap and three
 * common live values. No arbitrary AT, service-mode, or ECU commands are exposed.
 */
data class Obd2Telemetry(
    val engineRpm: Int? = null,
    val vehicleSpeedKmh: Int? = null,
    val coolantTemperatureC: Int? = null,
    val updatedAt: Long = 0L,
    val parametersReceived: Int = 0
)

internal object Obd2Protocol {
    private const val POSITIVE_RESPONSE_MODE_01 = 0x41
    private const val HEX_DIGITS = "0123456789ABCDEF"

    // PID 00 is the standard capability bitmap for PIDs 01-20, not a mutable ECU command.
    // The remaining PIDs are optional readings; individual ECUs need not support all of them.
    private val readOnlyPids = setOf(0x00, 0x05, 0x0C, 0x0D)

    fun requestFor(pid: Int): String {
        require(pid in readOnlyPids) { "Only allowlisted Mode 01 read PIDs are permitted." }
        return "01" + pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
    }

    fun supportedPids(response: String): Set<Int>? {
        val compact = response.uppercase(Locale.ROOT).filter { it in HEX_DIGITS }
        val markerAt = compact.indexOf("4100")
        if (markerAt < 0) return null
        val dataStart = markerAt + 4
        val dataEnd = dataStart + 8
        if (dataEnd > compact.length) return null
        val bitmap = compact.substring(dataStart, dataEnd).toLongOrNull(16) ?: return null
        return (1..32).filterTo(linkedSetOf()) { pid ->
            (bitmap and (1L shl (32 - pid))) != 0L
        }
    }

    fun decode(pid: Int, response: String): Int? {
        require(pid in readOnlyPids) { "Only allowlisted Mode 01 read PIDs can be decoded." }
        val dataBytes = when (pid) {
            0x05, 0x0D -> 1
            0x0C -> 2
            else -> return null
        }
        // ELM-compatible adapters may echo the request, include spaces or CAN headers.
        // Removing non-hex characters handles all of those formats without parsing arbitrary
        // commands or trusting a response that doesn't contain the expected positive PID frame.
        val compact = response.uppercase(Locale.ROOT).filter { it in HEX_DIGITS }
        val marker = POSITIVE_RESPONSE_MODE_01.toString(16).uppercase(Locale.ROOT).padStart(2, '0') +
            pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
        val markerAt = compact.indexOf(marker)
        if (markerAt < 0) return null
        val dataStart = markerAt + marker.length
        val dataEnd = dataStart + dataBytes * 2
        if (dataEnd > compact.length) return null
        val bytes = compact.substring(dataStart, dataEnd).chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (bytes.size != dataBytes) return null

        return when (pid) {
            0x0C -> ((bytes[0] * 256 + bytes[1]) / 4.0).toInt()
            0x0D -> bytes[0]
            0x05 -> bytes[0] - 40
            else -> null
        }
    }

    fun telemetry(
        rpmResponse: String,
        speedResponse: String,
        coolantResponse: String,
        now: Long = System.currentTimeMillis()
    ): Obd2Telemetry {
        val rpm = decode(0x0C, rpmResponse)
        val speed = decode(0x0D, speedResponse)
        val coolant = decode(0x05, coolantResponse)
        return Obd2Telemetry(
            engineRpm = rpm,
            vehicleSpeedKmh = speed,
            coolantTemperatureC = coolant,
            updatedAt = now,
            parametersReceived = listOf(rpm, speed, coolant).count { it != null }
        )
    }
}