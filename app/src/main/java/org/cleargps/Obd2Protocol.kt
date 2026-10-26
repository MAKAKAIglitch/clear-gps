package org.cleargps

import java.util.Locale

/** Why a current vehicle-speed sample could not be obtained. */
enum class Obd2SpeedIssue {
    PID_UNSUPPORTED,
    NO_VALID_RESPONSE
}

/**
 * Position-relevant, standard OBD-II data only.
 * RPM, coolant temperature, arbitrary PIDs and write-capable services are intentionally absent.
 */
data class Obd2Telemetry(
    val vehicleSpeedKmh: Int? = null,
    val updatedAt: Long = 0L,
    val speedIssue: Obd2SpeedIssue? = null
)

internal object Obd2Protocol {
    const val MAX_PLAUSIBLE_SPEED_KMH = 250
    private const val POSITIVE_RESPONSE_MODE_01 = 0x41
    private const val HEX_DIGITS = "0123456789ABCDEF"

    // Capability pages are read-only metadata. Actual vehicle telemetry remains limited to PID 0D.
    // A page's final bitmap bit advertises the next page (00 -> 20 -> ... -> C0).
    val supportBitmapBases = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
    private val readOnlyRequestPids = supportBitmapBases.toSet() + 0x0D

    fun requestFor(pid: Int): String {
        require(pid in readOnlyRequestPids) { "Only allowlisted support bitmaps and vehicle-speed PID 0D are permitted." }
        return "01" + pid.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
    }

    /**
     * Parses the capability bitmap requested by [basePid]. The returned range includes its
     * continuation marker (basePid + 0x20) when the ECU advertises another bitmap page.
     */
    fun supportedPids(response: String, basePid: Int = 0x00): Set<Int>? {
        if (basePid !in supportBitmapBases) return null
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

    fun decode(pid: Int, response: String): Int? {
        require(pid == 0x0D) { "Only vehicle-speed PID 0D is decoded as telemetry." }

        // ELM-compatible adapters may echo requests or include spaces/CAN headers.
        // Accept only a positive response for PID 0D with a complete data byte.
        val compact = response.uppercase(Locale.ROOT).filter { it in HEX_DIGITS }
        val marker = POSITIVE_RESPONSE_MODE_01.toString(16).uppercase(Locale.ROOT).padStart(2, '0') + "0D"
        val markerAt = compact.indexOf(marker)
        if (markerAt < 0) return null
        val dataStart = markerAt + marker.length
        val dataEnd = dataStart + 2
        if (dataEnd > compact.length) return null
        val speed = compact.substring(dataStart, dataEnd).toIntOrNull(16) ?: return null
        return speed.takeIf { it in 0..MAX_PLAUSIBLE_SPEED_KMH }
    }

    fun telemetry(
        speedResponse: String,
        speedPidSupported: Boolean? = null,
        now: Long = System.currentTimeMillis()
    ): Obd2Telemetry {
        val speed = if (speedPidSupported == false) null else decode(0x0D, speedResponse)
        val issue = when {
            speed != null -> null
            speedPidSupported == false -> Obd2SpeedIssue.PID_UNSUPPORTED
            else -> Obd2SpeedIssue.NO_VALID_RESPONSE
        }
        return Obd2Telemetry(
            vehicleSpeedKmh = speed,
            updatedAt = now,
            speedIssue = issue
        )
    }
}
