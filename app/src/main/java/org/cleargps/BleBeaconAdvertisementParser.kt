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
                frames[altBeaconIndex] = alt.copy(
                    protocol = "sembeacon",
                    identifier = alt.identifier.replaceFirst("altbeacon:", "sembeacon:")
                )
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
            }
        }
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
