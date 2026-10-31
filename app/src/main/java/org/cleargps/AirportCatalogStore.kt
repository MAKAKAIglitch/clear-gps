package org.cleargps

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class AirportRecord(
    val ident: String,
    val icao: String,
    val iata: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
) {
    fun allCodes(): Set<String> = setOf(ident, icao, iata)
        .map { it.trim().uppercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
}

data class AirportCatalogSnapshot(
    val airports: List<AirportRecord>,
    val updatedAtMillis: Long,
    val source: String
) {
    val isAvailable: Boolean get() = airports.isNotEmpty()
    val isStale: Boolean get() {
        if (!isAvailable || updatedAtMillis <= 0L) return true
        val ageMillis = System.currentTimeMillis() - updatedAtMillis
        return ageMillis < 0L || ageMillis > AirportCatalogStore.MAX_AGE_MILLIS
    }
}

/**
 * Small, compressed offline cache of public airport locations.
 *
 * OurAirports is public-domain data, but its nightly all-airports CSV is ~12.7 MB.
 * Clear GPS keeps only airport identifiers and coordinates in a compressed local
 * cache. The initial download is opt-in, read-only HTTPS GET, and never includes
 * the user's coordinates or radio observations.
 */
internal object AirportCatalogStore {
    const val MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    private const val BUNDLED_DATASET_UPDATED_AT_MILLIS = 1_790_839_210_000L // mwgg/Airports commit a89e4e0 (2026-10-01)
    private const val MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024
    private const val MIN_VALID_AIRPORTS = 1_000
    private const val MAX_AIRPORTS = 100_000
    private const val MAGIC = 0x43474150 // "CGAP"
    private const val VERSION = 1
    private const val CACHE_FILE = "airport-catalog-v1.bin.gz"
    private const val SOURCE_URL = "https://davidmegginson.github.io/ourairports-data/airports.csv"

    @Volatile private var memorySnapshot: AirportCatalogSnapshot? = null
    private val lock = Any()

    fun load(context: Context): AirportCatalogSnapshot {
        memorySnapshot?.let { return it }
        synchronized(lock) {
            memorySnapshot?.let { return it }
            val appContext = context.applicationContext
            val bundled = loadBundled(appContext)
            val cached = readCache(cacheFile(appContext))
            // Prefer the newest known catalogue: an online refresh remains authoritative
            // until the bundled snapshot is newer or the cache is corrupt.
            val snapshot = listOfNotNull(bundled, cached)
                .maxByOrNull { it.updatedAtMillis }
                ?: AirportCatalogSnapshot(emptyList(), 0L, "unavailable")
            memorySnapshot = snapshot
            return snapshot
        }
    }

    /**
     * Downloads only the public airport catalogue. Call this from Dispatchers.IO.
     * The dedicated user switch is checked again here, not only by the UI.
     */
    fun refresh(context: Context): AirportCatalogSnapshot {
        val appContext = context.applicationContext
        val enabled = appContext.getSharedPreferences(
            ClearGpsPreferences.PREFS, Context.MODE_PRIVATE
        ).getBoolean(ClearGpsPreferences.KEY_AIRPORT_CATALOG_ONLINE_ENABLED, false)
        check(enabled) { "Загрузка аэропортов выключена в настройках." }

        val connection = NetworkGate.openAirportCatalogConnection(appContext, SOURCE_URL)
        val downloaded = try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "text/csv")
            connection.setRequestProperty("User-Agent", "ClearGPS/1.0 (read-only airport catalogue)")
            val responseCode = connection.responseCode
            check(responseCode == 200) { "OurAirports вернул HTTP $responseCode." }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_DOWNLOAD_BYTES.toLong() || declaredLength < 0L) {
                "Файл каталога аэропортов больше допустимого лимита."
            }
            connection.inputStream.use { readBounded(it, MAX_DOWNLOAD_BYTES) }
        } finally {
            connection.disconnect()
        }

        val parsed = parseAirports(String(downloaded, StandardCharsets.UTF_8))
        require(parsed.size >= MIN_VALID_AIRPORTS) {
            "В каталоге найдено слишком мало пригодных аэропортов: ${parsed.size}."
        }
        require(parsed.size <= MAX_AIRPORTS) { "В каталоге слишком много записей." }
        val snapshot = AirportCatalogSnapshot(
            airports = parsed,
            updatedAtMillis = System.currentTimeMillis(),
            source = "ourairports"
        )
        writeCache(cacheFile(appContext), snapshot)
        synchronized(lock) { memorySnapshot = snapshot }
        DiagnosticLogStore.log(
            appContext,
            "AIRPORT_CATALOG",
            "Updated public airport catalogue: ${parsed.size} valid airport locations; user coordinates and radio IDs were not sent"
        )
        return snapshot
    }

    fun clearMemoryCache() {
        synchronized(lock) { memorySnapshot = null }
    }

    fun cacheFile(context: Context): File = File(context.applicationContext.filesDir, CACHE_FILE)

    private fun loadBundled(context: Context): AirportCatalogSnapshot? = try {
        context.assets.open("airports.csv").use { input ->
            val bytes = readBounded(input, MAX_DOWNLOAD_BYTES)
            val airports = parseAirports(String(bytes, StandardCharsets.UTF_8))
            if (airports.size < MIN_VALID_AIRPORTS) null
            else AirportCatalogSnapshot(airports, BUNDLED_DATASET_UPDATED_AT_MILLIS, "bundled:mwgg/Airports")
        }
    } catch (_: Exception) {
        null
    }

    private fun readCache(file: File): AirportCatalogSnapshot? {
        if (!file.isFile || file.length() !in 32L..8L * 1024L * 1024L) return null
        return try {
            DataInputStream(GZIPInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val updatedAt = input.readLong()
                val count = input.readInt()
                if (updatedAt <= 0L || count !in MIN_VALID_AIRPORTS..MAX_AIRPORTS) return null
                val airports = ArrayList<AirportRecord>(count)
                repeat(count) {
                    val airport = AirportRecord(
                        ident = input.readUTF(),
                        icao = input.readUTF(),
                        iata = input.readUTF(),
                        name = input.readUTF(),
                        latitude = input.readDouble(),
                        longitude = input.readDouble()
                    )
                    if (!isValidAirport(airport)) return null
                    airports.add(airport)
                }
                AirportCatalogSnapshot(airports, updatedAt, "cached")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(file: File, snapshot: AirportCatalogSnapshot) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(GZIPOutputStream(FileOutputStream(temporary))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(snapshot.updatedAtMillis)
                output.writeInt(snapshot.airports.size)
                snapshot.airports.forEach { airport ->
                    output.writeUTF(airport.ident.take(16))
                    output.writeUTF(airport.icao.take(8))
                    output.writeUTF(airport.iata.take(8))
                    output.writeUTF(airport.name.take(120))
                    output.writeDouble(airport.latitude)
                    output.writeDouble(airport.longitude)
                }
                output.flush()
            }
            val backup = File(file.parentFile, file.name + ".bak")
            if (backup.exists()) {
                if (!file.exists()) {
                    // Recover the last good cache after an interrupted previous replacement.
                    backup.renameTo(file)
                } else {
                    backup.delete()
                }
            }
            val hadPrevious = file.exists()
            if (hadPrevious && !file.renameTo(backup)) {
                throw IllegalStateException("Не удалось сохранить предыдущий каталог аэропортов.")
            }
            if (!temporary.renameTo(file)) {
                if (hadPrevious) backup.renameTo(file)
                throw IllegalStateException("Не удалось сохранить локальный каталог аэропортов.")
            }
            if (backup.exists()) backup.delete()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    internal fun parseAirports(csv: String): List<AirportRecord> {
        val rows = csvRows(csv).iterator()
        if (!rows.hasNext()) return emptyList()
        val headers = rows.next().mapIndexed { index, value ->
            value.trim().removePrefix("\uFEFF").uppercase(Locale.ROOT) to index
        }.toMap()
        fun field(row: List<String>, key: String): String =
            headers[key]?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()

        val acceptedTypes = setOf(
            "airport", "large_airport", "medium_airport", "small_airport",
            "heliport", "seaplane_base"
        )
        val output = LinkedHashMap<String, AirportRecord>()
        while (rows.hasNext() && output.size < MAX_AIRPORTS) {
            val row = rows.next()
            val type = field(row, "TYPE").lowercase(Locale.ROOT)
            if (type !in acceptedTypes) continue
            val latitude = field(row, "LATITUDE_DEG").toDoubleOrNull() ?: continue
            val longitude = field(row, "LONGITUDE_DEG").toDoubleOrNull() ?: continue
            val airport = AirportRecord(
                ident = field(row, "IDENT").take(16),
                icao = field(row, "GPS_CODE").take(8),
                iata = field(row, "IATA_CODE").take(8),
                name = field(row, "NAME").take(120),
                latitude = latitude,
                longitude = longitude
            )
            if (!isValidAirport(airport)) continue
            if (airport.ident.isBlank() && airport.icao.isBlank() && airport.iata.isBlank()) continue
            val key = airport.icao.ifBlank { airport.iata.ifBlank { airport.ident } }
                .uppercase(Locale.ROOT)
            output.putIfAbsent(key, airport)
        }
        return output.values.toList()
    }

    /** RFC-4180-style row reader; quoted commas and doubled quotes are supported. */
    private fun csvRows(value: String): Sequence<List<String>> = sequence {
        val row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                quoted && char == '"' && index + 1 < value.length && value[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                (char == ',' || char == '\n' || char == '\r') && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (char != ',') {
                        if (char == '\r' && index + 1 < value.length && value[index + 1] == '\n') index++
                        if (row.any { it.isNotBlank() }) yield(row.toList())
                        row.clear()
                    }
                }
                else -> field.append(char)
            }
            index++
        }
        row.add(field.toString())
        if (row.any { it.isNotBlank() }) yield(row.toList())
    }

    private fun isValidAirport(airport: AirportRecord): Boolean =
        airport.latitude.isFinite() && airport.latitude in -90.0..90.0 &&
            airport.longitude.isFinite() && airport.longitude in -180.0..180.0

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Каталог аэропортов превышает лимит 16 МБ." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
