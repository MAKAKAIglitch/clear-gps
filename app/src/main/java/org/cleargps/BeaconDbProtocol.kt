package org.cleargps

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class BeaconDbCell(
    val radioType: String,
    val mobileCountryCode: Int,
    val mobileNetworkCode: Int,
    val locationAreaCode: Int,
    val cellId: Long,
    val signalStrength: Int,
    val ageMillis: Long? = null
) {
    val identifier: String
        get() = "$radioType:$mobileCountryCode:$mobileNetworkCode:$locationAreaCode:$cellId"

    fun isValid(): Boolean =
        radioType in BeaconDbProtocol.SUPPORTED_RADIOS &&
            mobileCountryCode in 1..999 &&
            mobileNetworkCode in 0..999 &&
            locationAreaCode >= 0 &&
            cellId in 1..Int.MAX_VALUE.toLong() &&
            signalStrength in -160..0 &&
            (ageMillis == null || ageMillis >= 0L)
}

/** SSID is transient and used only to apply hidden/_nomap exclusions; it is never serialized. */
data class BeaconDbWifiAccessPoint(
    val macAddress: String,
    val signalStrength: Int,
    val ageMillis: Long? = null,
    val frequencyMhz: Int? = null,
    val ssid: String? = null
) {
    val identifier: String get() = macAddress.lowercase(Locale.ROOT)

    fun isValid(): Boolean {
        val name = ssid?.trim().orEmpty()
        val hiddenOrUnknown = name.isBlank() ||
            name.equals("<unknown ssid>", ignoreCase = true) ||
            name.equals("0x", ignoreCase = true)
        return BeaconDbProtocol.isMacAddress(macAddress) &&
            signalStrength in -128..0 &&
            (ageMillis == null || ageMillis >= 0L) &&
            (frequencyMhz == null || frequencyMhz in 2_000..8_000) &&
            !hiddenOrUnknown &&
            !name.endsWith("_nomap", ignoreCase = true)
    }
}

/** Only globally administered unicast BLE MACs are used; private/random addresses are excluded. */
data class BeaconDbBluetoothBeacon(
    val macAddress: String,
    val signalStrength: Int,
    val ageMillis: Long? = null
) {
    val identifier: String get() = macAddress.lowercase(Locale.ROOT)

    fun isValid(): Boolean =
        BeaconDbProtocol.isStableBeaconAddress(macAddress) &&
            signalStrength in -128..0 &&
            (ageMillis == null || ageMillis >= 0L)
}

data class BeaconDbRequest(
    val cells: List<BeaconDbCell> = emptyList(),
    val wifiAccessPoints: List<BeaconDbWifiAccessPoint> = emptyList(),
    val bluetoothBeacons: List<BeaconDbBluetoothBeacon> = emptyList()
) {
    fun normalized(): BeaconDbRequest = BeaconDbRequest(
        cells = cells.filter { it.isValid() }
            .distinctBy { it.identifier }
            .sortedBy { it.identifier }
            .take(BeaconDbProtocol.MAX_CELL_TOWERS),
        wifiAccessPoints = wifiAccessPoints.filter { it.isValid() }
            .groupBy { it.identifier }
            .values.mapNotNull { it.maxByOrNull { signal -> signal.signalStrength } }
            .sortedBy { it.identifier }
            .take(BeaconDbProtocol.MAX_WIFI_ACCESS_POINTS),
        bluetoothBeacons = bluetoothBeacons.filter { it.isValid() }
            .groupBy { it.identifier }
            .values.mapNotNull { it.maxByOrNull { signal -> signal.signalStrength } }
            .sortedBy { it.identifier }
            .take(BeaconDbProtocol.MAX_BLUETOOTH_BEACONS)
    )

    val uniqueWifiBluetoothNetworks: Int
        get() = (wifiAccessPoints.map { it.identifier } + bluetoothBeacons.map { it.identifier }).distinct().size

    fun canLookup(): Boolean =
        cells.any { it.isValid() } || uniqueWifiBluetoothNetworks >= BeaconDbProtocol.MIN_WIFI_BLE_NETWORKS
}

data class BeaconDbCoordinate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double
)

