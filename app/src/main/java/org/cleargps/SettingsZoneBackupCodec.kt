package org.cleargps

import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import java.io.InputStream
import java.util.Base64

/** A manually exported snapshot of non-sensitive app configuration plus user-authored risk zones. */
internal data class SettingsZoneBackupSnapshot(
    val positionMode: String,
    val mapTheme: String,
    val mapSource: String,
    val scanIntervalSeconds: Int,
    val adaptiveScan: Boolean,
    val useWifi: Boolean,
    val useCells: Boolean,
    val useBle: Boolean,
    val zones: List<SavedRiskZone>
)

internal object SettingsZoneBackupCodec {
    const val HEADER = "CLEAR_GPS_SETTINGS_ZONES|1"
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ZONES = 100
    const val MAX_POINTS_PER_ZONE = 1_000
    const val MAX_LINES = 100_000

    private val settingKeys = setOf(
        "positionMode", "mapTheme", "mapSource", "scanIntervalSeconds",
        "adaptiveScan", "useWifi", "useCells", "useBle"
    )
    private val validPositionModes = setOf("auto", "gps", "radio")
    private val validMapThemes = setOf("system", "light", "dark")
    private val validMapSources = setOf("online", "offline")

    private data class PendingZone(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val createdAt: Long,
        val expectedPoints: Int,
        val points: MutableList<ZonePoint> = mutableListOf()
    )

    fun encode(snapshot: SettingsZoneBackupSnapshot): String {
        validateSnapshot(snapshot)
        require(snapshot.zones.size <= MAX_ZONES) { "Too many risk zones." }
        val lines = mutableListOf(HEADER)
        fun setting(key: String, value: String) {
            lines += listOf("S", key, value).joinToString("|")
        }
        setting("positionMode", snapshot.positionMode)
        setting("mapTheme", snapshot.mapTheme)
        setting("mapSource", snapshot.mapSource)
        setting("scanIntervalSeconds", snapshot.scanIntervalSeconds.toString())
        setting("adaptiveScan", bit(snapshot.adaptiveScan))
        setting("useWifi", bit(snapshot.useWifi))
        setting("useCells", bit(snapshot.useCells))
        setting("useBle", bit(snapshot.useBle))

        val seenIds = mutableSetOf<String>()
        snapshot.zones.forEach { zone ->
            validateZone(zone)
            require(seenIds.add(zone.id)) { "Duplicate risk-zone ID." }
            lines += listOf(
                "Z", token(zone.id), token(zone.name), bit(zone.enabled),
                zone.createdAt.toString(), zone.points.size.toString()
            ).joinToString("|")
            zone.points.forEach { point ->
                lines += listOf(
                    "P", token(zone.id), point.latitude.toString(), point.longitude.toString()
                ).joinToString("|")
            }
        }
        lines += listOf("END", snapshot.zones.size.toString()).joinToString("|")
        require(lines.size <= MAX_LINES) { "Backup has too many lines." }
        val text = lines.joinToString("\n", postfix = "\n")
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 2 MiB limit." }
        return text
    }

    fun decode(text: String): SettingsZoneBackupSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 2 MiB limit." }
        val lines = text.lineSequence().map { it.removeSuffix("\r") }.filter { it.isNotBlank() }.toList()
        require(lines.size <= MAX_LINES) { "Backup has too many lines." }
        require(lines.firstOrNull()?.removePrefix("\uFEFF") == HEADER) { "Unsupported settings backup format." }

        val settings = linkedMapOf<String, String>()
        val zones = mutableListOf<SavedRiskZone>()
        val zoneIds = mutableSetOf<String>()
        var current: PendingZone? = null
        var ended = false

        fun finishZone() {
            val pending = current ?: return
            require(pending.points.size == pending.expectedPoints) { "Risk-zone point count mismatch." }
            zones += SavedRiskZone(
                id = pending.id,
                name = pending.name,
                points = pending.points.toList(),
                enabled = pending.enabled,
                createdAt = pending.createdAt
            )
            current = null
        }

