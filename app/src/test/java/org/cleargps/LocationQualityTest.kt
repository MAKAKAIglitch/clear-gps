package org.cleargps

import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationQualityTest {
    @Test
    fun gpsHasPriorityOverNetworkWhileItIsNotMoreThanTwoMinutesOlder() {
        val network = fix(LocationManager.NETWORK_PROVIDER, time = 100_000L, accuracy = 10f)
        val gpsWithinTolerance = fix(LocationManager.GPS_PROVIDER, time = -20_000L, accuracy = 80f)
        val gpsTooOld = fix(LocationManager.GPS_PROVIDER, time = -20_001L, accuracy = 1f)

        assertTrue(isBetterLocation(gpsWithinTolerance, network))
        assertFalse(isBetterLocation(gpsTooOld, network))
    }

    @Test
    fun recentNetworkFixCannotReplaceGpsBeforeTheTwoMinuteBoundary() {
        val gps = fix(LocationManager.GPS_PROVIDER, time = 100_000L, accuracy = 30f)
        val networkBeforeBoundary = fix(LocationManager.NETWORK_PROVIDER, time = 219_999L, accuracy = 1f)
        val networkAtBoundary = fix(LocationManager.NETWORK_PROVIDER, time = 220_000L, accuracy = 200f)

        assertFalse(isBetterLocation(networkBeforeBoundary, gps))
        assertTrue(isBetterLocation(networkAtBoundary, gps))
    }

    @Test
    fun substantiallyNewerFixWinsWhenThePreviousProviderIsNoLongerFresh() {
        val previous = fix(LocationManager.NETWORK_PROVIDER, time = 100_000L, accuracy = 5f)
        val candidate = fix(LocationManager.NETWORK_PROVIDER, time = 130_001L, accuracy = 100f)

        assertTrue(isBetterLocation(candidate, previous))
    }

    @Test
    fun smallTimeDifferenceUsesAccuracyAndDoesNotPreferAnOlderWorseFix() {
        val previous = fix(LocationManager.NETWORK_PROVIDER, time = 100_000L, accuracy = 30f)
        val moreAccurate = fix(LocationManager.NETWORK_PROVIDER, time = 100_000L, accuracy = 10f)
        val olderAndWorse = fix(LocationManager.NETWORK_PROVIDER, time = 99_000L, accuracy = 45f)

        assertTrue(isBetterLocation(moreAccurate, previous))
        assertFalse(isBetterLocation(olderAndWorse, previous))
    }

    @Test
    fun providerComparisonIsCaseInsensitive() {
        val previous = fix("network", time = 100_000L, accuracy = 15f)
        val gps = fix("GPS", time = 99_000L, accuracy = 100f)

        assertTrue(isBetterLocation(gps, previous))
    }

    @Test
    fun monotonicMeasurementTimeWinsWhenWallClockMovesBackward() {
        val previous = fix(
            LocationManager.NETWORK_PROVIDER,
            time = 100_000L,
            accuracy = 5f,
            elapsedRealtimeMillis = 1_000_000L
        )
        val candidate = fix(
            LocationManager.NETWORK_PROVIDER,
            time = 80_000L,
            accuracy = 100f,
            elapsedRealtimeMillis = 1_030_001L
        )

        assertTrue(isBetterLocation(candidate, previous))
    }

    @Test
    fun wallClockJumpForwardDoesNotMakeAnOlderMeasurementFresh() {
        val previous = fix(
            LocationManager.NETWORK_PROVIDER,
            time = 100_000L,
            accuracy = 5f,
            elapsedRealtimeMillis = 1_000_000L
        )
        val candidate = fix(
            LocationManager.NETWORK_PROVIDER,
            time = 130_001L,
            accuracy = 100f,
            elapsedRealtimeMillis = 999_000L
        )

        assertFalse(isBetterLocation(candidate, previous))
    }

    private fun fix(
        provider: String,
        time: Long,
        accuracy: Float,
        elapsedRealtimeMillis: Long = time + 300_000L
    ): Location = Location(provider).apply {
        latitude = 52.274
        longitude = 40.452
        this.time = time
        elapsedRealtimeNanos = elapsedRealtimeMillis * 1_000_000L
        this.accuracy = accuracy
    }
}
