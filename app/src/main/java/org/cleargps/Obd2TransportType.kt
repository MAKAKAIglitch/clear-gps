package org.cleargps

/** OBD adapter transport, independent of the data link used to talk to the ELM-compatible device. */
enum class Obd2TransportType(val displayName: String) {
    CLASSIC_SPP("Bluetooth Classic SPP"),
    BLE_GATT("Bluetooth LE GATT"),
    WIFI_TCP("Wi-Fi TCP")
}

/** Stable local selection key. Legacy bare MAC addresses continue to mean Classic SPP. */
internal data class Obd2AdapterSelection(
    val address: String,
    val transportType: Obd2TransportType,
    val port: Int? = null
) {
    val key: String
        get() = when (transportType) {
            Obd2TransportType.CLASSIC_SPP -> address.uppercase()
            Obd2TransportType.BLE_GATT -> "ble:" + address.uppercase()
            Obd2TransportType.WIFI_TCP -> "wifi:$address:${port ?: DEFAULT_WIFI_PORT}"
        }

    companion object {
        const val DEFAULT_WIFI_PORT = 35000
        private val MAC_PATTERN = Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        fun parse(key: String): Obd2AdapterSelection {
            if (key.startsWith("wifi:", ignoreCase = true)) {
                val parts = key.substringAfter(':').split(':')
                require(parts.size == 2) { "У Wi-Fi OBD укажи локальный IPv4-адрес и TCP-порт." }
                val host = parts[0]
                val port = parts[1].toIntOrNull()
                require(Obd2WifiAddress.isPrivateIpv4(host)) {
                    "Для Wi-Fi OBD нужен локальный приватный IPv4-адрес, например 192.168.0.10."
                }
                require(port != null && port in 1..65535) { "TCP-порт должен быть в диапазоне 1–65535." }
                return Obd2AdapterSelection(host, Obd2TransportType.WIFI_TCP, port)
            }
            val isBle = key.startsWith("ble:", ignoreCase = true)
            val address = if (isBle) key.substringAfter(':') else key
            require(MAC_PATTERN.matches(address)) { "Некорректный Bluetooth-адрес OBD-II адаптера." }
            return Obd2AdapterSelection(
                address = address.uppercase(),
                transportType = if (isBle) Obd2TransportType.BLE_GATT else Obd2TransportType.CLASSIC_SPP
            )
        }
    }
}

/** Only RFC1918 private IPv4 destinations are accepted for manual Wi-Fi adapter entry. */
internal object Obd2WifiAddress {
    fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 } || values.joinToString(".") != host) return false
        val a = values[0]
        val b = values[1]
        val privateRange = when {
            a == 10 -> true
            a == 172 -> b in 16..31
            a == 192 && b == 168 -> true
            else -> false
        }
        return privateRange && values[3] !in setOf(0, 255)
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
