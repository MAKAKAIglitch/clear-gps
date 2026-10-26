package org.cleargps

import org.json.JSONObject

/**
 * A cellular-only request input. These values are sent to BeaconDB only after the user
 * explicitly enables BeaconDB in Settings and presses the lookup button.
 */
data class BeaconDbCell(
    val radioType: String,
    val mobileCountryCode: Int,
    val mobileNetworkCode: Int,
    val locationAreaCode: Int,
    val cellId: Long,
    val signalStrength: Int
) {
    val identifier: String
        get() = radioType + ":" + mobileCountryCode + ":" + mobileNetworkCode + ":" +
            locationAreaCode + ":" + cellId

    fun isValid(): Boolean =
        radioType in BeaconDbProtocol.SUPPORTED_RADIOS &&
            mobileCountryCode in 1..999 &&
            mobileNetworkCode in 0..999 &&
            locationAreaCode >= 0 &&
            cellId in 1..Int.MAX_VALUE.toLong() &&
            signalStrength in -160..0
}

data class BeaconDbCoordinate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double
)

data class BeaconDbLookupResult(
    /** Only the supported cellular identifiers included in the request; kept in memory. */
    val cells: List<BeaconDbCell>,
    val unsupportedCellCount: Int = 0,
    val coordinate: BeaconDbCoordinate? = null,
    val queriedAtMillis: Long = 0L,
    val message: String,
    /** True when this explicit lookup reused a successful in-memory cache entry. */
    val fromCache: Boolean = false,
    /** Age of the cache entry at lookup time; zero for network results. */
    val cacheAgeMillis: Long = 0L
) {
    val success: Boolean get() = coordinate != null
}

internal object BeaconDbProtocol {
    val SUPPORTED_RADIOS = setOf("gsm", "wcdma", "lte")

    /**
     * Maps the existing local cell-signal representation to the documented Ichnaea-style
     * cellTowers payload. Wi-Fi, BLE and 5G NR entries are intentionally never serialized.
     */
    fun cellFromSignal(signal: RadioSignal): BeaconDbCell? {
        if (signal.type != "cell") return null
        val parts = signal.identifier.split(':')
        if (parts.size != 5) return null
        val cell = BeaconDbCell(
            radioType = parts[0].lowercase(),
            mobileCountryCode = parts[1].toIntOrNull() ?: return null,
            mobileNetworkCode = parts[2].toIntOrNull() ?: return null,
            locationAreaCode = parts[3].toIntOrNull() ?: return null,
            cellId = parts[4].toLongOrNull() ?: return null,
            signalStrength = signal.rssi
        )
        return cell.takeIf { it.isValid() }
    }

    fun buildCellOnlyRequestBody(cells: List<BeaconDbCell>): String {
        val validCells = cells.filter { it.isValid() }.distinctBy { it.identifier }
        require(validCells.isNotEmpty()) { "At least one supported cellular tower is required." }

        val encodedCells = validCells.joinToString(",") { cell ->
            "{\"radioType\":\"" + cell.radioType +
                "\",\"mobileCountryCode\":" + cell.mobileCountryCode +
                ",\"mobileNetworkCode\":" + cell.mobileNetworkCode +
                ",\"locationAreaCode\":" + cell.locationAreaCode +
                ",\"cellId\":" + cell.cellId +
                ",\"signalStrength\":" + cell.signalStrength + "}"
        }
        // No wifiAccessPoints, bluetoothBeacons, GPS coordinates, or submission metadata.
        // Disable both IP-based and area-based fallback so the estimate must use cell records.
        return "{\"considerIp\":false,\"fallbacks\":{\"lacf\":false,\"ipf\":false},\"cellTowers\":[" +
            encodedCells + "]}"
    }

    fun parseCoordinateResponse(responseBody: String): BeaconDbCoordinate? {
        return try {
            val root = JSONObject(responseBody)
            // The request disables IP and location-area fallbacks. If the server nevertheless
            // labels a fallback result, do not present it as an exact cellular database match.
            val fallback = root.opt("fallback")
            if (fallback != null && fallback != JSONObject.NULL && fallback.toString().isNotBlank()) {
                return null
            }
            val location = root.optJSONObject("location") ?: return null
            val latitude = location.optDouble("lat", Double.NaN)
            val longitude = location.optDouble("lng", Double.NaN)
            val accuracy = root.optDouble("accuracy", Double.NaN)
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0 ||
                !accuracy.isFinite() || accuracy <= 0.0 || accuracy > 10_000_000.0) {
                null
            } else {
                BeaconDbCoordinate(latitude, longitude, accuracy)
            }
        } catch (_: Exception) {
            null
        }
    }
}
