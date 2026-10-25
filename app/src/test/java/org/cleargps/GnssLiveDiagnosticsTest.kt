package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssLiveDiagnosticsTest {
    private fun satellite(
        constellation: Int = 1, svid: Int = 7, used: Boolean = true, cn0: Double? = 36.0
    ) = GnssSatelliteReading(
        constellationType = constellation, svid = svid, cn0DbHz = cn0,
        elevationDegrees = 42.0, azimuthDegrees = 125.0, usedInFix = used,
        reportedCarrierFrequencyHz = 1_575_420_000.0
    )

    @Test
    fun freshStatusIsAvailableAndStaleStatusIsHidden() {
        GnssDiagnosticsLiveStore.clear()
        GnssDiagnosticsLiveStore.updateSatelliteStatus(1_000_000_000L, listOf(satellite()))
        assertNotNull(GnssDiagnosticsLiveStore.snapshot(2_000_000_000L))
        assertNull(GnssDiagnosticsLiveStore.snapshot(12_000_000_001L))
    }

    @Test
    fun measurementsAreMatchedByConstellationAndSvid() {
        GnssDiagnosticsLiveStore.clear()
        GnssDiagnosticsLiveStore.updateSatelliteStatus(
            1_000_000_000L, listOf(satellite(constellation = 1), satellite(constellation = 6, used = false))
        )
        GnssDiagnosticsLiveStore.updateMeasurements(
            2_000_000_000L,
            listOf(GnssSatelliteMeasurement(1, 7, 1_575_420_000.0, -43.0)),
            listOf(GnssAgcBandReading(1, 1_575_420_000.0, -44.0))
        )
        val snapshot = GnssDiagnosticsLiveStore.snapshot(3_000_000_000L)
        assertNotNull(snapshot)
        assertEquals(-43.0, snapshot!!.satellites.first { it.constellationType == 1 }.agcDb!!, 0.001)
        assertNull(snapshot.satellites.first { it.constellationType == 6 }.agcDb)
        assertEquals(1, snapshot.agcBands.size)
    }

    @Test
    fun staleMeasurementFieldsExpireWhileRecentSatelliteStatusRemainsVisible() {
        GnssDiagnosticsLiveStore.clear()
        GnssDiagnosticsLiveStore.updateSatelliteStatus(1_000_000_000L, listOf(satellite()))
        GnssDiagnosticsLiveStore.updateMeasurements(
            2_000_000_000L,
            listOf(GnssSatelliteMeasurement(1, 7, 1_575_420_000.0, -43.0)),
            listOf(GnssAgcBandReading(1, 1_575_420_000.0, -44.0))
        )
        GnssDiagnosticsLiveStore.updateSatelliteStatus(5_000_000_000L, listOf(satellite()))
        val snapshot = GnssDiagnosticsLiveStore.snapshot(13_000_000_000L)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.satellites.size)
        assertNull(snapshot.satellites.single().agcDb)
        assertNull(snapshot.satellites.single().measurementCarrierFrequencyHz)
        assertTrue(snapshot.agcBands.isEmpty())
    }

    @Test
    fun invalidSatelliteRecordsAreIgnoredAndClearDropsSnapshot() {
        GnssDiagnosticsLiveStore.clear()
        GnssDiagnosticsLiveStore.updateSatelliteStatus(
            1_000_000_000L,
            listOf(satellite(), satellite(svid = -1), satellite(svid = 7, cn0 = Double.NaN))
        )
        val snapshot = GnssDiagnosticsLiveStore.snapshot(2_000_000_000L)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.satellites.size)
        GnssDiagnosticsLiveStore.clear()
        assertNull(GnssDiagnosticsLiveStore.snapshot(3_000_000_000L))
    }
}
