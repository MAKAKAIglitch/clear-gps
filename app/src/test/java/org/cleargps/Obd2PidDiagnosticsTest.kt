package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2PidDiagnosticsTest {
    @Test
    fun mapsSpeedRpmAndLoadToTheirActualConsumers() {
        val nowEpoch = 50_000L
        val nowElapsed = 80_000_000_000L
        val rows = Obd2PidDiagnostics.rows(
            Obd2Telemetry(
                vehicleSpeedKmh = 54,
                engineRpm = 1_800,
                engineLoadPercent = 35,
                throttlePercent = 22,
                speedSampleElapsedRealtimeNanos = nowElapsed - 1_250_000_000L,
                speedQueryDurationMillis = 180L,
                engineRpmSampleElapsedRealtimeNanos = nowElapsed - 1_500_000_000L,
                engineRpmQueryDurationMillis = 210L,
                updatedAt = nowEpoch - 1_000L
            ),
            nowEpoch,
            nowElapsed
        ).associateBy { it.pid }

        assertEquals("54 км/ч", rows.getValue(0x0D).value)
        assertEquals(1_250L, rows.getValue(0x0D).ageMillis)
        assertTrue(rows.getValue(0x0D).consumers.any { it.contains("антиспуфинга") })
        assertTrue(rows.getValue(0x0D).consumers.any { it.contains("GPS-прогноз") })

        assertEquals(1_800, rows.getValue(0x0C).value?.substringBefore(' ')?.toInt())
        assertEquals(1_500L, rows.getValue(0x0C).ageMillis)
        assertTrue(rows.getValue(0x0C).consumers.single().contains("GearRatioLearner"))

        assertTrue(rows.getValue(0x04).consumers.single().contains("высокой нагрузки"))
        assertTrue(rows.getValue(0x11).consumers.single().contains("нажатия газа"))
    }

    @Test
    fun explicitlyMarksMapAndCoolantPidsAsNotInternallyConsumedYet() {
        val rows = Obd2PidDiagnostics.rows(
            Obd2Telemetry(
                manifoldPressureKpa = 98,
                coolantTemperatureCelsius = 87,
                barometricPressureKpa = 100,
                updatedAt = 10_000L
            ),
            nowEpochMillis = 12_000L,
            nowElapsedRealtimeNanos = 5_000_000_000L
        ).associateBy { it.pid }

        assertEquals(2_000L, rows.getValue(0x0B).ageMillis)
        assertEquals(2_000L, rows.getValue(0x05).ageMillis)
        assertEquals(2_000L, rows.getValue(0x33).ageMillis)
        assertTrue(rows.getValue(0x0B).consumers.single().contains("Внутреннего потребителя пока нет"))
        assertTrue(rows.getValue(0x05).consumers.single().contains("Внутреннего потребителя пока нет"))
        assertTrue(rows.getValue(0x33).consumers.single().contains("P3.6 ещё не подключён"))
        assertNull(rows.getValue(0x0B).issue)
        assertFalse(rows.getValue(0x0B).ageBasis.contains("монотонная"))
    }

    @Test
    fun futureSampleTimestampHasUnknownAgeInsteadOfNegativeAge() {
        val row = Obd2PidDiagnostics.rows(
            Obd2Telemetry(
                vehicleSpeedKmh = 20,
                speedSampleElapsedRealtimeNanos = 10_000L,
                speedQueryDurationMillis = 100L,
                updatedAt = 1_000L
            ),
            nowEpochMillis = 1_100L,
            nowElapsedRealtimeNanos = 9_999L
        ).first { it.pid == 0x0D }

        assertNull(row.ageMillis)
    }
}
