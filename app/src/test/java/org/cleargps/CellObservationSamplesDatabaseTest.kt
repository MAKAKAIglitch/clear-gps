package org.cleargps

import android.content.ContentValues
import android.content.Context
import org.cleargps.cell.CellIdentity
import org.cleargps.cell.CellSignalMetadata
import org.cleargps.cell.CellSiteGroup
import org.cleargps.cell.SectorAzimuthEstimator
import org.cleargps.cell.CellTechnology
import org.cleargps.cell.CellTimingAdvanceRangePolicy
import org.cleargps.cell.CellTimingAdvanceSiteEstimator
import org.cleargps.cell.ObservationLocationOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

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
            channelNumber = 1800, pci = 111, rsrpDbm = -65, rsrqDb = -10, sinrDb = 15,
            timingAdvanceValue = 100, timingAdvanceUnit = "lte-command-index",
            timingAdvancePositionDeltaMillis = 500L
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
        assertEquals(100, sample.timingAdvanceValue)
        assertEquals("lte-command-index", sample.timingAdvanceUnit)
        assertEquals(500L, sample.timingAdvancePositionDeltaMillis)
        assertTrue(sample.usableForSector())
        assertTrue(sample.usableForTimingAdvance())
    }

    @Test
    fun timingAdvanceWithoutSynchronizedMeasuredLocationIsNotUsable() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.LTE, "250", "01", 1024, 123456L
        )!!
        insertObservation(
            localSource.id, "lte:250:01:1024:123456", "gps", 51.5, -0.12,
            CellSignalMetadata(
                CellTechnology.LTE, "250", "01", true, 1024, 123456L,
                channelNumber = 1800,
                timingAdvanceValue = 20,
                timingAdvanceUnit = "lte-command-index"
            )
        )

        val sample = database.listCellObservationSamples(identity).single()

        assertEquals(20, sample.timingAdvanceValue)
        assertEquals(null, sample.timingAdvancePositionDeltaMillis)
        assertFalse(sample.usableForTimingAdvance())
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
    fun batchQueryReturnsOnlySynchronizedLocalLteTimingAdvanceSamples() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val importedSource = database.writableDatabase.insertOrThrow(
            "radio_sources",
            null,
            ContentValues().apply {
                put("kind", "wigle")
                put("name", "Imported TA")
                put("created_at", 1_700_000_000_000L)
                put("last_seen", 1_700_000_000_000L)
                put("enabled", 1)
                put("trust_weight", 1.0)
            }
        )
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.LTE, "250", "01", 1024, 987700L
        )!!
        val valid = CellSignalMetadata(
            CellTechnology.LTE, "250", "01", true, 1024, 987700L,
            channelNumber = 1800,
            timingAdvanceValue = 20,
            timingAdvanceUnit = "lte-command-index",
            timingAdvancePositionDeltaMillis = 300L
        )
        insertObservation(
            localSource.id, "lte:250:01:1024:987700", "gps", 51.5, -0.12, valid
        )
        insertObservation(
            localSource.id, "lte:250:01:1024:987700", "gps", 51.5001, -0.1201,
            valid.copy(timingAdvancePositionDeltaMillis = null)
        )
        insertObservation(
            importedSource, "lte:250:01:1024:987700", "wigle-import", 51.5002, -0.1202, valid
        )

        val samples = database.listCellTimingAdvanceSamples()

        assertEquals(1, samples.size)
        assertEquals(identity.stableKey, samples.single().identity.stableKey)
        assertTrue(samples.single().usableForTimingAdvance())
    }

    @Test
    fun sectorBatchQueryReturnsOnlyOriginalLocalSamplesWithKnownCarrier() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        insertObservation(
            localSource.id,
            "lte:250:01:1024:987702",
            "gps",
            51.5,
            -0.12,
            CellSignalMetadata(
                CellTechnology.LTE, "250", "01", true, 1024, 987702L,
                channelNumber = 1800,
                pci = 111,
                rsrpDbm = -65
            )
        )

        val samples = database.listCellSectorObservationSamples()

        assertEquals(1, samples.size)
        assertEquals(1800, samples.single().frequencyKey)
        assertEquals("local-device", samples.single().receiverProfileId)
        assertTrue(samples.single().usableForSector())
    }

    @Test
    fun historicalLocalSignalPatternProducesStoredMapEligibleSector() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.LTE, "250", "01", 1024, 987703L
        )!!
        val siteLatitude = 51.5
        val siteLongitude = -0.12
        val group = CellSiteGroup(
            stableKey = "site:${identity.stableKey}",
            members = listOf(identity),
            latitude = siteLatitude,
            longitude = siteLongitude,
            uncertaintyRadiusMeters = 10.0,
            confidenceScore = 0.85,
            lockedByUser = false
        )
        database.saveCellSiteGroups(listOf(group), calculatedAtMillis = 1_700_000_000_000L)

        var observationIndex = 0
        for (bearing in 0 until 360 step 10) {
            for (distance in listOf(300.0, 450.0, 600.0)) {
                val gain = if (angularDifference(bearing.toDouble(), 80.0) <= 65.0) 16.0 else 0.0
                val coordinate = destination(siteLatitude, siteLongitude, bearing.toDouble(), distance)
                val rssi = (-45.0 - 30.0 * log10(distance) + gain).roundToInt()
                insertObservation(
                    localSource.id,
                    "lte:250:01:1024:987703",
                    "gps",
                    coordinate.first,
                    coordinate.second,
                    CellSignalMetadata(
                        CellTechnology.LTE, "250", "01", true, 1024, 987703L,
                        channelNumber = 1800,
                        pci = 111,
                        rsrpDbm = rssi
                    ),
                    observedAtMillis = 1_700_000_000_100L + observationIndex * 1_000L,
                    accuracyMeters = 8.0,
                    rssiDbm = rssi
                )
                observationIndex++
            }
        }

        val stored = RadioRepository(database).recalculateCellSiteSectors(
            calculatedAtMillis = 1_700_000_200_000L
        )

        assertEquals(1, stored.size)
        val sector = stored.single()
        assertEquals(identity.stableKey, sector.identity.stableKey)
        assertTrue(angularDifference(sector.azimuthDegrees, 80.0) < 20.0)
        assertTrue(sector.sampleCount >= 18)
        assertTrue(sector.confidenceScore >= 0.60)
        assertTrue(sector.modelDirectionSpreadDegrees <= 30.0)
        assertEquals(1800, sector.frequencyKeys.single())
    }

    @Test
    fun timingAdvanceHistoryCreatesAndPersistsPhysicalSiteWithoutNetworkOrNewGps() {
        val localSource = database.listRadioSources().single { it.kind == "local" }
        val identity = CellIdentity.fromAndroidStrings(
            CellTechnology.LTE, "250", "01", 1024, 987701L
        )!!
        val siteLatitude = 51.5007
        val siteLongitude = -0.1246
        val baseTime = 1_700_000_000_000L

        repeat(16) { index ->
            val bearing = Math.toRadians(index * 22.5)
            val distance = 600.0 + (index % 4) * 180.0
            val angular = distance / 6_371_008.8
            val lat0 = Math.toRadians(siteLatitude)
            val lon0 = Math.toRadians(siteLongitude)
            val latitude = Math.toDegrees(
                kotlin.math.asin(
                    kotlin.math.sin(lat0) * kotlin.math.cos(angular) +
                        kotlin.math.cos(lat0) * kotlin.math.sin(angular) * kotlin.math.cos(bearing)
                )
            )
            val longitude = Math.toDegrees(
                lon0 + kotlin.math.atan2(
                    kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat0),
                    kotlin.math.cos(angular) - kotlin.math.sin(lat0) * kotlin.math.sin(Math.toRadians(latitude))
                )
            )
            val timingAdvance = (distance / CellTimingAdvanceRangePolicy.LTE_ONE_WAY_STEP_METERS)
                .roundToInt()
            val metadata = CellSignalMetadata(
                CellTechnology.LTE, "250", "01", true, 1024, 987701L,
                channelNumber = 1800,
                pci = 111,
                rsrpDbm = -65,
                timingAdvanceValue = timingAdvance,
                timingAdvanceUnit = "lte-command-index",
                timingAdvancePositionDeltaMillis = 100L
            )
            insertObservation(
                localSource.id,
                "lte:250:01:1024:987701",
                "gps",
                latitude,
                longitude,
                metadata,
                observedAtMillis = baseTime + index * 1_000L
            )
        }

        val rawSamples = database.listCellTimingAdvanceSamples()
        assertEquals(16, rawSamples.size)
        assertNotNull(CellTimingAdvanceSiteEstimator.estimate(identity, rawSamples))

        val result = RadioRepository(database).calculateAndSaveCachedOpenCellIdCellSites(
            nowMillis = baseTime + 20_000L
        )

        assertEquals(1, result.groups.size)
        val saved = database.listCellSites().single()
        assertEquals(identity.stableKey, saved.members.single().stableKey)
        assertTrue(saved.uncertaintyMeters in 1.0..250.0)
        assertTrue(saved.confidenceScore >= 0.45)
        assertFalse(saved.lockedByUser)
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

    private fun destination(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ): Pair<Double, Double> {
        val angular = distanceMeters / 6_371_008.8
        val bearing = Math.toRadians(bearingDegrees)
        val lat0 = Math.toRadians(latitude)
        val lon0 = Math.toRadians(longitude)
        val lat = kotlin.math.asin(
            kotlin.math.sin(lat0) * kotlin.math.cos(angular) +
                kotlin.math.cos(lat0) * kotlin.math.sin(angular) * kotlin.math.cos(bearing)
        )
        val lon = lon0 + kotlin.math.atan2(
            kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat0),
            kotlin.math.cos(angular) - kotlin.math.sin(lat0) * kotlin.math.sin(lat)
        )
        return Math.toDegrees(lat) to (((Math.toDegrees(lon) + 540.0) % 360.0) - 180.0)
    }

    private fun angularDifference(first: Double, second: Double): Double {
        val delta = kotlin.math.abs((first - second + 540.0) % 360.0 - 180.0)
        return delta
    }

    private fun log10(value: Double): Double = kotlin.math.ln(value) / kotlin.math.ln(10.0)

    private fun insertObservation(
        sourceId: Long,
        identifier: String,
        positionSource: String,
        latitude: Double,
        longitude: Double,
        metadata: CellSignalMetadata,
        observedAtMillis: Long = 1_700_000_000_000L,
        accuracyMeters: Double = 12.0,
        rssiDbm: Int = -65
    ) {
        val observationId = database.writableDatabase.insertOrThrow(
            "radio_observations",
            null,
            ContentValues().apply {
                put("radio_type", "cell")
                put("identifier", identifier)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracyMeters)
                put("rssi", rssiDbm)
                put("frequency", 1800)
                put("observed_at", observedAtMillis)
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
                put("timing_advance_position_delta_ms", metadata.timingAdvancePositionDeltaMillis)
            }
        )
    }
}
