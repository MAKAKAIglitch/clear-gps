package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixAcquisitionPolicyTest {
    @Test
    fun precisePermissionIsRequiredOnlyForGpsBackedModes() {
        assertTrue(GpsFixAcquisitionPolicy.requiresPreciseLocationPermission("gps"))
        assertTrue(GpsFixAcquisitionPolicy.requiresPreciseLocationPermission("gps_obd2"))
        assertFalse(GpsFixAcquisitionPolicy.requiresPreciseLocationPermission("auto"))
        assertFalse(GpsFixAcquisitionPolicy.requiresPreciseLocationPermission("radio"))
    }

    @Test
    fun missingPrecisePermissionHasActionableStatus() {
        assertEquals(
            "Для режима «Только GPS» требуется разрешение «Точное местоположение».",
            GpsFixAcquisitionPolicy.waitingStatus(
                hasFineLocationPermission = false,
                providerAvailable = true,
                providerEnabled = true,
                elapsedSinceTrackingStartedMs = 60_000L
            )
        )
    }

    @Test
    fun missingOrDisabledGpsProviderIsNotReportedAsIndefiniteWait() {
        assertEquals(
            "GPS-провайдер недоступен на этом устройстве.",
            GpsFixAcquisitionPolicy.waitingStatus(true, false, false, 0L)
        )
        assertEquals(
            "GPS выключен в системных настройках геолокации.",
            GpsFixAcquisitionPolicy.waitingStatus(true, true, false, 0L)
        )
    }

    @Test
    fun waitBecomesActionableAfterThirtySeconds() {
        assertTrue(
            GpsFixAcquisitionPolicy.waitingStatus(true, true, true, 29_999L)
                .contains("ожидание первого спутникового фикса")
        )
        assertTrue(
            GpsFixAcquisitionPolicy.waitingStatus(
                true, true, true, GpsFixAcquisitionPolicy.FIRST_FIX_TIMEOUT_MS
            ).contains("не выдал координаты за 30 секунд")
        )
    }
}
