package org.cleargps

/** OBD adapter transport, independent of the data link used to talk to the ELM-compatible device. */
enum class Obd2TransportType(val displayName: String) {
    CLASSIC_SPP("Bluetooth Classic SPP"),
    BLE_GATT("Bluetooth LE GATT")
}

/** Stable selection key persisted by the UI. Legacy bare MAC addresses still mean Classic SPP. */
internal data class Obd2AdapterSelection(
    val address: String,
    val transportType: Obd2TransportType
) {
    val key: String
        get() = if (transportType == Obd2TransportType.CLASSIC_SPP) address.uppercase()
            else "ble:" + address.uppercase()

    companion object {
        private val MAC_PATTERN = Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        fun parse(key: String): Obd2AdapterSelection {
            val isBle = key.startsWith("ble:", ignoreCase = true)
            val address = if (isBle) key.substringAfter(':') else key
            require(MAC_PATTERN.matches(address)) { "Некорректный адрес OBD-II адаптера." }
            return Obd2AdapterSelection(
                address = address.uppercase(),
                transportType = if (isBle) Obd2TransportType.BLE_GATT else Obd2TransportType.CLASSIC_SPP
            )
        }
    }
}

/** Filter BLE names locally; this is a discovery hint, not proof of OBD-II compatibility. */
internal object Obd2AdapterNameFilter {
    private val hints = listOf(
        "obd", "elm327", "elm 327", "obdlink", "vlink", "v-link", "vgate", "veepeak",
        "konnwei", "viecar", "lelink", "carista", "thinkcar", "thinkdiag", "obdii"
    )

    fun looksLikeObdAdapter(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase()?.replace('_', '-') ?: return false
        return normalized.isNotBlank() && hints.any { normalized.contains(it) }
    }
}
