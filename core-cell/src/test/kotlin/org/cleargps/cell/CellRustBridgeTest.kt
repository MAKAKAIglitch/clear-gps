package org.cleargps.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CellRustBridgeTest {
    @Test
    fun nativeKernelsAreCallableWhenRequiredByCi() {
        if (System.getProperty("cleargps.requireRustNative") != "true") return

        assertTrue("Host JNI library must load in CI", CellRustBridge.isAvailable)

        val x = DoubleArray(16)
        val y = DoubleArray(16)
        val ranges = DoubleArray(16)
        val sigmas = DoubleArray(16) { 15.0 }
        for (index in 0 until 16) {
            val bearing = Math.toRadians(index * 22.5)
            val distance = 600.0 + (index % 4) * 180.0
            x[index] = distance * sin(bearing)
            y[index] = distance * cos(bearing)
            ranges[index] = distance
        }
        val timingFit = CellRustBridge.fitTimingAdvance(
            x, y, ranges, sigmas, maximumIterations = 30, bearingBinDegrees = 45
        )
        assertNotNull("Timing Advance JNI symbol must resolve and return a fit", timingFit)
        assertEquals(8, timingFit!!.size)
        assertTrue(abs(timingFit[0]) < 0.01)
        assertTrue(abs(timingFit[1]) < 0.01)

        val bearings = ArrayList<Double>()
        val distances = ArrayList<Double>()
        val rssi = ArrayList<Double>()
        for (bearing in 0 until 360 step 10) {
            for (distance in listOf(300.0, 450.0, 600.0)) {
                val difference = abs(((bearing - 80.0 + 540.0) % 360.0) - 180.0)
                val gain = if (difference <= 65.0) 16.0 else 0.0
                bearings += bearing.toDouble()
                distances += distance
                rssi += -45.0 - 30.0 * (kotlin.math.ln(distance) / kotlin.math.ln(10.0)) + gain
            }
        }
        val sectorFit = CellRustBridge.evaluateSector(
            bearings.toDoubleArray(),
            distances.toDoubleArray(),
            rssi.toDoubleArray(),
            angularBinDegrees = 10,
            minimumOccupiedBins = 6,
            minimumSignalContrastDb = 3.0,
            maximumModelSpreadDegrees = 30.0
        )
        assertNotNull("Sector JNI symbol must resolve and return a model", sectorFit)
        assertEquals(6, sectorFit!!.size)
        val directionDifference = abs(((sectorFit[0] - 80.0 + 540.0) % 360.0) - 180.0)
        assertTrue("Expected sector direction near 80°, got ${sectorFit[0]}", directionDifference < 20.0)
        assertTrue(sectorFit[2] in 0.0..1.0)
    }
}
