package org.cleargps.cell

enum class CellTechnology(val token: String) {
    GSM("gsm"), WCDMA("wcdma"), LTE("lte"), NR("nr");

    companion object {
        fun fromToken(value: String): CellTechnology? =
            entries.firstOrNull { it.token.equals(value.trim(), ignoreCase = true) }
    }
}


/** Raw modem metadata. Channel numbers and timing advance remain in their native units. */
data class CellSignalMetadata(
    val technology: CellTechnology,
    val mcc: String,
    val mnc: String,
    val mncLengthKnown: Boolean,
    val areaCode: Long,
    val cellId: Long,
    val channelNumber: Int? = null,
    val bsic: Int? = null,
    val psc: Int? = null,
    val pci: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
    val rscpDbm: Int? = null,
    val ecNoDb: Int? = null,
    val timingAdvanceValue: Int? = null,
    val timingAdvanceUnit: String? = null
) {
    init {
        require(mcc.matches(Regex("^[0-9]{3}$")))
        require(mnc.matches(if (mncLengthKnown) Regex("^[0-9]{2,3}$") else Regex("^[0-9]{1,3}$")))
        require(areaCode >= 0L && cellId > 0L)
        require(channelNumber == null || channelNumber >= 0)
        require(bsic == null || (technology == CellTechnology.GSM && bsic in 0..63))
        require(psc == null || (technology == CellTechnology.WCDMA && psc in 0..511))
        require(pci == null || when (technology) {
            CellTechnology.LTE -> pci in 0..503
            CellTechnology.NR -> pci in 0..1007
            else -> false
        })
        require(rsrpDbm == null || rsrpDbm in -160..0)
        require(rsrqDb == null || rsrqDb in -60..30)
        require(sinrDb == null || sinrDb in -40..60)
        require(rscpDbm == null || rscpDbm in -140..0)
        require(ecNoDb == null || ecNoDb in -40..10)
        require((timingAdvanceValue == null) == (timingAdvanceUnit == null))
        if (timingAdvanceValue != null) {
            require(timingAdvanceValue >= 0)
            require(
                (technology == CellTechnology.GSM && timingAdvanceUnit == "gsm-symbols") ||
                    (technology == CellTechnology.LTE && timingAdvanceUnit == "lte-command-index") ||
                    (technology == CellTechnology.NR && timingAdvanceUnit == "nr-command-index")
            )
        }
    }

    fun toIdentity(): CellIdentity = CellIdentity(
        technology = technology,
        mcc = mcc,
        mnc = mnc,
        areaCode = areaCode,
        cellId = cellId,
        mncLengthKnown = mncLengthKnown
    )
}

enum class CellIdentityMatch { EXACT, POSSIBLE_MNC_LENGTH_MATCH, DIFFERENT }

