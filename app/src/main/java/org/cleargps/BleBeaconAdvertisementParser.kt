package org.cleargps

import java.util.Locale

/**
 * Decoded BLE advertisement frames. Only StableIdentity is allowed to become a
 * persistent radio point or a position-fingerprint key.
 */
internal sealed interface BleBeaconFrame {
    data class StableIdentity(
        val protocol: String,
        val identifier: String,
        val calibratedTxPowerDbm: Int?
    ) : BleBeaconFrame

    data class EddystoneUrl(
        val url: String,
        val calibratedTxPowerDbm: Int
    ) : BleBeaconFrame

    data class EddystoneTelemetry(
        val batteryMillivolts: Int,
        val temperatureCelsius: Double?,
        val advertisementCount: Long,
        val uptimeSeconds: Long
    ) : BleBeaconFrame

    data class EddystoneEphemeralId(
        val ephemeralId: String,
        val calibratedTxPowerDbm: Int
    ) : BleBeaconFrame

    data class RuuviTelemetry(
        val identifier: String?,
        val temperatureCelsius: Double?,
        val humidityPercent: Double?,
        val pressurePascals: Int?,
        val accelerationXG: Double?,
        val accelerationYG: Double?,
        val accelerationZG: Double?,
        val batteryMillivolts: Int?,
        val txPowerDbm: Int?,
        val movementCounter: Int?,
        val measurementSequence: Int?
    ) : BleBeaconFrame
}

/**
 * Protocol parser for the byte arrays exposed by Android ScanRecord:
 * manufacturerData excludes the two-byte Bluetooth company identifier;
 * serviceData excludes the two-byte Service Data UUID.
 *
 * No Bluetooth address is used to construct protocol identities.
 */
internal object BleBeaconAdvertisementParser {
    const val APPLE_COMPANY_ID = 0x004C
    const val RUUVI_COMPANY_ID = 0x0499
    const val EDDYSTONE_SERVICE_UUID = "0000feaa-0000-1000-8000-00805f9b34fb"

    fun parse(
        manufacturerData: Map<Int, ByteArray>,
        serviceData: Map<String, ByteArray>
    ): List<BleBeaconFrame> {
        val frames = ArrayList<BleBeaconFrame>()

        manufacturerData.forEach { (companyId, payload) ->
            if (companyId !in 0..0xFFFF || payload.isEmpty()) return@forEach

            if (companyId == APPLE_COMPANY_ID) {
                parseIBeacon(payload)?.let(frames::add)
            }

            if (companyId == RUUVI_COMPANY_ID) {
                parseRuuviRawV2(payload)?.let(frames::add)
            }

            parseAltBeacon(companyId, payload)?.let(frames::add)
        }

        val eddystonePayload = serviceData.entries.firstOrNull {
            it.key.equals(EDDYSTONE_SERVICE_UUID, ignoreCase = true) ||
                it.key.equals("feaa", ignoreCase = true)
        }?.value

        val eddystoneFrames = eddystonePayload?.let(::parseEddystone).orEmpty()
        val hasEddystoneUrl = eddystoneFrames.any { it is BleBeaconFrame.EddystoneUrl }

        // SemBeacon uses the AltBeacon identity payload and an Eddystone-URL-compatible
        // scan response. Android may expose both parts in the same ScanRecord.
        if (hasEddystoneUrl) {
            val altBeaconIndex = frames.indexOfLast {
                it is BleBeaconFrame.StableIdentity && it.protocol == "altbeacon"
            }
            if (altBeaconIndex >= 0) {
                val alt = frames[altBeaconIndex] as BleBeaconFrame.StableIdentity
                frames[altBeaconIndex] = alt.copy(protocol = "sembeacon")
            }
        }

        frames.addAll(eddystoneFrames)
        return frames.distinctBy { frame ->
            when (frame) {
                is BleBeaconFrame.StableIdentity ->
                    "stable:${frame.protocol}:${frame.identifier}"
                is BleBeaconFrame.EddystoneUrl -> "url:${frame.url}"
                is BleBeaconFrame.EddystoneTelemetry ->
                    "tlm:${frame.batteryMillivolts}:${frame.advertisementCount}:${frame.uptimeSeconds}"
                is BleBeaconFrame.EddystoneEphemeralId -> "eid:${frame.ephemeralId}"
                is BleBeaconFrame.RuuviTelemetry ->
                    frame.identifier ?: "ruuvi-unidentified:${frame.measurementSequence}:${frame.movementCounter}"
            }
        }
    }

