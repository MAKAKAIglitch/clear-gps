package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundRefinementPolicyTest {
    @Test fun beaconDbRequiresValidatedWifiBatteryAndSafeTemperature() {
        assertTrue(BeaconDbAutoRefinementPolicy.canRun(
            BeaconDbRefinementEnvironment(true, 60, 37.0)
        ))
        assertFalse(BeaconDbAutoRefinementPolicy.canRun(
            BeaconDbRefinementEnvironment(false, 100, 20.0)
        ))
        assertFalse(BeaconDbAutoRefinementPolicy.canRun(
            BeaconDbRefinementEnvironment(true, 59, 20.0)
        ))
        assertFalse(BeaconDbAutoRefinementPolicy.canRun(
            BeaconDbRefinementEnvironment(true, 90, 37.1)
        ))
        assertFalse(BeaconDbAutoRefinementPolicy.canRun(
            BeaconDbRefinementEnvironment(true, null, 30.0)
        ))
        assertFalse(BeaconDbAutoRefinementPolicy.canRun(
            BeaconDbRefinementEnvironment(true, 90, null)
        ))
    }

    @Test fun beaconDbRespectsGlobalAndPerFingerprintBackoff() {
        val now = 1_000_000L
        assertTrue(BeaconDbAutoRefinementPolicy.mayAttempt(
            now + BeaconDbAutoRefinementPolicy.GLOBAL_REQUEST_INTERVAL_MILLIS,
            now,
            now
        ))
        assertFalse(BeaconDbAutoRefinementPolicy.mayAttempt(now + 1L, now, now))
        assertFalse(BeaconDbAutoRefinementPolicy.mayAttempt(
            now + BeaconDbAutoRefinementPolicy.GLOBAL_REQUEST_INTERVAL_MILLIS,
            now,
            now + BeaconDbAutoRefinementPolicy.GLOBAL_REQUEST_INTERVAL_MILLIS + 1L
        ))
        assertEquals(
            now + 5L * 60L * 1_000L,
            BeaconDbAutoRefinementPolicy.nextAllowedAt(now, 1, "failure")
        )
        assertEquals(
            now + BeaconDbAutoRefinementPolicy.SUCCESS_BACKOFF_MILLIS,
            BeaconDbAutoRefinementPolicy.nextAllowedAt(now, 3, "success")
        )
        assertEquals(
            now + BeaconDbAutoRefinementPolicy.RATE_LIMIT_BACKOFF_MILLIS,
            BeaconDbAutoRefinementPolicy.nextAllowedAt(now, 1, "rate_limited")
        )
    }

    @Test fun openCellIdDailyBudgetIsTenAndSpreadsRequestsAcrossUtcDay() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteSharedPreferences("opencellid_local_database_budget")
        val start = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

        assertTrue(OpenCellIdLocalDbAutoLookupBudget.tryReserve(context, start))
        assertFalse(OpenCellIdLocalDbAutoLookupBudget.tryReserve(
            context, start + OpenCellIdLocalDbAutoLookupBudget.MIN_REQUEST_INTERVAL_MILLIS - 1L
        ))
        repeat(9) { index ->
            val at = start + (index + 1L) * OpenCellIdLocalDbAutoLookupBudget.MIN_REQUEST_INTERVAL_MILLIS
            assertTrue(OpenCellIdLocalDbAutoLookupBudget.tryReserve(context, at))
        }
        assertEquals(10, OpenCellIdLocalDbAutoLookupBudget.requestsUsedToday(
            context,
            start + 9L * OpenCellIdLocalDbAutoLookupBudget.MIN_REQUEST_INTERVAL_MILLIS
        ))
        val nextUtcDay = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
        assertTrue(OpenCellIdLocalDbAutoLookupBudget.tryReserve(context, nextUtcDay))
    }

    @Test fun passiveCaptureAcceptsOnlyFreshTrustedGpsLocations() {
        assertTrue(PassiveRadioCapturePolicy.acceptsFix(
            "gps", false, 8f, 5_000L, false, true
        ))
        assertFalse(PassiveRadioCapturePolicy.acceptsFix(
            "network", false, 2f, 1_000L, false, true
        ))
        assertFalse(PassiveRadioCapturePolicy.acceptsFix(
            "gps", true, 2f, 1_000L, false, true
        ))
        assertFalse(PassiveRadioCapturePolicy.acceptsFix(
            "gps", false, 10.1f, 1_000L, false, true
        ))
        assertFalse(PassiveRadioCapturePolicy.acceptsFix(
            "gps", false, 5f, 15_001L, false, true
        ))
        assertFalse(PassiveRadioCapturePolicy.acceptsFix(
            "gps", false, 5f, 1_000L, true, true
        ))
        assertFalse(PassiveRadioCapturePolicy.acceptsFix(
            "gps", false, 5f, 1_000L, false, false
        ))
    }

    @Test fun passiveCaptureMayContinueWithScreenOffButHonoursBatterySafeguards() {
        val settings = PowerPolicySettings(appSaverEnabled = true, lowBatteryThresholdPercent = 20)
        val screenOff = PowerEnvironment(
            batteryPercent = 80,
            isCharging = false,
            isInteractive = false,
            systemPowerSaveMode = false
        )
        assertTrue(PowerPolicy.evaluate(PowerTask.PASSIVE_CAPTURE, settings, screenOff).allowed)
        assertEquals(
            PowerBlockReason.SCREEN_OFF,
            PowerPolicy.evaluate(PowerTask.OPTIONAL_BACKGROUND, settings, screenOff).blockReason
        )
        assertEquals(
            PowerBlockReason.SYSTEM_POWER_SAVE,
            PowerPolicy.evaluate(
                PowerTask.PASSIVE_CAPTURE,
                settings,
                screenOff.copy(systemPowerSaveMode = true)
            ).blockReason
        )
        assertEquals(
            PowerBlockReason.LOW_BATTERY,
            PowerPolicy.evaluate(
                PowerTask.PASSIVE_CAPTURE,
                settings,
                screenOff.copy(batteryPercent = 20)
            ).blockReason
        )
    }

    @Test fun passiveCaptureUsesIndependentDistanceThresholds() {
        assertTrue(PassiveRadioCapturePolicy.hasMovedEnough(
            52.0003, 5.0, 52.0, 5.0, PassiveRadioCapturePolicy.WIFI_MIN_DISTANCE_METERS
        ))
        assertFalse(PassiveRadioCapturePolicy.hasMovedEnough(
            52.0001, 5.0, 52.0, 5.0, PassiveRadioCapturePolicy.WIFI_MIN_DISTANCE_METERS
        ))
        assertFalse(PassiveRadioCapturePolicy.hasMovedEnough(
            52.0003, 5.0, 52.0, 5.0, PassiveRadioCapturePolicy.CELL_MIN_DISTANCE_METERS
        ))
        assertFalse(PassiveRadioCapturePolicy.hasMovedEnough(
            91.0, 5.0, null, null, PassiveRadioCapturePolicy.WIFI_MIN_DISTANCE_METERS
        ))
    }
}
