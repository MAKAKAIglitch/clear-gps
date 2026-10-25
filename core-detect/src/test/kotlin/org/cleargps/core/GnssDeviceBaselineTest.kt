package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssDeviceBaselineTest {
    private fun signal(
        atSeconds: Long,
        visible: Int = 10,
        used: Int = 8,
        cn0: Double? = 38.0,
        spread: Double? = 4.0
    ) = GnssSignalSnapshot(
        elapsedRealtimeNanos = atSeconds * 1_000_000_000L,
        satellitesVisible = visible,
        satellitesUsedInFix = used,
        meanCn0DbHz = cn0,
        cn0SpreadDb = spread,
        constellationCount = 3,
        strongSignalCount = visible
    )

    private fun rf(atSeconds: Long, agc: Double? = -62.0, agcCount: Int = 8) = GnssRfSnapshot(
        elapsedRealtimeNanos = atSeconds * 1_000_000_000L,
        signalCount = 10,
        constellationCount = 3,
        meanCn0DbHz = 38.0,
        meanAgcDb = agc,
        agcSampleCount = agcCount
    )

    @Test
    fun baselineOnlyLearnsFromNormalSamplesOutsideRiskZones() {
        val baseline = GnssDeviceBaseline()
        assertFalse(baseline.observe(signal(1), rf(1), 1_200_000_000L, isNormal = false, inRiskZone = false))
        assertFalse(baseline.observe(signal(2), rf(2), 2_200_000_000L, isNormal = true, inRiskZone = true))
        assertEquals(0, baseline.snapshot().acceptedSignalSnapshots)
        assertEquals(0, baseline.snapshot().acceptedAgcSnapshots)

        assertTrue(baseline.observe(signal(3), rf(3), 3_200_000_000L, isNormal = true, inRiskZone = false))
        val snapshot = baseline.snapshot()
        assertEquals(1, snapshot.acceptedSignalSnapshots)
        assertEquals(1, snapshot.acceptedAgcSnapshots)
        assertEquals(38.0, snapshot.meanCn0DbHz.mean!!, 0.001)
        assertEquals(-62.0, snapshot.meanAgcDb.mean!!, 0.001)
    }

    @Test
    fun missingAgcDoesNotBlockSignalBaselineOrInventAnAgcValue() {
        val baseline = GnssDeviceBaseline()
        assertTrue(
            baseline.observe(
                signal(1),
                rf(1, agc = null, agcCount = 0),
                1_200_000_000L,
                isNormal = true,
                inRiskZone = false
            )
        )
        val snapshot = baseline.snapshot()
        assertEquals(1, snapshot.meanCn0DbHz.sampleCount)
        assertEquals(0, snapshot.meanAgcDb.sampleCount)
        assertNull(snapshot.meanAgcDb.mean)
    }

    @Test
    fun staleFutureDuplicateAndOutOfOrderSamplesAreIgnored() {
        val baseline = GnssDeviceBaseline()
        assertFalse(baseline.observe(signal(1), null, 20_000_000_000L, isNormal = true, inRiskZone = false))
        assertFalse(baseline.observe(signal(3), null, 2_200_000_000L, isNormal = true, inRiskZone = false))
        assertTrue(baseline.observe(signal(3), null, 3_200_000_000L, isNormal = true, inRiskZone = false))
        assertFalse(baseline.observe(signal(3), null, 3_300_000_000L, isNormal = true, inRiskZone = false))
        assertFalse(baseline.observe(signal(2), null, 3_200_000_000L, isNormal = true, inRiskZone = false))
        assertEquals(1, baseline.snapshot().acceptedSignalSnapshots)
    }

    @Test
    fun lossOfSatellitesIsNotRecordedAsZeroCn0OrZeroSatellitesInBaseline() {
        val baseline = GnssDeviceBaseline()
        assertFalse(
            baseline.observe(
                signal(1, visible = 0, used = 0, cn0 = null, spread = null),
                null,
                1_200_000_000L,
                isNormal = true,
                inRiskZone = false
            )
        )
        val snapshot = baseline.snapshot()
        assertEquals(0, snapshot.acceptedSignalSnapshots)
        assertNull(snapshot.meanCn0DbHz.mean)
        assertNull(snapshot.visibleSatellites.mean)
        assertNull(snapshot.firstSampleElapsedRealtimeNanos)
    }
    @Test
    fun restoredBaselineContinuesLearningWithFreshSessionTimestamps() {
        val initial = GnssDeviceBaseline()
        assertTrue(initial.observe(signal(1, cn0 = 38.0), rf(1), 1_200_000_000L, isNormal = true, inRiskZone = false))
        val storedState = initial.snapshot().copy(
            firstSampleElapsedRealtimeNanos = null,
            lastSampleElapsedRealtimeNanos = null
        )

        val restored = GnssDeviceBaseline()
        assertTrue(restored.restore(storedState))
        assertTrue(restored.observe(signal(2, cn0 = 40.0), rf(2, agc = -64.0), 2_200_000_000L, isNormal = true, inRiskZone = false))

        val snapshot = restored.snapshot()
        assertEquals(2, snapshot.acceptedSignalSnapshots)
        assertEquals(2, snapshot.acceptedAgcSnapshots)
        assertEquals(39.0, snapshot.meanCn0DbHz.mean!!, 0.001)
        assertEquals(-63.0, snapshot.meanAgcDb.mean!!, 0.001)
        assertEquals(2_000_000_000L, snapshot.firstSampleElapsedRealtimeNanos)
    }

    @Test
    fun corruptRestoredBaselineIsRejectedWithoutPartialMutation() {
        val baseline = GnssDeviceBaseline()
        val corrupt = GnssDeviceBaselineSnapshot(
            acceptedSignalSnapshots = 2,
            acceptedAgcSnapshots = 0,
            firstSampleElapsedRealtimeNanos = null,
            lastSampleElapsedRealtimeNanos = null,
            meanCn0DbHz = GnssMetricBaseline(sampleCount = 2, mean = 500.0, standardDeviation = -1.0),
            cn0SpreadDb = GnssMetricBaseline(sampleCount = 0, mean = null, standardDeviation = null),
            visibleSatellites = GnssMetricBaseline(sampleCount = 2, mean = 8.0, standardDeviation = 1.0),
            usedSatellites = GnssMetricBaseline(sampleCount = 2, mean = 6.0, standardDeviation = 1.0),
            meanAgcDb = GnssMetricBaseline(sampleCount = 0, mean = null, standardDeviation = null)
        )
        assertFalse(baseline.restore(corrupt))
        assertEquals(0, baseline.snapshot().acceptedSignalSnapshots)
        assertEquals(0, baseline.snapshot().meanCn0DbHz.sampleCount)
    }

}
