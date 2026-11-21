package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPositionModeTest {
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
