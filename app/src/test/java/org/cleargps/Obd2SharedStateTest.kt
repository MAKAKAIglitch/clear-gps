package org.cleargps

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Obd2SharedStateTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("obd2-shared-state-test", Context.MODE_PRIVATE)

    @Before
    fun clearBeforeTest() {
        prefs.edit().clear().commit()
    }

    @After
    fun clearAfterTest() {
        prefs.edit().clear().commit()
    }

    @Test
    fun storesTheOriginalMonotonicPidMidpointAndReturnsOnlyFreshSamples() {
        val now = seconds(20)
        val midpoint = now - 250_000_000L
        Obd2SharedState.storeTelemetry(
            prefs,
            Obd2Telemetry(
                vehicleSpeedKmh = 42,
                updatedAt = 1_000L,
                speedSampleElapsedRealtimeNanos = midpoint,
                speedQueryDurationMillis = 250L
            ),
            nowElapsedRealtimeNanos = now
        )

        val sample = Obd2SharedState.freshSpeedSample(prefs, now)
        assertEquals(42, sample?.speedKmh)
        assertEquals(midpoint, sample?.elapsedRealtimeNanos)
        assertEquals(250L, sample?.queryDurationMillis)
    }

    @Test
    fun legacyWallClockTimestampCannotCreateAValidMonotonicSample() {
        Obd2SharedState.storeTelemetry(
            prefs,
            Obd2Telemetry(vehicleSpeedKmh = 35, updatedAt = 1_000L),
            nowElapsedRealtimeNanos = seconds(20)
        )

        assertNull(Obd2SharedState.freshSpeedSample(prefs, seconds(20)))
        assertNull(Obd2SharedState.freshSpeedSample(prefs, seconds(20) + 10_000_000_000L))
    }

    @Test
    fun staleOrFuturePidSamplesAreNotStoredForDeadReckoning() {
        val now = seconds(20)
        val stale = Obd2Telemetry(
            vehicleSpeedKmh = 50,
            updatedAt = 1_000L,
            speedSampleElapsedRealtimeNanos = now - Obd2SharedState.SPEED_MAX_AGE_NANOS - 1L,
            speedQueryDurationMillis = 100L
        )
        Obd2SharedState.storeTelemetry(prefs, stale, now)
        assertNull(Obd2SharedState.freshSpeedSample(prefs, now))

        val future = stale.copy(speedSampleElapsedRealtimeNanos = now + 1L)
        Obd2SharedState.storeTelemetry(prefs, future, now)
        assertNull(Obd2SharedState.freshSpeedSample(prefs, now))
        assertEquals(-1, prefs.getInt(Obd2SharedState.KEY_SPEED_KMH, -1))
    }

    @Test
    fun slowQueriesCannotFeedPositionPredictionAndClearRemovesMonotonicTime() {
        val now = seconds(20)
        val sampleAt = now - 100_000_000L
        Obd2SharedState.storeTelemetry(
            prefs,
            Obd2Telemetry(
                vehicleSpeedKmh = 42,
                updatedAt = 1_000L,
                speedSampleElapsedRealtimeNanos = sampleAt,
                speedQueryDurationMillis = Obd2SpeedSamplePolicy.MAXIMUM_QUERY_DURATION_MILLIS + 1L
            ),
            nowElapsedRealtimeNanos = now
        )

        assertTrue(Obd2SharedState.freshSpeedKmh(prefs, 1_000L) != null)
        assertNull(Obd2SharedState.freshSpeedSample(prefs, now))

        Obd2SharedState.clearSpeed(prefs)
        assertNull(Obd2SharedState.freshSpeedSample(prefs, now))
        assertEquals(0L, prefs.getLong(Obd2SharedState.KEY_SPEED_AT_ELAPSED_REALTIME_NANOS, -1L))
        assertEquals(-1L, prefs.getLong(Obd2SharedState.KEY_SPEED_QUERY_DURATION_MS, 0L))
    }

    @Test
    fun keepsOnlyChronologicalMonotonicSamplesAfterTheGpsAnchor() {
        val base = seconds(100)
        for (sampleSecond in listOf(100L, 102L, 104L)) {
            Obd2SharedState.storeTelemetry(
                prefs,
                Obd2Telemetry(
                    vehicleSpeedKmh = 36,
                    updatedAt = sampleSecond * 1_000L,
                    speedSampleElapsedRealtimeNanos = seconds(sampleSecond),
                    speedQueryDurationMillis = 150L
                ),
                nowElapsedRealtimeNanos = seconds(sampleSecond) + 100_000_000L
            )
        }
        val history = Obd2SharedState.speedSamplesSince(
            afterElapsedRealtimeNanos = base,
            nowElapsedRealtimeNanos = seconds(106)
        )
        assertEquals(listOf(seconds(102), seconds(104)), history.map { it.elapsedRealtimeNanos })
        assertTrue(history.all { it.speedKmh == 36 })
    }

    @Test
    fun invalidTelemetryBreaksTheInMemoryHistory() {
        val now = seconds(20)
        Obd2SharedState.storeTelemetry(
            prefs,
            Obd2Telemetry(
                vehicleSpeedKmh = 36, updatedAt = 1_000L,
                speedSampleElapsedRealtimeNanos = seconds(19),
                speedQueryDurationMillis = 100L
            ),
            nowElapsedRealtimeNanos = now
        )
        Obd2SharedState.storeTelemetry(
            prefs,
            Obd2Telemetry(
                vehicleSpeedKmh = 36, updatedAt = 1_500L,
                speedSampleElapsedRealtimeNanos = seconds(19) + 1_000_000L,
                speedQueryDurationMillis = 2_000L
            ),
            nowElapsedRealtimeNanos = now
        )
        assertTrue(
            Obd2SharedState.speedSamplesSince(seconds(18), now).isEmpty()
        )
    }

    @Test
    fun exactFreshnessBoundaryIsAccepted() {
        val now = seconds(20)
        val sampleAt = now - Obd2SharedState.SPEED_MAX_AGE_NANOS
        Obd2SharedState.storeTelemetry(
            prefs,
            Obd2Telemetry(
                vehicleSpeedKmh = 36,
                updatedAt = 1_000L,
                speedSampleElapsedRealtimeNanos = sampleAt,
                speedQueryDurationMillis = 100L
            ),
            nowElapsedRealtimeNanos = now
        )

        assertEquals(sampleAt, Obd2SharedState.freshSpeedSample(prefs, now)?.elapsedRealtimeNanos)
    }

    private fun seconds(value: Long) = value * 1_000_000_000L
}
