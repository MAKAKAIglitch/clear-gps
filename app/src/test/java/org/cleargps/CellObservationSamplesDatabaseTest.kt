package org.cleargps

import android.content.ContentValues
import android.content.Context
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSignalMetadata
import org.cleargps.cell.CellTechnology
import org.cleargps.cell.ObservationLocationOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CellObservationSamplesDatabaseTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val database = LocalRadioDatabase(context, null)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun loadsOnlyOriginalTrustedLocatedObservationsForExactIdentity() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val externalSourceId = database.writableDatabase.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "beacondb")
                put("name", "External lookup")
                put("created_at", 1_700_000_000_000L)
                put("last_seen", 1_700_000_000_000L)
                put("enabled", 1)
                put("trust_weight", 1.0)
            }
        )
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.LTE, "250", "01", 1024, 987654L
        )!!

        insertObservation(localSource.id, "lte:250:01:1024:987654", "gps", 51.5, -0.12, CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987654L,
            channelNumber = 1800, pci = 111, rsrpDbm = -65, rsrqDb = -10, sinrDb = 15
        ))
        insertObservation(localSource.id, "lte:250:01:1024:987654", "radio-estimate", 51.6, -0.13, CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987654L,
            channelNumber = 1800, pci = 111, rsrpDbm = -65, rsrqDb = -10, sinrDb = 15
        ))
        insertObservation(localSource.id, "lte:250:01:1024:987654", "manual-edit", 51.7, -0.14, CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987654L,
            channelNumber = 1800, pci = 111, rsrpDbm = -65, rsrqDb = -10, sinrDb = 15
        ))
        insertObservation(localSource.id, "lte:250:001:1024:987654", "gps", 51.8, -0.15,
            CellSignalMetadata(CellTechnology.LTE, "250", "001", true, 1024, 987654L, channelNumber = 1801, pci = 111))
        insertObservation(externalSourceId, "lte:250:01:1024:987654", "gps", 51.9, -0.16, CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987654L,
            channelNumber = 1800, pci = 111, rsrpDbm = -65, rsrqDb = -10, sinrDb = 15
        ))

        val samples = database.listCellObservationSamples(identity)

        assertEquals(1, samples.size)
        val sample = samples.single()
        assertEquals(51.5, sample.latitude, 0.0)
        assertEquals(-0.12, sample.longitude, 0.0)
        assertEquals(1800, sample.frequencyKey)
        assertEquals(ObservationLocationOrigin.OBSERVED, sample.locationOrigin)
        assertEquals("local-device", sample.receiverProfileId)
        assertTrue(sample.usableForSector())
    }

    @Test
    fun importedObservationsAreRetainedAsEvidenceButDoNotClaimAReceiverProfile() {
        val importedSourceId = database.writableDatabase.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "wigle")
                put("name", "Historical radio import")
                put("created_at", 1_700_000_000_000L)
                put("last_seen", 1_700_000_000_000L)
                put("enabled", 1)
                put("trust_weight", 1.0)
            }
        )
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.GSM, "250", "01", 7, 12345L
        )!!
        insertObservation(importedSourceId, "gsm:250:01:7:12345", "wigle-import", 51.5, -0.12,
            CellSignalMetadata(CellTechnology.GSM, "250", "01", true, 7, 12345L, channelNumber = 62, bsic = 12))

        val sample = database.listCellObservationSamples(identity).single()

        assertEquals(ObservationLocationOrigin.IMPORTED, sample.locationOrigin)
        assertEquals(null, sample.receiverProfileId)
        assertFalse(sample.usableForSector())
        assertTrue(sample.sourceGroupId!!.startsWith("wigle:"))
    }

    @Test
    fun listsExactAndLegacyMncIdentitiesFromTrustedHistoryForCatalogMatching() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val externalSourceId = database.writableDatabase.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "beacondb")
                put("name", "External-only identity")
                put("created_at", 1_700_000_000_000L)
                put("last_seen", 1_700_000_000_000L)
                put("enabled", 1)
                put("trust_weight", 1.0)
            }
        )

        val twoDigit = CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987654L, channelNumber = 1800
        )
        val threeDigit = CellSignalMetadata(
            CellTechnology.LTE, "250", "001", true, 1024, 987655L, channelNumber = 1801
        )
        val legacyWidthUnknown = CellSignalMetadata(
            CellTechnology.LTE, "250", "1", false, 1024, 987656L, channelNumber = 1802
        )
        val externalOnly = CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987657L, channelNumber = 1803
        )

        insertObservation(localSource.id, "lte:250:01:1024:987654", "gps", 51.5, -0.12, twoDigit)
        insertObservation(localSource.id, "lte:250:001:1024:987655", "gps", 51.5, -0.12, threeDigit)
        insertObservation(
            localSource.id, "lte:250:1:1024:987656", "legacy-import", 51.5, -0.12, legacyWidthUnknown
        )
        insertObservation(
            externalSourceId, "lte:250:01:1024:987657", "gps", 51.5, -0.12, externalOnly
        )

        val identities = database.listCellIdentitiesWithObservationHistory()
        val byCellId = identities.associateBy { it.cellId }

        assertEquals(3, identities.size)
        assertEquals("01", byCellId.getValue(987654L).mnc)
        assertTrue(byCellId.getValue(987654L).mncLengthKnown)
        assertEquals("001", byCellId.getValue(987655L).mnc)
        assertTrue(byCellId.getValue(987655L).mncLengthKnown)
        assertEquals("1", byCellId.getValue(987656L).mnc)
        assertFalse(byCellId.getValue(987656L).mncLengthKnown)
        assertFalse(byCellId.containsKey(987657L))
    }

    @Test
    fun cachedOpenCellIdCandidatesAreCalculatedAndSavedWithoutNetwork() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val now = 1_700_000_000_000L
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.LTE, "250", "01", 1024, 987654L
        )!!
        insertObservation(
            localSource.id,
            "lte:250:01:1024:987654",
            "gps",
            51.5,
            -0.12,
            CellSignalMetadata(
                CellTechnology.LTE, "250", "01", true, 1024, 987654L,
                channelNumber = 1800, pci = 111, rsrpDbm = -65, rsrqDb = -10, sinrDb = 15
            )
        )
        database.writableDatabase.insertOrThrow(
            "opencellid_cells",
            null,
            ContentValues().apply {
                put("radio", "LTE")
                put("mcc", 250)
                put("mnc", 1)
                put("area", 1024)
                put("cell_id", 987654L)
                put("latitude", 51.501)
                put("longitude", -0.121)
                put("range_m", 1_500)
                put("samples", 12)
                put("changeable", 0)
                put("checked_at", now)
                put("status", "found")
            }
        )

        val repository = RadioRepository(database)
        val result = repository.calculateAndSaveCachedOpenCellIdCellSites(nowMillis = now)

        assertEquals(1, result.groups.size)
        val persisted = database.listCellSites().single()
        assertEquals(identity.stableKey, persisted.members.single().stableKey)
        assertTrue(persisted.uncertaintyMeters >= OpenCellIdLookupPolicy.MIN_CELL_COORDINATE_UNCERTAINTY_METERS)
        assertTrue(persisted.confidenceScore >= 0.18)
        assertTrue(persisted.confidenceScore < 0.25)
        assertFalse(persisted.lockedByUser)

        val originalId = persisted.id
        repository.calculateAndSaveCachedOpenCellIdCellSites(nowMillis = now + 1_000L)
        assertEquals(originalId, database.listCellSites().single().id)
        assertEquals(2, database.listCellSiteBatches().size)
    }

    private fun insertObservation(
        sourceId: Long,
        identifier: String,
        positionSource: String,
        latitude: Double,
        longitude: Double,
        metadata: CellSignalMetadata
    ) {
        val observationId = database.writableDatabase.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "cell")
                put("identifier", identifier)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", 12.0)
                put("rssi", -65)
                put("frequency", 1800)
                put("observed_at", 1_700_000_000_000L)
                put("position_source", positionSource)
                put("source_id", sourceId)
            }
        )
        database.writableDatabase.insertOrThrow(
            "cell_observation_details",
            null,
            ContentValues().apply {
                put("observation_id", observationId)
                put("rat", metadata.technology.token)
                put("mcc", metadata.mcc)
                put("mnc", metadata.mnc)
                put("mnc_length_known", if (metadata.mncLengthKnown) 1 else 0)
                put("area_code", metadata.areaCode)
                put("cell_id", metadata.cellId)
                put("channel_number", metadata.channelNumber)
                put("bsic", metadata.bsic)
                put("psc", metadata.psc)
                put("pci", metadata.pci)
                put("rsrp_dbm", metadata.rsrpDbm)
                put("rsrq_db", metadata.rsrqDb)
                put("sinr_db", metadata.sinrDb)
                put("rscp_dbm", metadata.rscpDbm)
                put("ecno_db", metadata.ecNoDb)
                put("timing_advance_value", metadata.timingAdvanceValue)
                put("timing_advance_unit", metadata.timingAdvanceUnit)
            }
        )
    }
}
