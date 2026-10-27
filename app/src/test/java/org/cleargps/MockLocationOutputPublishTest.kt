package org.cleargps

import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val output = MockLocationOutput(context)
        val expectedLatitude = 52.123456
        val expectedLongitude = 40.654321
        val source = Location("clear-gps-radio").apply {
            latitude = expectedLatitude
            longitude = expectedLongitude
            accuracy = 18f
            time = 1_798_000_000_000L
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

            val gpsFix = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: throw AssertionError("Android GPS test provider did not retain the published fix.")
            assertEquals(expectedLatitude, gpsFix.latitude, 0.000001)
            assertEquals(expectedLongitude, gpsFix.longitude, 0.000001)
            assertEquals(18f, gpsFix.accuracy, 0.01f)

            val networkFix = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: throw AssertionError("Android network test provider did not retain the published fix.")
            assertEquals(expectedLatitude, networkFix.latitude, 0.000001)
            assertEquals(expectedLongitude, networkFix.longitude, 0.000001)
        } finally {
            output.close()
        }
    }
}