    fun summary(frame: BleBeaconFrame): String? = when (frame) {
        is BleBeaconFrame.StableIdentity -> null
        is BleBeaconFrame.EddystoneUrl -> "Eddystone URL: ${frame.url.take(120)}"
        is BleBeaconFrame.EddystoneTelemetry -> buildList {
            add("Eddystone TLM")
            frame.batteryMillivolts.takeIf { it > 0 }?.let { add("battery ${it} mV") }
            frame.temperatureCelsius?.let { add("temperature ${"%.1f".format(Locale.ROOT, it)} °C") }
            add("advertisements ${frame.advertisementCount}")
            add("uptime ${frame.uptimeSeconds} s")
        }.joinToString("; ")
        is BleBeaconFrame.EddystoneEphemeralId -> "Eddystone EID (rotating identifier)"
        is BleBeaconFrame.RuuviTelemetry -> buildList {
            add("Ruuvi RAWv2")
            frame.temperatureCelsius?.let { add("temperature ${"%.1f".format(Locale.ROOT, it)} °C") }
            frame.humidityPercent?.let { add("humidity ${"%.1f".format(Locale.ROOT, it)} %") }
            frame.pressurePascals?.let { add("pressure ${"%.1f".format(Locale.ROOT, it / 100.0)} hPa") }
            frame.accelerationXG?.let { x ->
                val y = frame.accelerationYG
                val z = frame.accelerationZG
                if (y != null && z != null) {
                    add("acceleration ${"%.2f".format(Locale.ROOT, x)}/${"%.2f".format(Locale.ROOT, y)}/${"%.2f".format(Locale.ROOT, z)} g")
                }
            }
            frame.batteryMillivolts?.let { add("battery ${"%.2f".format(Locale.ROOT, it / 1_000.0)} V") }
            frame.txPowerDbm?.let { add("TX ${it} dBm") }
            frame.movementCounter?.let { add("movement ${it}") }
            frame.measurementSequence?.let { add("sequence ${it}") }
        }.joinToString("; ")
    }

    private fun parseIBeacon(payload: ByteArray): BleBeaconFrame.StableIdentity? {
        // Apple manufacturer data: 02 15 + UUID(16) + Major(2) + Minor(2) + TxPower(1).
        if (payload.size != 23 || payload.u8(0) != 0x02 || payload.u8(1) != 0x15) return null
        val txPower = payload[22].toInt()
        if (txPower !in -127..20) return null

        val uuidHex = payload.hex(2, 18)
        val uuid = "${uuidHex.substring(0, 8)}-${uuidHex.substring(8, 12)}-" +
            "${uuidHex.substring(12, 16)}-${uuidHex.substring(16, 20)}-" +
            uuidHex.substring(20, 32)
        val major = payload.u16be(18)
        val minor = payload.u16be(20)
        return BleBeaconFrame.StableIdentity(
            protocol = "ibeacon",
            identifier = "ibeacon:$uuid:$major:$minor",
            calibratedTxPowerDbm = txPower
        )
    }

    private fun parseAltBeacon(
        companyId: Int,
        payload: ByteArray
    ): BleBeaconFrame.StableIdentity? {
        // AltBeacon manufacturer payload is exactly 24 bytes:
        // BEAC, 20-byte Beacon ID, signed reference RSSI, manufacturer-reserved byte.
        if (payload.size != 24 || payload.u8(0) != 0xBE || payload.u8(1) != 0xAC) return null
        val referenceRssi = payload[22].toInt()
        if (referenceRssi !in -127..0) return null
        val identifier = payload.hex(2, 22)
        return BleBeaconFrame.StableIdentity(
            protocol = "altbeacon",
            identifier = "altbeacon:${companyId.toString(16).padStart(4, '0')}:$identifier",
            calibratedTxPowerDbm = referenceRssi
        )
    }

