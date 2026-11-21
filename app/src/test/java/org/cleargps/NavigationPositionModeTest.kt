package org.cleargps

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationPositionModeTest {
    @Test
    fun readsClearGpsCalculatedRadioPositionWhenMockGpsPublishingIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_test", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "radio")
            .putBoolean(LocationMonitorService.KEY_MONITORING, true)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, true)
            .putBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
            .putString(LocationMonitorService.KEY_SOURCE, "LOCAL_RADIO")
            .putString(LocationMonitorService.KEY_LAT, "60.1699")
            .putString(LocationMonitorService.KEY_LON, "24.9384")
            .putFloat(LocationMonitorService.KEY_ACCURACY, 85f)
            .commit()

        val snapshot = readNavigationPosition(prefs)

        assertNotNull(snapshot)
        assertEquals(60.1699, snapshot!!.coordinate.latitude, 0.000001)
        assertEquals(24.9384, snapshot.coordinate.longitude, 0.000001)
        assertEquals(85.0, snapshot.accuracyMeters!!, 0.001)
        assertEquals("LOCAL_RADIO", snapshot.source)
        prefs.edit().clear().commit()
    }

    @Test
    fun readsLegacyAndCurrentPositionModeKeys() {
        assertTrue(
            NavigationPositionMode.fromStoredKey("auto") == NavigationPositionMode.AUTO
        )
        assertTrue(
            NavigationPositionMode.fromStoredKey("gps") == NavigationPositionMode.GPS_ONLY
        )
        assertTrue(
            NavigationPositionMode.fromStoredKey("gps_obd2") == NavigationPositionMode.GPS_ONLY
        )
        assertTrue(
            NavigationPositionMode.fromStoredKey("radio") == NavigationPositionMode.RADIO_ONLY
        )
        assertTrue(
            NavigationPositionMode.fromStoredKey("radio_obd2") == NavigationPositionMode.RADIO_ONLY
        )
    }

    @Test
    fun gpsModeAcceptsOnlyGpsPipelineOutputs() {
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.GPS_ONLY, "GPS"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.GPS_ONLY, "LAST_TRUSTED"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.GPS_ONLY, "OBD2_DEAD_RECKONING"))
        assertFalse(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.GPS_ONLY, "LOCAL_RADIO"))
        assertFalse(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.GPS_ONLY, "BEACONDB"))
    }

    @Test
    fun radioModeAcceptsRadioEstimatesButNotPhysicalGps() {
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.RADIO_ONLY, "LOCAL_RADIO"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.RADIO_ONLY, "LOCAL_RADIO_STALE"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.RADIO_ONLY, "BEACONDB"))
        assertFalse(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.RADIO_ONLY, "GPS"))
    }

    @Test
    fun autoModeAcceptsEitherTrustedGpsOrRadioFallback() {
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.AUTO, "GPS"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.AUTO, "LAST_TRUSTED"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.AUTO, "NETWORK"))
        assertTrue(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.AUTO, "LOCAL_RADIO"))
        assertFalse(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.AUTO, "NONE"))
        assertFalse(NavigationPositionPolicy.acceptsSource(NavigationPositionMode.AUTO, "EXTERNAL_MOCK"))
    }
}
