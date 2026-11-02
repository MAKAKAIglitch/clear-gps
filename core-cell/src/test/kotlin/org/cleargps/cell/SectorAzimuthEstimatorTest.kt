package org.cleargps.cell

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SectorAzimuthEstimatorTest {
    private val identity = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "01", 10, 123456L)!!

    @Test fun derivesDirectionFromSpatiallyDiverseHistoricalSamples() {
        val samples = buildList {
            for (bearing in 0 until 360 step 10) for (distance in listOf(300.0, 450.0, 600.0)) {
                val gain = if (angularDifference(bearing.toDouble(), 80.0) <= 65.0) 16.0 else 0.0
                val coordinate = destination(51.5, -0.12, bearing.toDouble(), distance)
                add(CellObservationSample(identity, coordinate.first, coordinate.second, 8.0,
                    -45.0 - 30.0 * log10(distance) + gain, 1800000, size.toLong() + 1L,
                    ObservationLocationOrigin.OBSERVED, "device-a"))
            }
        }
        val result = SectorAzimuthEstimator.estimate(identity, 51.5, -0.12, 10.0, samples)
        assertNotNull(result)
        assertTrue(angularDifference(result!!.azimuthDegrees, 80.0) < 20.0)
        assertTrue(result.suitableForMapDisplay)
    }

    @Test fun missingReceiverProfileCannotDetermineAntennaDirection() {
        val samples = (0 until 360 step 10).map { bearing ->
            val p = destination(51.5, -0.12, bearing.toDouble(), 500.0)
            CellObservationSample(identity, p.first, p.second, 5.0, -50.0, 1800000,
                bearing.toLong(), ObservationLocationOrigin.OBSERVED)
        }
        assertNull(SectorAzimuthEstimator.estimate(identity, 51.5, -0.12, 10.0, samples))
    }

    @Test fun legacyAmbiguousMncCannotProduceSector() {
        val legacy = CellIdentity.fromLegacyIdentifier("lte:250:1:10:123456")!!
        val samples = (0 until 360 step 10).map { bearing ->
            val p = destination(51.5, -0.12, bearing.toDouble(), 500.0)
            CellObservationSample(legacy, p.first, p.second, 5.0, -50.0, 1800000,
                bearing.toLong(), ObservationLocationOrigin.OBSERVED, "device-a")
        }
        assertNull(SectorAzimuthEstimator.estimate(legacy, 51.5, -0.12, 10.0, samples))
    }

    @Test fun derivedCoordinatesCannotDetermineAntennaDirection() {
        val samples = (0 until 360 step 10).map { bearing ->
            val p = destination(51.5, -0.12, bearing.toDouble(), 500.0)
            CellObservationSample(identity, p.first, p.second, 5.0, -50.0, 1800000,
                bearing.toLong(), ObservationLocationOrigin.DERIVED_ESTIMATE, "device-a")
        }
        assertNull(SectorAzimuthEstimator.estimate(identity, 51.5, -0.12, 10.0, samples))
    }
    
    private fun destination(lat: Double, lon: Double, bearing: Double, distance: Double): Pair<Double, Double> {
        val angular = distance / 6_371_008.8
        val br = Math.toRadians(bearing); val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = kotlin.math.asin(sin(p1) * cos(angular) + cos(p1) * sin(angular) * cos(br))
        val l2 = l1 + kotlin.math.atan2(sin(br) * sin(angular) * cos(p1), cos(angular) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to (((Math.toDegrees(l2) + 540.0) % 360.0) - 180.0)
    }
    private fun log10(v: Double) = kotlin.math.ln(v) / kotlin.math.ln(10.0)
    private fun angularDifference(a: Double, b: Double): Double {
        val d = kotlin.math.abs((a - b + 540.0) % 360.0 - 180.0)
        return d
    }
}