data class BeaconDbLookupResult(
    val cells: List<BeaconDbCell>,
    val unsupportedCellCount: Int = 0,
    val coordinate: BeaconDbCoordinate? = null,
    val queriedAtMillis: Long = 0L,
    val message: String,
    val fromCache: Boolean = false,
    val cacheAgeMillis: Long = 0L,
    val wifiAccessPoints: List<BeaconDbWifiAccessPoint> = emptyList(),
    val bluetoothBeacons: List<BeaconDbBluetoothBeacon> = emptyList()
) {
    val success: Boolean get() = coordinate != null
}

internal object BeaconDbProtocol {
    val SUPPORTED_RADIOS = setOf("gsm", "wcdma", "lte")
    const val MIN_WIFI_BLE_NETWORKS = 2
    const val MAX_CELL_TOWERS = 16
    const val MAX_WIFI_ACCESS_POINTS = 64
    const val MAX_BLUETOOTH_BEACONS = 128

    fun isMacAddress(value: String): Boolean =
        value.matches(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))

    fun isStableBeaconAddress(value: String): Boolean {
        if (!isMacAddress(value)) return false
        val firstOctet = value.substringBefore(':').toIntOrNull(16) ?: return false
        // I/G bit 0 = unicast; U/L bit 0 = globally administered, not private/random.
        return (firstOctet and 0x01) == 0 && (firstOctet and 0x02) == 0
    }

    fun cellFromSignal(signal: RadioSignal, nowElapsedRealtimeNanos: Long? = null): BeaconDbCell? {
        if (signal.type != "cell") return null
        val parts = signal.identifier.split(':')
        if (parts.size != 5) return null
        val measuredAt = signal.observedAtElapsedRealtimeNanos
        val ageMillis = if (nowElapsedRealtimeNanos != null &&
            measuredAt != null && measuredAt > 0L && measuredAt <= nowElapsedRealtimeNanos
        ) (nowElapsedRealtimeNanos - measuredAt) / 1_000_000L else null
        return BeaconDbCell(
            radioType = parts[0].lowercase(Locale.ROOT),
            mobileCountryCode = parts[1].toIntOrNull() ?: return null,
            mobileNetworkCode = parts[2].toIntOrNull() ?: return null,
            locationAreaCode = parts[3].toIntOrNull() ?: return null,
            cellId = parts[4].toLongOrNull() ?: return null,
            signalStrength = signal.rssi,
            ageMillis = ageMillis
        ).takeIf { it.isValid() }
    }

    fun wifiFromSignal(signal: RadioSignal, nowElapsedRealtimeNanos: Long): BeaconDbWifiAccessPoint? {
        if (signal.type != "wifi") return null
        val measuredAt = signal.observedAtElapsedRealtimeNanos
        val ageMillis = measuredAt?.takeIf { it > 0L && it <= nowElapsedRealtimeNanos }
            ?.let { (nowElapsedRealtimeNanos - it) / 1_000_000L }
        return BeaconDbWifiAccessPoint(
            macAddress = signal.identifier,
            signalStrength = signal.rssi,
            ageMillis = ageMillis,
            frequencyMhz = signal.frequency.takeIf { it > 0 },
            ssid = signal.ssid
        ).takeIf { it.isValid() }
    }

    fun bluetoothFromSignal(signal: RadioSignal, nowElapsedRealtimeNanos: Long): BeaconDbBluetoothBeacon? {
        if (signal.type != "ble") return null
        val measuredAt = signal.observedAtElapsedRealtimeNanos
        val ageMillis = measuredAt?.takeIf { it > 0L && it <= nowElapsedRealtimeNanos }
            ?.let { (nowElapsedRealtimeNanos - it) / 1_000_000L }
        return BeaconDbBluetoothBeacon(
            macAddress = signal.identifier,
            signalStrength = signal.rssi,
            ageMillis = ageMillis
        ).takeIf { it.isValid() }
    }

    fun buildRequestBody(request: BeaconDbRequest): String {
        val normalized = request.normalized()
        require(normalized.canLookup()) {
            "A supported cell or at least two Wi-Fi/BLE network identifiers is required."
        }
        val root = JSONObject()
            .put("considerIp", false)
            .put("fallbacks", JSONObject().put("lacf", false).put("ipf", false))

        if (normalized.cells.isNotEmpty()) {
            val cells = JSONArray()
            normalized.cells.forEach { cell ->
                val item = JSONObject()
                    .put("radioType", cell.radioType)
                    .put("mobileCountryCode", cell.mobileCountryCode)
                    .put("mobileNetworkCode", cell.mobileNetworkCode)
                    .put("locationAreaCode", cell.locationAreaCode)
                    .put("cellId", cell.cellId)
                    .put("signalStrength", cell.signalStrength)
                cell.ageMillis?.let { item.put("age", it) }
                cells.put(item)
            }
            root.put("cellTowers", cells)
        }
        if (normalized.wifiAccessPoints.isNotEmpty()) {
            val wifi = JSONArray()
            normalized.wifiAccessPoints.forEach { point ->
                val item = JSONObject()
                    .put("macAddress", point.macAddress.lowercase(Locale.ROOT))
                    .put("signalStrength", point.signalStrength)
                point.ageMillis?.let { item.put("age", it) }
                point.frequencyMhz?.let { item.put("frequency", it) }
                // SSID is deliberately not serialized.
                wifi.put(item)
            }
            root.put("wifiAccessPoints", wifi)
        }
        if (normalized.bluetoothBeacons.isNotEmpty()) {
            val beacons = JSONArray()
            normalized.bluetoothBeacons.forEach { beacon ->
                val item = JSONObject()
                    .put("macAddress", beacon.macAddress.lowercase(Locale.ROOT))
                    .put("signalStrength", beacon.signalStrength)
                beacon.ageMillis?.let { item.put("age", it) }
                // Names and advertisement payloads are never read or sent.
                beacons.put(item)
            }
            root.put("bluetoothBeacons", beacons)
        }
        return root.toString()
    }

    /** Stable identity-only digest for automatic refresh scheduling; raw IDs are never persisted. */
    fun identityFingerprint(request: BeaconDbRequest): String {
        val normalized = request.normalized()
        if (normalized.cells.isEmpty() && normalized.wifiAccessPoints.isEmpty() &&
            normalized.bluetoothBeacons.isEmpty()
        ) return ""
        val canonical = buildString {
            append("beacondb-auto-identities-v1\\n")
            append("cells\\n")
            normalized.cells.forEach { append(it.identifier).append('\\n') }
            append("wifi\\n")
            normalized.wifiAccessPoints.forEach { append(it.identifier).append('\\n') }
            append("ble\\n")
            normalized.bluetoothBeacons.forEach { append(it.identifier).append('\\n') }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun signalStrengthBucket(value: Int): Int = Math.floorDiv(value + 5, 10) * 10

    /** Cache fingerprint intentionally excludes SSIDs and sample ages. */
    fun cacheFingerprint(request: BeaconDbRequest): String {
        val normalized = request.normalized()
        return buildString {
            append("beacondb-multiradio-v2\n")
            append("cells\n")
            normalized.cells.forEach { append(it.identifier).append(':').append(signalStrengthBucket(it.signalStrength)).append('\n') }
            append("wifi\n")
            normalized.wifiAccessPoints.forEach {
                append(it.identifier).append(':').append(signalStrengthBucket(it.signalStrength)).append(':')
                    .append(it.frequencyMhz ?: 0).append('\n')
            }
            append("ble\n")
            normalized.bluetoothBeacons.forEach {
                append(it.identifier).append(':').append(signalStrengthBucket(it.signalStrength)).append('\n')
            }
        }
    }

    fun parseCoordinateResponse(responseBody: String): BeaconDbCoordinate? {
        return try {
            val root = JSONObject(responseBody)
            if (root.has("fallback") && !root.isNull("fallback") &&
                root.opt("fallback")?.toString().orEmpty().isNotBlank()
            ) return null
            val location = root.optJSONObject("location") ?: return null
            val latitude = location.optDouble("lat", Double.NaN)
            val longitude = location.optDouble("lng", Double.NaN)
            val accuracy = root.optDouble("accuracy", Double.NaN)
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0 ||
                !accuracy.isFinite() || accuracy <= 0.0 || accuracy > 10_000_000.0
            ) null else BeaconDbCoordinate(latitude, longitude, accuracy)
        } catch (_: Exception) {
            null
        }
    }
}
