package org.cleargps

import org.cleargps.cell.CellEvidenceSource
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSiteCoordinateEvidence
import org.cleargps.cell.CellSiteEvidenceBundle
import org.cleargps.cell.CellTechnology

/**
 * Converts the existing OpenCellID cache to per-cell site evidence without making HTTP calls.
 *
 * OpenCellID keys store MNC as an integer, so they cannot distinguish values such as "01"
 * and "001". A cached coordinate is attributed only when the caller's complete known identity
 * set contains exactly one numeric match. Ambiguous records are deliberately left unused.
 */
internal object OpenCellIdCellSiteEvidenceAdapter {
    fun toEvidenceBundles(
        identities: Collection<CellIdentity>,
        records: Collection<OpenCellIdCellRecord>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<CellSiteEvidenceBundle> {
        require(nowMillis >= 0L)
        val known = identities.filter { it.mncLengthKnown }.distinctBy { it.stableKey }
        val bundles = LinkedHashMap<String, CellSiteEvidenceBundle>()

        records.asSequence()
            .filter { it.status == "found" }
            .sortedWith(compareByDescending<OpenCellIdCellRecord> { it.checkedAtMillis }
                .thenBy { it.key.radio }
                .thenBy { it.key.mcc }
                .thenBy { it.key.mnc }
                .thenBy { it.key.area }
                .thenBy { it.key.cellId })
            .forEach { record ->
                val coordinateUncertainty = OpenCellIdLookupPolicy.coordinateUncertaintyMeters(record, nowMillis)
                    ?: return@forEach
                val latitude = record.latitude ?: return@forEach
                val longitude = record.longitude ?: return@forEach
                val technology = when (record.key.radio) {
                    "GSM" -> CellTechnology.GSM
                    "UMTS" -> CellTechnology.WCDMA
                    "LTE" -> CellTechnology.LTE
                    "NR" -> CellTechnology.NR
                    else -> return@forEach
                }
                val matches = known.filter { identity ->
                    identity.technology == technology &&
                        identity.mcc.toIntOrNull() == record.key.mcc &&
                        identity.mnc.toIntOrNull() == record.key.mnc &&
                        identity.areaCode == record.key.area.toLong() &&
                        identity.cellId == record.key.cellId
                }
                // Numeric OpenCellID MNCs are ambiguous when more than one width-qualified
                // network identity shares the same integer value. Never guess which cell it is.
                if (matches.size != 1) return@forEach
                val identity = matches.single()
                val key = listOf(
                    record.key.radio, record.key.mcc, record.key.mnc,
                    record.key.area, record.key.cellId
                ).joinToString(":")
                val evidence = CellSiteCoordinateEvidence(
                    evidenceId = "opencellid:$key",
                    source = CellEvidenceSource.OPENCELLID,
                    latitude = latitude,
                    longitude = longitude,
                    uncertaintyMeters = coordinateUncertainty,
                    dependencyGroup = "opencellid:$key"
                )
                bundles[identity.stableKey] = CellSiteEvidenceBundle(identity, listOf(evidence))
            }

        return bundles.values.sortedBy { it.identity.stableKey }
    }
}
