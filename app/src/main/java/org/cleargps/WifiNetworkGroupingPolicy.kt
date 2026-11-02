package org.cleargps

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A cautious, explainable association suggestion; it never changes stored records. */
internal data class WifiNetworkCandidate(
    val point: RadioPointRecord,
    val score: Int,
    val reasons: List<String>
)

/** A single map marker may represent several BSSIDs only when Android supplies the same MLD MAC. */
internal data class WifiMapMarker(
    val type: String,
    val pointIds: List<Long>,
    val latitude: Double,
    val longitude: Double
)

internal object WifiNetworkGroupingPolicy {
    private const val MAX_CANDIDATES = 12
    private const val NEARBY_METERS = 150.0
    private const val MAX_USABLE_ACCURACY_METERS = 250.0
    private const val COOBSERVED_WINDOW_MILLIS = 15L * 60L * 1_000L

    fun buildMapMarkers(points: List<RadioPointRecord>): List<WifiMapMarker> {
        val buckets = LinkedHashMap<String, MutableList<RadioPointRecord>>()
        points.forEach { point ->
            val latitude = point.latitude ?: return@forEach
            val longitude = point.longitude ?: return@forEach
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0
            ) return@forEach

            val mld = if (point.type == "wifi") normalizeMac(point.wifiMldMac) else null
            val key = if (mld != null) "wifi-mld:$mld" else "point:${point.id}"
            buckets.getOrPut(key) { ArrayList() }.add(point)
        }

        return buckets.values.mapNotNull { members ->
            val representative = members.minWithOrNull(
                compareBy<RadioPointRecord>(
                    { it.accuracyMeters?.takeIf { value -> value.isFinite() && value > 0.0 } ?: Double.MAX_VALUE },
                    { -it.peakRssi },
                    { -it.lastSeen }
                )
            ) ?: return@mapNotNull null
            WifiMapMarker(
                type = representative.type,
                pointIds = members.map { it.id }.distinct().sorted(),
                latitude = representative.latitude!!,
                longitude = representative.longitude!!
            )
        }
    }

    /**
     * Recommends a manual association only when at least two independent clues agree.
     * SSID-only and vendor-only matches are intentionally ignored to avoid merging neighbours.
     */
    fun suggestCandidates(
        source: RadioPointRecord,
        pool: List<RadioPointRecord>,
        excludedPointIds: Set<Long> = emptySet()
    ): List<WifiNetworkCandidate> {
        if (source.type != "wifi") return emptyList()
        val sourceMac = normalizeMac(source.identifier) ?: return emptyList()
        val sourceOui = oui(sourceMac)

        return pool.asSequence()
            .filter { candidate ->
                candidate.type == "wifi" && candidate.id != source.id &&
                    candidate.id !in excludedPointIds
            }
            .mapNotNull { candidate ->
                val candidateMac = normalizeMac(candidate.identifier) ?: return@mapNotNull null
                if (candidateMac == sourceMac) return@mapNotNull null
                val sourceMld = normalizeMac(source.wifiMldMac)
                val candidateMld = normalizeMac(candidate.wifiMldMac)
                if (sourceMld != null && sourceMld == candidateMld) return@mapNotNull null

                val sameSsid = source.ssid.isNotBlank() &&
                    candidate.ssid.isNotBlank() && source.ssid == candidate.ssid
                val sameVendorPrefix = sourceOui != null && sourceOui == oui(candidateMac)
                val distance = distanceMeters(source, candidate)
                val nearby = distance != null && distance <= NEARBY_METERS
                val coObserved = kotlin.math.abs(source.lastSeen - candidate.lastSeen) <= COOBSERVED_WINDOW_MILLIS

                // A hidden interface is a candidate only with all three independent clues.
                val hiddenInterface = source.ssid.isBlank() || candidate.ssid.isBlank()
                val score = (if (sameSsid) 45 else 0) +
                    (if (sameVendorPrefix) 20 else 0) +
                    (if (nearby) 25 else 0) +
                    (if (coObserved) 15 else 0)
                val enoughEvidence = if (hiddenInterface) {
                    sameVendorPrefix && nearby && coObserved
                } else if (!sameSsid) {
                    // Different visible names may be separate router bands, IoT SSIDs or Mesh backhaul;
                    // only offer them when vendor, location and time all agree.
                    sameVendorPrefix && nearby && coObserved
                } else {
                    score >= 65 && listOf(sameSsid, sameVendorPrefix, nearby, coObserved).count { it } >= 2
                }
                if (!enoughEvidence) return@mapNotNull null

                val reasons = buildList {
                    if (sameSsid) add("Совпадает имя сети (SSID)")
                    if (sameVendorPrefix) add("Совпадает OUI (первые 3 байта MAC)")
                    if (nearby) add("Центры наблюдений близко: ${distance!!.toInt()} м")
                    if (coObserved) add("Точки обнаруживались примерно в одно время")
                    if (hiddenInterface) add("SSID не получен: возможна скрытая сеть")
                }
                WifiNetworkCandidate(candidate, score, reasons)
            }
            .sortedWith(compareByDescending<WifiNetworkCandidate> { it.score }
                .thenByDescending { it.point.lastSeen }
                .thenBy { it.point.id })
            .take(MAX_CANDIDATES)
            .toList()
    }

    private fun distanceMeters(first: RadioPointRecord, second: RadioPointRecord): Double? {
        val lat1 = first.latitude ?: return null
        val lon1 = first.longitude ?: return null
        val lat2 = second.latitude ?: return null
        val lon2 = second.longitude ?: return null
        val accuracy1 = first.accuracyMeters ?: return null
        val accuracy2 = second.accuracyMeters ?: return null
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite() ||
            accuracy1 !in 1.0..MAX_USABLE_ACCURACY_METERS ||
            accuracy2 !in 1.0..MAX_USABLE_ACCURACY_METERS
        ) return null

        val toRadians = Math.PI / 180.0
        val dLat = (lat2 - lat1) * toRadians
        val dLon = (lon2 - lon1) * toRadians
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1 * toRadians) * cos(lat2 * toRadians) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return 6_371_000.0 * 2.0 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    private fun normalizeMac(value: String?): String? {
        val mac = value?.trim()?.lowercase()?.takeIf {
            it.matches(Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))
        } ?: return null
        if (mac == "00:00:00:00:00:00" || mac == "ff:ff:ff:ff:ff:ff") return null
        return mac
    }

    private fun oui(mac: String): String? {
        val firstOctet = mac.substringBefore(':').toIntOrNull(16) ?: return null
        // Local-administered or multicast addresses do not carry a reliable vendor OUI.
        if ((firstOctet and 0x03) != 0) return null
        return mac.take(8).takeIf { it.length == 8 }
    }
}
