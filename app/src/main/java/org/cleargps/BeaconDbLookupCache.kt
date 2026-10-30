package org.cleargps

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * A bounded, process-local cache for successful, explicitly requested BeaconDB estimates.
 *
 * The cache key is a SHA-256 digest of the supported cell identifiers and their signal levels.
 * Raw cell identifiers are not retained by this class; entries never reach SQLite or training.
 */
internal class BeaconDbLookupCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    data class Hit(
        val coordinate: BeaconDbCoordinate,
        val queriedAtMillis: Long,
        val ageMillis: Long
    )

    private data class Entry(
        val coordinate: BeaconDbCoordinate,
        val queriedAtMillis: Long,
        val storedAtMonotonicMillis: Long
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var generation = 0L

    init {
        require(ttlMillis > 0L) { "TTL must be positive." }
        require(maxEntries > 0) { "Cache capacity must be positive." }
    }

    /** Current generation lets callers reject in-flight responses after an opt-out. */
    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun isGenerationCurrent(expectedGeneration: Long): Boolean = generation == expectedGeneration

    @Synchronized
    fun get(cells: List<BeaconDbCell>): Hit? = getByKey(cacheKey(cells))

    @Synchronized
    fun getRequest(fingerprint: String): Hit? =
        getByKey(digest("beacondb-multiradio-cache-v2\n" + fingerprint))

    @Synchronized
    fun put(
        cells: List<BeaconDbCell>,
        coordinate: BeaconDbCoordinate,
        queriedAtMillis: Long,
        expectedGeneration: Long? = null
    ) = putByKey(cacheKey(cells), coordinate, queriedAtMillis, expectedGeneration)

    @Synchronized
    fun putRequest(
        fingerprint: String,
        coordinate: BeaconDbCoordinate,
        queriedAtMillis: Long,
        expectedGeneration: Long? = null
    ) = putByKey(digest("beacondb-multiradio-cache-v2\n" + fingerprint), coordinate, queriedAtMillis, expectedGeneration)

    private fun getByKey(key: String?): Hit? {
        if (key == null) return null
        val now = monotonicTimeMillis()
        val entry = entries[key] ?: return null
        val age = now - entry.storedAtMonotonicMillis
        if (age < 0L || age >= ttlMillis) {
            entries.remove(key)
            return null
        }
        return Hit(entry.coordinate, entry.queriedAtMillis, age)
    }

    private fun putByKey(
        key: String?,
        coordinate: BeaconDbCoordinate,
        queriedAtMillis: Long,
        expectedGeneration: Long?
    ) {
        if (expectedGeneration != null && expectedGeneration != generation) return
        if (key == null || !coordinate.isCacheable() || queriedAtMillis <= 0L) return
        val now = monotonicTimeMillis()
        removeExpired(now)
        entries[key] = Entry(coordinate, queriedAtMillis, now)
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        generation += 1L
        entries.clear()
    }

    private fun removeExpired(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val age = now - entry.storedAtMonotonicMillis
            if (age < 0L || age >= ttlMillis) iterator.remove()
        }
    }

    private fun cacheKey(cells: List<BeaconDbCell>): String? {
        val validCells = cells
            .filter { it.isValid() }
            .distinctBy { it.identifier }
            .sortedBy { it.identifier }
        if (validCells.isEmpty()) return null

        // Include signal level in the key so meaningful radio changes don't reuse an old estimate.
        val canonical = "beacondb-cache-v1\n" + validCells.joinToString("\n") { cell ->
            listOf(
                cell.radioType,
                cell.mobileCountryCode.toString(),
                cell.mobileNetworkCode.toString(),
                cell.locationAreaCode.toString(),
                cell.cellId.toString(),
                signalStrengthBucket(cell.signalStrength).toString()
            ).joinToString(":")
        }
        return digest(canonical)
    }

    private fun signalStrengthBucket(value: Int): Int = Math.floorDiv(value, 10) * 10

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun BeaconDbCoordinate.isCacheable(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters > 0.0 &&
            accuracyMeters <= 10_000_000.0

    companion object {
        const val DEFAULT_TTL_MILLIS = 120_000L
        const val DEFAULT_MAX_ENTRIES = 32
    }
}
