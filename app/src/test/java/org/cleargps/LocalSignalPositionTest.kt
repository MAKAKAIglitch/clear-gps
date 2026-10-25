package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

class LocalSignalPositionTest {
    @Test fun strongerSignalMustBeatStoredPeakByThreeDb() {
        assertTrue(isBetterSignalSample(-55, -59, 8.0))
        assertFalse(isBetterSignalSample(-57, -59, 8.0))
    }

    @Test fun weakOrInvalidPositionFixNeverPromotesPoint() {
        assertFalse(isBetterSignalSample(-45, -60, 51.0))
        assertFalse(isBetterSignalSample(-45, -60, 0.0))
        assertFalse(isBetterSignalSample(-45, -60, Double.NaN))
    }

    @Test fun observationCentreUsesLocatedSamplesAndIgnoresMissingCoordinates() {
        val centre = estimateObservationCenter(listOf(
            RadioPointObservation(1, 1000L, 55.000, 40.000, 10.0, -50, 2412, "gps"),
            RadioPointObservation(2, 2000L, 55.002, 40.002, 10.0, -60, 2412, "gps"),
            RadioPointObservation(3, 3000L, null, null, null, -70, 2412, "unlocated")
        ))
        requireNotNull(centre)
        assertEquals(2, centre.sampleCount)
        assertTrue(abs(centre.latitude - 55.001) < 0.001)
        assertTrue(abs(centre.longitude - 40.001) < 0.001)
        assertTrue(centre.accuracyMeters > 0.0)
    }

    @Test fun observationCentreHandlesTheInternationalDateLine() {
        val centre = estimateObservationCenter(listOf(
            RadioPointObservation(1, 1000L, 0.0, 179.99, 20.0, -50, 0, "gps"),
            RadioPointObservation(2, 2000L, 0.0, -179.99, 20.0, -55, 0, "gps")
        ))
        requireNotNull(centre)
        assertTrue(abs(centre.longitude) > 179.9)
    }

    @Test fun observationCentreIsNullWhenNoCoordinatesWereRecorded() {
        assertNull(estimateObservationCenter(listOf(
            RadioPointObservation(1, 1000L, null, null, null, -50, 0, "unlocated")
        )))
    }

    @Test fun fingerprintSearchPrefersTheMatchingPlaceOverAnOutlierFingerprint() {
        val now = 1_000_000L
        val current = listOf(
            RadioSignal("wifi", "ap-a", -50),
            RadioSignal("wifi", "ap-b", -60),
            RadioSignal("wifi", "ap-c", -65)
        )
        fun sample(id: String, lat: Double, lon: Double, rssi: Int, at: Long, source: String = "android-location") =
            RadioFingerprintSample("wifi", id, lat, lon, 10.0, rssi, at, source)

        val matchingPlace = listOf(
            sample("ap-a", 55.0000, 40.0000, -51, now - 1_000),
            sample("ap-b", 55.0000, 40.0000, -61, now - 1_000),
            sample("ap-c", 55.0000, 40.0000, -66, now - 1_000),
            sample("ap-d", 55.0000, 40.0000, -72, now - 1_000)
        )
        val weakerPlace = listOf(
            sample("ap-a", 55.1000, 40.1000, -85, now - 2_000),
            sample("ap-b", 55.1000, 40.1000, -92, now - 2_000),
            sample("other-1", 55.1000, 40.1000, -45, now - 2_000),
            sample("other-2", 55.1000, 40.1000, -50, now - 2_000),
            sample("other-3", 55.1000, 40.1000, -53, now - 2_000),
            sample("other-4", 55.1000, 40.1000, -58, now - 2_000)
        )

        val estimate = estimateRadioFingerprintPosition(current, matchingPlace + weakerPlace, now)
        requireNotNull(estimate)
        assertTrue(abs(estimate.latitude - 55.0) < 0.002)
        assertTrue(abs(estimate.longitude - 40.0) < 0.002)
        assertEquals(3, estimate.matchedCount)
        assertFalse(estimate.fallbackUsed)
    }

    @Test fun fingerprintSearchRefusesIdenticalFingerprintsAtDistantPlaces() {
        val now = 1_000_000L
        val current = listOf(
            RadioSignal("wifi", "same-a", -50),
            RadioSignal("wifi", "same-b", -60),
            RadioSignal("wifi", "same-c", -65)
        )
        val history = listOf(55.0 to 40.0, 55.1 to 40.1).flatMapIndexed { index, (lat, lon) ->
            listOf(
                RadioFingerprintSample("wifi", "same-a", lat, lon, 10.0, -50, now - index * 1_000L, "android-location"),
                RadioFingerprintSample("wifi", "same-b", lat, lon, 10.0, -60, now - index * 1_000L, "android-location"),
                RadioFingerprintSample("wifi", "same-c", lat, lon, 10.0, -65, now - index * 1_000L, "android-location")
            )
        }

        assertNull(estimateRadioFingerprintPosition(current, history, now))
        assertTrue(lookupRadioFingerprintPosition(current, history, now).ambiguous)
    }