    private fun parseEddystone(payload: ByteArray): List<BleBeaconFrame> {
        if (payload.isEmpty()) return emptyList()
        return when (payload.u8(0)) {
            0x00 -> parseEddystoneUid(payload)?.let(::listOf).orEmpty()
            0x10 -> parseEddystoneUrl(payload)?.let(::listOf).orEmpty()
            0x20 -> parseEddystoneTlm(payload)?.let(::listOf).orEmpty()
            0x30 -> parseEddystoneEid(payload)?.let(::listOf).orEmpty()
            else -> emptyList()
        }
    }

    private fun parseEddystoneUid(payload: ByteArray): BleBeaconFrame.StableIdentity? {
        // Standard UID is 20 bytes. Accept the older 18-byte form with omitted RFU octets.
        if (payload.size != 20 && payload.size != 18) return null
        val txPower = payload[1].toInt()
        if (txPower !in -100..20) return null
        if (payload.size == 20 && (payload.u8(18) != 0 || payload.u8(19) != 0)) return null
        val namespace = payload.hex(2, 12)
        val instance = payload.hex(12, 18)
        return BleBeaconFrame.StableIdentity(
            protocol = "eddystone-uid",
            identifier = "eddystone-uid:$namespace:$instance",
            calibratedTxPowerDbm = txPower
        )
    }

    private fun parseEddystoneUrl(payload: ByteArray): BleBeaconFrame.EddystoneUrl? {
        if (payload.size < 4) return null
        val txPower = payload[1].toInt()
        if (txPower !in -100..20) return null
        val prefix = URL_PREFIXES.getOrNull(payload.u8(2)) ?: return null
        val suffix = StringBuilder()
        for (index in 3 until payload.size) {
            val value = payload.u8(index)
            val expansion = URL_SUFFIXES.getOrNull(value)
            if (expansion != null) {
                suffix.append(expansion)
            } else {
                if (value !in 0x21..0x7E) return null
                suffix.append(value.toChar())
            }
        }
        if (suffix.isEmpty()) return null
        return BleBeaconFrame.EddystoneUrl(prefix + suffix, txPower)
    }

    private fun parseEddystoneTlm(payload: ByteArray): BleBeaconFrame.EddystoneTelemetry? {
        // Unencrypted TLM v0: frame, version, battery, signed 8.8 temperature,
        // advertising count and uptime (all multi-byte values big endian).
        if (payload.size != 14 || payload.u8(0) != 0x20 || payload.u8(1) != 0x00) return null
        val battery = payload.u16be(2)
        val rawTemperature = payload.u16be(4)
        val temperature = if (rawTemperature == 0x8000) null else {
            val signed = if (rawTemperature and 0x8000 != 0) rawTemperature - 0x10000 else rawTemperature
            signed / 256.0
        }
        val advertisementCount = payload.u32be(6)
        val uptimeSeconds = payload.u32be(10)
        return BleBeaconFrame.EddystoneTelemetry(
            batteryMillivolts = battery,
            temperatureCelsius = temperature,
            advertisementCount = advertisementCount,
            uptimeSeconds = uptimeSeconds
        )
    }