data class CellIdentity(
    val technology: CellTechnology,
    val mcc: String,
    val mnc: String,
    val areaCode: Long,
    val cellId: Long,
    val mncLengthKnown: Boolean = true
) {
    init {
        require(mcc.matches(Regex("^[0-9]{3}$")))
        require(mnc.matches(if (mncLengthKnown) Regex("^[0-9]{2,3}$") else Regex("^[0-9]{1,3}$")))
        require(areaCode >= 0L && cellId > 0L)
    }

    val stableKey: String
        get() = listOf(
            technology.token, mcc, mnc, areaCode.toString(), cellId.toString(),
            if (mncLengthKnown) "known" else "legacy-width-unknown"
        ).joinToString(":")

    fun compare(other: CellIdentity): CellIdentityMatch {
        if (technology != other.technology || mcc != other.mcc ||
            areaCode != other.areaCode || cellId != other.cellId
        ) return CellIdentityMatch.DIFFERENT

        if (mncLengthKnown && other.mncLengthKnown) {
            return if (mnc == other.mnc) CellIdentityMatch.EXACT else CellIdentityMatch.DIFFERENT
        }
        return if (mnc.toIntOrNull() == other.mnc.toIntOrNull()) {
            CellIdentityMatch.POSSIBLE_MNC_LENGTH_MATCH
        } else CellIdentityMatch.DIFFERENT
    }

    companion object {
        fun fromAndroidStrings(
            technology: CellTechnology,
            mcc: String?,
            mnc: String?,
            areaCode: Int,
            cellId: Long
        ): CellIdentity? {
            val rawMcc = mcc?.trim() ?: return null
            val rawMnc = mnc?.trim() ?: return null
            if (!rawMcc.matches(Regex("^[0-9]{3}$")) ||
                !rawMnc.matches(Regex("^[0-9]{2,3}$")) ||
                areaCode < 0 || cellId <= 0L
            ) return null
            return CellIdentity(technology, rawMcc, rawMnc, areaCode.toLong(), cellId, true)
        }

        fun fromLegacyIdentifier(identifier: String): CellIdentity? {
            val parts = identifier.split(':')
            if (parts.size != 5) return null
            val technology = CellTechnology.fromToken(parts[0]) ?: return null
            val mcc = parts[1]
            val mnc = parts[2]
            val area = parts[3].toLongOrNull() ?: return null
            val cell = parts[4].toLongOrNull() ?: return null
            if (!mcc.matches(Regex("^[0-9]{3}$")) ||
                !mnc.matches(Regex("^[0-9]{1,3}$")) ||
                area < 0L || cell <= 0L
            ) return null

            // The old Android parser stored MNC as Int, so even a two-digit value may have lost
            // an original leading zero. Legacy identifiers therefore cannot prove MNC width.
            return runCatching {
                CellIdentity(technology, mcc, mnc, area, cell, mncLengthKnown = false)
            }.getOrNull()
        }
    }
}

enum class ObservationLocationOrigin {
    OBSERVED, IMPORTED, USER_CONFIRMED, DERIVED_ESTIMATE, EXTERNAL_CATALOGUE
}

data class CellObservationSample(
    val identity: CellIdentity,
    val latitude: Double,
    val longitude: Double,
    val coordinateAccuracyMeters: Double,
    val rssiDbm: Double,
    val frequencyKey: Int = 0,
    val observedAtMillis: Long,
    val locationOrigin: ObservationLocationOrigin,
    val receiverProfileId: String? = null,
    val sourceGroupId: String? = null
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(coordinateAccuracyMeters.isFinite() && coordinateAccuracyMeters > 0.0)
        require(rssiDbm.isFinite() && rssiDbm in -160.0..0.0)
        require(frequencyKey >= 0 && observedAtMillis >= 0L)
    }

    fun usableForSector(maximumAccuracyMeters: Double = 1_000.0): Boolean =
        identity.mncLengthKnown &&
            !receiverProfileId.isNullOrBlank() &&
            locationOrigin in setOf(
                ObservationLocationOrigin.OBSERVED,
                ObservationLocationOrigin.IMPORTED,
                ObservationLocationOrigin.USER_CONFIRMED
            ) &&
            coordinateAccuracyMeters <= maximumAccuracyMeters
}

enum class CellEvidenceSource(val defaultReliability: Double) {
    USER_VERIFIED(1.0), OPENCELLID(0.55), IMPORTED_CATALOGUE(0.45),
    OSM_COMMUNICATIONS_STRUCTURE(0.22), OTHER_EXTERNAL(0.30)
}

data class CellSiteCoordinateEvidence(
    val evidenceId: String,
    val source: CellEvidenceSource,
    val latitude: Double,
    val longitude: Double,
    val uncertaintyMeters: Double,
    val reliability: Double = source.defaultReliability,
    val dependencyGroup: String? = null,
    val lockedByUser: Boolean = false
) {
    init {
        require(evidenceId.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(uncertaintyMeters.isFinite() && uncertaintyMeters > 0.0)
        require(reliability.isFinite() && reliability in 0.0..1.0)
    }
}

data class CellSiteCoordinateEstimate(
    val latitude: Double,
    val longitude: Double,
    val uncertaintyRadiusMeters: Double,
    val confidenceScore: Double,
    val independentEvidenceCount: Int,
    val acceptedEvidenceIds: List<String>,
    val rejectedEvidenceIds: List<String>,
    val ignoredCorrelatedEvidenceIds: List<String>,
    val conflictDetected: Boolean,
    val lockedByUser: Boolean,
    val algorithmVersion: String = "cell-site-position-1"
)