    @Test fun fingerprintSearchIgnoresUnlocatedAndSelfEstimatedSamples() {
        val now = 1_000_000L
        val current = listOf(
            RadioSignal("wifi", "ap-a", -50),
            RadioSignal("wifi", "ap-b", -60)
        )
        val history = listOf(
            RadioFingerprintSample("wifi", "ap-a", 55.0, 40.0, 10.0, -50, now - 1_000, "unlocated"),
            RadioFingerprintSample("wifi", "ap-b", 55.0, 40.0, 10.0, -60, now - 1_000, "local-radio-estimate")
        )

        assertNull(estimateRadioFingerprintPosition(current, history, now))
    }

    @Test fun radioAnchorAgePenaltyGrowsAndDiffersByRadioType() {
        val day = 24L * 60L * 60L * 1_000L
        val wifiOneDay = radioAnchorAgePenaltyMeters("wifi", day)
        val wifiTwoYears = radioAnchorAgePenaltyMeters("wifi", 730L * day)
        val cellTwoYears = radioAnchorAgePenaltyMeters("cell", 730L * day)
        val bleOneDay = radioAnchorAgePenaltyMeters("ble", day)

        assertTrue(wifiOneDay > 0.0)
        assertTrue(wifiTwoYears > wifiOneDay)
        assertTrue(cellTwoYears > 0.0)
        assertTrue(bleOneDay > wifiOneDay)
        assertEquals(750.0, radioAnchorAgePenaltyMeters("wifi", 20L * 365L * day), 0.0)
        assertEquals(300.0, radioAnchorAgePenaltyMeters("ble", 20L * 365L * day), 0.0)
    }

    @Test fun oldWifiAnchorsRemainUsableButIncreaseUncertainty() {
        val now = 1_000_000L
        fun point(id: Long, identifier: String, lat: Double, lon: Double) =
            RadioPointRecord(
                id = id, type = "wifi", identifier = identifier, label = "", ssid = "", notes = "",
                latitude = lat, longitude = lon, accuracyMeters = 100.0,
                rssi = -60, peakRssi = -55, frequency = 2412,
                firstSeen = now - 10_000, lastSeen = now - 1_000, sightings = 4,
                positionSource = "wigle-import"
            )
        val matches = listOf(
            RadioSignal("wifi", "old-a", -60) to point(1, "old-a", 55.0000, 40.0000),
            RadioSignal("wifi", "old-b", -62) to point(2, "old-b", 55.0002, 40.0001),
            RadioSignal("wifi", "old-c", -59) to point(3, "old-c", 54.9999, 40.0002),
            RadioSignal("wifi", "old-d", -65) to point(4, "old-d", 55.0001, 39.9999)
        )
        val fresh = requireNotNull(estimateRadioAnchorPosition(matches, now))
        val twoYearsAgo = 730L * 24L * 60L * 60L * 1_000L
        val oldMatches = matches.map { (signal, record) ->
            signal to record.copy(lastSeen = now - twoYearsAgo)
        }
        val old = requireNotNull(estimateRadioAnchorPosition(oldMatches, now))

        assertTrue(abs(old.latitude - fresh.latitude) < 0.002)
        assertTrue(abs(old.longitude - fresh.longitude) < 0.002)
        assertTrue(old.accuracyMeters > fresh.accuracyMeters)
    }

    @Test fun bleAnchorsExpireEarlierThanWifiAnchors() {
        val day = 24L * 60L * 60L * 1_000L
        assertTrue(radioAnchorMaxAgeMillis("wifi") >= 5L * 365L * day)
        assertEquals(180L * day, radioAnchorMaxAgeMillis("ble"))
        assertEquals(0L, radioAnchorMaxAgeMillis("unknown"))
    }

    @Test fun anchorEstimatorRejectsAHighSignalSpatialOutlier() {
        val now = 1_000_000L
        fun point(id: Long, identifier: String, lat: Double, lon: Double, accuracy: Double, rssi: Int, source: String = "recorded") =
            RadioPointRecord(
                id = id, type = "wifi", identifier = identifier, label = "", ssid = "", notes = "",
                latitude = lat, longitude = lon, accuracyMeters = accuracy,
                rssi = rssi, peakRssi = rssi, frequency = 2412,
                firstSeen = now - 5_000, lastSeen = now - 1_000, sightings = 4, positionSource = source
            )
        val matches = listOf(
            RadioSignal("wifi", "near-a", -60) to point(1, "near-a", 55.0000, 40.0000, 100.0, -60),
            RadioSignal("wifi", "near-b", -62) to point(2, "near-b", 55.0002, 40.0001, 100.0, -62),
            RadioSignal("wifi", "near-c", -59) to point(3, "near-c", 54.9999, 40.0002, 100.0, -59),
            RadioSignal("wifi", "near-d", -65) to point(4, "near-d", 55.0001, 39.9999, 100.0, -65),
            RadioSignal("wifi", "outlier", -40) to point(5, "outlier", 55.1, 40.1, 20.0, -40, "manual-edit")
        )

        val estimate = estimateRadioAnchorPosition(matches, now)
        requireNotNull(estimate)
        assertTrue(abs(estimate.latitude - 55.0) < 0.002)
        assertTrue(abs(estimate.longitude - 40.0) < 0.002)
        assertTrue(estimate.accuracyMeters > 80.0)
        assertTrue(estimate.fallbackUsed)
    }

}