    private fun parseRuuviRawV2(payload: ByteArray): BleBeaconFrame.RuuviTelemetry? {
        // Ruuvi RAWv2 manufacturer payload: format, sensor fields, power, counters and
        // embedded 48-bit MAC. Android has already removed the 0x0499 company identifier.
        if (payload.size != 24 || payload.u8(0) != 0x05) return null

        val rawTemperature = payload.i16be(1)
        val rawHumidity = payload.u16be(3)
        val rawPressure = payload.u16be(5)
        val rawAccelerationX = payload.i16be(7)
        val rawAccelerationY = payload.i16be(9)
        val rawAccelerationZ = payload.i16be(11)
        val powerInfo = payload.u16be(13)
        val rawBattery = powerInfo ushr 5
        val rawTxPower = powerInfo and 0x1F
        val rawMovement = payload.u8(15)
        val rawSequence = payload.u16be(16)

        val embeddedMac = payload.hex(18, 24).chunked(2).joinToString(":")
        val firstOctet = embeddedMac.substringBefore(':').toIntOrNull(16) ?: return null
        val validMac = embeddedMac != "ff:ff:ff:ff:ff:ff" &&
            embeddedMac != "00:00:00:00:00:00" &&
            (firstOctet and 0x01) == 0

        return BleBeaconFrame.RuuviTelemetry(
            identifier = if (validMac) "ruuvi:$embeddedMac" else null,
            temperatureCelsius = rawTemperature.takeIf { it != Short.MIN_VALUE.toInt() }
                ?.div(200.0),
            humidityPercent = rawHumidity.takeIf { it <= 40_000 }
                ?.div(400.0),
            pressurePascals = rawPressure.takeIf { it != 0xFFFF }
                ?.plus(50_000),
            accelerationXG = rawAccelerationX.takeIf { it != Short.MIN_VALUE.toInt() }
                ?.div(1_000.0),
            accelerationYG = rawAccelerationY.takeIf { it != Short.MIN_VALUE.toInt() }
                ?.div(1_000.0),
            accelerationZG = rawAccelerationZ.takeIf { it != Short.MIN_VALUE.toInt() }
                ?.div(1_000.0),
            batteryMillivolts = rawBattery.takeIf { it != 0x07FF }
                ?.plus(1_600),
            txPowerDbm = rawTxPower.takeIf { it != 0x1F }
                ?.let { -40 + (it * 2) },
            movementCounter = rawMovement.takeIf { it != 0xFF },
            measurementSequence = rawSequence.takeIf { it != 0xFFFF }
        )
    }

    private fun parseEddystoneEid(payload: ByteArray): BleBeaconFrame.EddystoneEphemeralId? {
        // EID is intentionally rotating. It is surfaced as a frame but is never a
        // persistent position key without the provisioning service's resolver material.
        if (payload.size != 10) return null
        val txPower = payload[1].toInt()
        if (txPower !in -100..20) return null
        return BleBeaconFrame.EddystoneEphemeralId(
            ephemeralId = payload.hex(2, 10),
            calibratedTxPowerDbm = txPower
        )
    }

    private val URL_PREFIXES = listOf(
        "http://www.", "https://www.", "http://", "https://"
    )

    private val URL_SUFFIXES = listOf(
        ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
        ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov"
    )

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16be(index: Int): Int =
        (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.i16be(index: Int): Int =
        u16be(index).toShort().toInt()

    private fun ByteArray.u32be(index: Int): Long =
        (u8(index).toLong() shl 24) or
            (u8(index + 1).toLong() shl 16) or
            (u8(index + 2).toLong() shl 8) or
            u8(index + 3).toLong()

    private fun ByteArray.hex(from: Int, until: Int): String =
        (from until until).joinToString("") { "%02x".format(Locale.ROOT, u8(it)) }
}

/**
 * Android does not expose BLE address type on all supported releases.
 * Only API 35+ addresses explicitly reported as PUBLIC may be a generic
 * MAC-based fallback. RANDOM, ANONYMOUS, UNKNOWN and unavailable are rejected.
 */
internal object BleAddressPolicy {
    fun isStablePublicAddress(
        addressType: Int?,
        publicAddressType: Int,
        address: String?
    ): Boolean {
        if (addressType == null || addressType != publicAddressType || address == null) return false
        if (!address.matches(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))) return false
        val firstOctet = address.substringBefore(':').toIntOrNull(16) ?: return false
        val isUnicast = (firstOctet and 0x01) == 0
        val isGloballyAdministered = (firstOctet and 0x02) == 0
        return isUnicast && isGloballyAdministered
    }
}
