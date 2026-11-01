package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPointCenterEstimatorTest {
    private fun sample(
        id: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double = 15.0,
        rssi: Int = -55,
        source: String = "gps"
    ) = RadioPointObservation(
        id = id,
        observedAt = id,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        rssi = rssi,
        frequency = 0,
        positionSource = source
    )

    @Test
    fun strongerSignalHasMoreInfluenceWhenAccuracyIsEqual() {
        val center = estimateObservationCenter(listOf(
            sample(1, 0.0, 0.0, rssi = -35),
            sample(2, 0.0, 0.001, rssi = -85)
        ))

        assertNotNull(center)
        assertTrue(center!!.longitude < 0.0002)
    }

    @Test
    fun accurateObservationHasMoreInfluenceThanCoarseObservation() {
        val center = estimateObservationCenter(listOf(
            sample(1, 0.0, 0.0, accuracy = 8.0),
            sample(2, 0.0, 0.001, accuracy = 800.0)
        ))

        assertNotNull(center)
        assertTrue(center!!.longitude < 0.0001)
    }

    @Test
    fun isolatedOutlierIsRemovedBeforeFinalCenterIsCalculated() {
        val center = estimateObservationCenter(listOf(
            sample(1, 52.0, 5.0),
            sample(2, 52.00002, 5.00001),
            sample(3, 51.99999, 5.00002),
            sample(4, 52.00001, 4.99999),
            sample(5, 52.00002, 4.99998),
            sample(6, 52.2, 5.4)
        ))

        assertNotNull(center)
        assertTrue(kotlin.math.abs(center!!.latitude - 52.0) < 0.0001)
        assertTrue(kotlin.math.abs(center.longitude - 5.0) < 0.0001)
        assertEquals(5, center.sampleCount)
    }

    @Test
    fun openCellIdEvidenceHasLowerWeightThanLocalObservation() {
        val center = estimateObservationCenter(listOf(
            sample(1, 0.0, 0.0, source = "gps"),
            sample(2, 0.0, 0.0005, accuracy = 15.0, rssi = -110, source = "OpenCellID")
        ))

        assertNotNull(center)
        assertTrue(center!!.longitude < 0.0001)
    }

    @Test
    fun observationsAcrossAntimeridianProduceNearbyCenter() {
        val center = estimateObservationCenter(listOf(
            sample(1, 10.0, 179.999),
            sample(2, 10.0, -179.999)
        ))

        assertNotNull(center)
        assertTrue(kotlin.math.abs(center!!.longitude) > 179.0)
        assertEquals(10.0, center.latitude, 0.001)
    }

    @Test
    fun invalidCoordinatesAreIgnored() {
        assertEquals(null, estimateObservationCenter(listOf(
            sample(1, 91.0, 0.0),
            sample(2, 0.0, Double.NaN)
        )))
    }
}
