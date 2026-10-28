package org.cleargps

import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MockLocationOutputPublishTest {
    @Test
    fun radioModePublishesCalculatedFixToSystemGpsAndNetworkProviders() {
        val context = RuntimeEnvironment.getApplication()
        val output = MockLocationOutput(context)
        val expectedLatitude = 52.123456
        val expectedLongitude = 40.654321
        val source = Location("clear-gps-radio").apply {
            latitude = expectedLatitude
            longitude = expectedLongitude
            accuracy = 18f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }

        try {
            assertTrue(
                "Radio mode should publish at least through Android's system providers",
                output.publish(
                    source = source,
                    alsoPublishNetworkProvider = true,
                    publishGpsProvider = true
                )
            )
            assertTrue(output.lastPublishedProviders.contains(LocationManager.GPS_PROVIDER))
            assertTrue(output.lastPublishedProviders.contains(LocationManager.NETWORK_PROVIDER))

            // Robolectric confirms that both provider update calls completed without throwing.
            // Its LocationManager shadow does not reliably expose injected mock state through
            // getLastKnownLocation() or isProviderEnabled(); readback remains a device-level check.
        } finally {
            output.close()
        }
    }
}
