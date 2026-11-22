package org.cleargps

import android.content.Context
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationPositionModeTest {
    @Test
    fun autoModeDescriptionExplainsLocalRadioCorrectionAlongsideTrustedGps() {
        val description = NavigationPositionMode.AUTO.description
        assertTrue(description.contains("локальной радиобазе"))
        assertTrue(description.contains("корректирует GPS"))
        assertTrue(description.contains("слияние измерений"))
    }
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
            .putFloat(LocationMonitorService.KEY_BEARING, 180f)
            .commit()

        val snapshot = requireNotNull(readNavigationPosition(prefs))

        assertEquals(60.1699, snapshot.coordinate.latitude, 0.000001)
        assertEquals(24.9384, snapshot.coordinate.longitude, 0.000001)
        assertEquals(85.0, snapshot.accuracyMeters!!, 0.001)
        assertEquals("LOCAL_RADIO", snapshot.source)
        assertEquals(null, snapshot.bearingDegrees)
        prefs.edit().clear().commit()
    }

    @Test
    fun readsFilteredGpsPositionWhenMockGpsPublishingIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_test_gps", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "gps")
            .putBoolean(LocationMonitorService.KEY_MONITORING, true)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, true)
            .putBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
            .putString(LocationMonitorService.KEY_SOURCE, "GPS")
            .putString(LocationMonitorService.KEY_LAT, "52.5200")
            .putString(LocationMonitorService.KEY_LON, "13.4050")
            .putFloat(LocationMonitorService.KEY_ACCURACY, 4.5f)
            .putFloat(LocationMonitorService.KEY_BEARING, 72.5f)
            .commit()

        val snapshot = requireNotNull(readNavigationPosition(prefs))

        assertEquals(52.5200, snapshot.coordinate.latitude, 0.000001)
        assertEquals(13.4050, snapshot.coordinate.longitude, 0.000001)
        assertEquals(4.5, snapshot.accuracyMeters!!, 0.001)
        assertEquals("GPS", snapshot.source)
        assertEquals(72.5, snapshot.bearingDegrees!!, 0.001)
        prefs.edit().clear().commit()
    }

    @Test
    fun showsRecentPhysicalGpsFixOnNavigationBeforeTrustFilterAcceptsIt() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_unverified_gps", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "gps")
            .putBoolean(LocationMonitorService.KEY_MONITORING, true)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, "55.7558")
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, "37.6173")
            .putFloat(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ACCURACY, 12f)
            .putLong(
                LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 1_000_000L
            )
            .commit()

        val snapshot = requireNotNull(readNavigationPosition(prefs))

        assertEquals(55.7558, snapshot.coordinate.latitude, 0.000001)
        assertEquals(37.6173, snapshot.coordinate.longitude, 0.000001)
        assertEquals("GPS_UNVERIFIED", snapshot.source)
        assertEquals(12.0, snapshot.accuracyMeters!!, 0.001)
        assertEquals(null, snapshot.bearingDegrees)
        prefs.edit().clear().commit()
    }

    @Test
    fun autoModePrefersClearGpsRadioDatabaseEstimateOverDisplayOnlyGpsFallback() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_auto_radio_priority", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            .putBoolean(LocationMonitorService.KEY_MONITORING, true)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, true)
            .putString(LocationMonitorService.KEY_SOURCE, "LOCAL_RADIO")
            .putString(LocationMonitorService.KEY_LAT, "60.1701")
            .putString(LocationMonitorService.KEY_LON, "24.9386")
            .putFloat(LocationMonitorService.KEY_ACCURACY, 75f)
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, "60.1699")
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, "24.9384")
            .putLong(
                LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 1_000_000L
            )
            .commit()

        val snapshot = requireNotNull(readNavigationPosition(prefs))
        assertEquals(60.1701, snapshot.coordinate.latitude, 0.000001)
        assertEquals(24.9386, snapshot.coordinate.longitude, 0.000001)
        assertEquals("LOCAL_RADIO", snapshot.source)
        prefs.edit().clear().commit()
    }

    @Test
    fun autoModeShowsRecentPhysicalGpsWhenRadioFallbackHasNoEstimate() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_auto_gps_fallback", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            .putBoolean(LocationMonitorService.KEY_MONITORING, true)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, "60.1699")
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, "24.9384")
            .putFloat(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ACCURACY, 18f)
            .putLong(
                LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 1_000_000L
            )
            .commit()

        val snapshot = requireNotNull(readNavigationPosition(prefs))
        assertEquals(60.1699, snapshot.coordinate.latitude, 0.000001)
        assertEquals(24.9384, snapshot.coordinate.longitude, 0.000001)
        assertEquals("GPS_UNVERIFIED", snapshot.source)
        assertEquals(18.0, snapshot.accuracyMeters!!, 0.001)
        prefs.edit().clear().commit()
    }

    @Test
    fun doesNotUseUnverifiedGpsInRadioOnlyModeButKeepsItVisibleAfterTwoMinutes() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_unverified_gps_expired", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "radio")
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, "55.7558")
            .putString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, "37.6173")
            .putLong(
                LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 1_000_000L
            )
            .commit()
        assertEquals(null, readNavigationPosition(prefs))

        ShadowSystemClock.advanceBy(121_000L, TimeUnit.MILLISECONDS)
        prefs.edit()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            .putLong(
                LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 121_000_000_000L
            )
            .commit()
        val staleSnapshot = requireNotNull(readNavigationPosition(prefs))
        assertEquals(55.7558, staleSnapshot.coordinate.latitude, 0.000001)
        assertEquals(37.6173, staleSnapshot.coordinate.longitude, 0.000001)
        assertEquals("GPS_UNVERIFIED_STALE", staleSnapshot.source)
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
        assertTrue(NavigationPositionPolicy.canProvideHeading("GPS"))
        assertTrue(NavigationPositionPolicy.canProvideHeading("LAST_TRUSTED"))
        assertTrue(NavigationPositionPolicy.canProvideHeading("OBD2_DEAD_RECKONING"))
        assertFalse(NavigationPositionPolicy.canProvideHeading("LOCAL_RADIO"))
        assertFalse(NavigationPositionPolicy.canProvideHeading("BEACONDB"))
    }
    @Test
    fun keepsRecentTrustedGpsVisibleWhileAutoIsAcquiringANewFix() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_last_trusted", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            .putBoolean(LocationMonitorService.KEY_MONITORING, true)
            .putBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            .putString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT, "48.1372")
            .putString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LON, "11.5756")
            .putFloat(LocationMonitorService.KEY_LAST_TRUSTED_GPS_ACCURACY, 6f)
            .putLong(
                LocationMonitorService.KEY_LAST_TRUSTED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 1_000_000L
            )
            .commit()

        val snapshot = requireNotNull(readNavigationPosition(prefs))

        assertEquals(48.1372, snapshot.coordinate.latitude, 0.000001)
        assertEquals(11.5756, snapshot.coordinate.longitude, 0.000001)
        assertEquals("LAST_TRUSTED", snapshot.source)
        assertEquals(6.0, snapshot.accuracyMeters!!, 0.001)
        assertEquals(null, snapshot.bearingDegrees)
        prefs.edit().clear().commit()
    }

    @Test
    fun doesNotUseLastTrustedGpsInRadioOnlyMode() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_last_trusted_radio", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "radio")
            .putString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT, "48.1372")
            .putString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LON, "11.5756")
            .putLong(
                LocationMonitorService.KEY_LAST_TRUSTED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 1_000_000L
            )
            .commit()

        assertEquals(null, readNavigationPosition(prefs))
        prefs.edit().clear().commit()
    }

    @Test
    fun ignoresExpiredTrustedGpsFallback() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("navigation_position_expired_gps", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            .putString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT, "48.1372")
            .putString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LON, "11.5756")
            .putLong(
                LocationMonitorService.KEY_LAST_TRUSTED_GPS_ELAPSED_NANOS,
                SystemClock.elapsedRealtimeNanos() - 180_000_000_000L
            )
            .commit()

        assertEquals(null, readNavigationPosition(prefs))
        prefs.edit().clear().commit()
    }

}