        for (line in lines.drop(1)) {
            require(!ended) { "Data found after backup footer." }
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                "S" -> {
                    require(current == null) { "Settings must precede risk zones." }
                    require(fields.size == 3) { "Invalid settings row." }
                    val key = fields[1]
                    require(key in settingKeys && key !in settings) { "Unknown or duplicate setting." }
                    settings[key] = fields[2]
                }
                "Z" -> {
                    finishZone()
                    require(fields.size == 6) { "Invalid risk-zone header." }
                    require(zones.size < MAX_ZONES) { "Too many risk zones." }
                    val id = fromToken(fields[1]).also {
                        require(it.isNotBlank() && it.length <= 128 && zoneIds.add(it)) { "Invalid or duplicate risk-zone ID." }
                    }
                    val name = fromToken(fields[2])
                    require(name.isNotBlank() && name.length <= 80 && name == name.trim()) { "Invalid risk-zone name." }
                    val enabled = parseBit(fields[3])
                    val createdAt = fields[4].toLongOrNull()
                        ?.also { require(it in 0L..Long.MAX_VALUE) }
                        ?: throw IllegalArgumentException("Invalid risk-zone date.")
                    val pointsCount = fields[5].toIntOrNull()
                        ?.also { require(it in 3..MAX_POINTS_PER_ZONE) }
                        ?: throw IllegalArgumentException("Invalid risk-zone point count.")
                    current = PendingZone(id, name, enabled, createdAt, pointsCount)
                }
                "P" -> {
                    require(fields.size == 4) { "Invalid risk-zone point." }
                    val pending = current ?: throw IllegalArgumentException("Risk-zone point has no zone.")
                    require(fromToken(fields[1]) == pending.id) { "Risk-zone point ID mismatch." }
                    require(pending.points.size < pending.expectedPoints) { "Too many points for risk zone." }
                    val latitude = fields[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                        ?: throw IllegalArgumentException("Invalid risk-zone latitude.")
                    val longitude = fields[3].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                        ?: throw IllegalArgumentException("Invalid risk-zone longitude.")
                    pending.points += ZonePoint(latitude, longitude)
                }
                "END" -> {
                    require(fields.size == 2) { "Invalid backup footer." }
                    finishZone()
                    val expectedZones = fields[1].toIntOrNull()
                        ?.also { require(it == zones.size) }
                        ?: throw IllegalArgumentException("Risk-zone count mismatch.")
                    ended = true
                }
                else -> throw IllegalArgumentException("Unknown backup record.")
            }
        }
        require(ended) { "Backup footer is missing." }
        require(settings.keys == settingKeys) { "Backup is missing required settings." }

        fun required(key: String) = settings[key] ?: throw IllegalArgumentException("Missing setting.")
        fun int(key: String) = required(key).toIntOrNull() ?: throw IllegalArgumentException("Invalid numeric setting.")
        fun bool(key: String) = parseBit(required(key))

        val snapshot = SettingsZoneBackupSnapshot(
            positionMode = required("positionMode"),
            mapTheme = required("mapTheme"),
            mapSource = required("mapSource"),
            scanIntervalSeconds = int("scanIntervalSeconds"),
            adaptiveScan = bool("adaptiveScan"),
            useWifi = bool("useWifi"),
            useCells = bool("useCells"),
            useBle = bool("useBle"),
            zones = zones.toList()
        )
        validateSnapshot(snapshot)
        return snapshot
    }

    fun readBounded(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_BYTES) { "Backup exceeds the 2 MiB limit." }
                out.write(buffer, 0, count)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun validateSnapshot(snapshot: SettingsZoneBackupSnapshot) {
        require(snapshot.positionMode in validPositionModes) { "Invalid position mode." }
        require(snapshot.mapTheme in validMapThemes) { "Invalid map theme." }
        require(snapshot.mapSource in validMapSources) { "Invalid map source." }
        require(snapshot.scanIntervalSeconds in 1..30) { "Invalid scan interval." }
        require(snapshot.zones.size <= MAX_ZONES) { "Too many risk zones." }
        val ids = mutableSetOf<String>()
        snapshot.zones.forEach {
            validateZone(it)
            require(ids.add(it.id)) { "Duplicate risk-zone ID." }
        }
    }

    private fun validateZone(zone: SavedRiskZone) {
        require(zone.id.isNotBlank() && zone.id.length <= 128) { "Invalid risk-zone ID." }
        require(zone.name.isNotBlank() && zone.name.length <= 80) { "Invalid risk-zone name." }
        require(zone.points.size in 3..MAX_POINTS_PER_ZONE) { "Invalid risk-zone point count." }
        require(zone.createdAt >= 0L) { "Invalid risk-zone date." }
        require(zone.points.all { point ->
            point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                point.longitude.isFinite() && point.longitude in -180.0..180.0
        }) { "Invalid risk-zone coordinates." }
    }

    private fun token(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun fromToken(value: String): String = try {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid encoded text in backup.", error)
    }

    private fun bit(value: Boolean) = if (value) "1" else "0"

    private fun parseBit(value: String): Boolean = when (value) {
        "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("Invalid boolean setting.")
    }
}
