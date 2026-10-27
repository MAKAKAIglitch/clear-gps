package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRecoveryPolicyTest {
    @Test
    fun restoresOnlyAfterBootWhenMonitoringWasAlreadyEnabledAndPermissionExists() {
        assertTrue(
            BootRecoveryPolicy.shouldRestoreAfterBoot(
                BootRecoveryPolicy.ACTION_BOOT_COMPLETED,
                monitoringWasEnabled = true,
                hasBackgroundLocationPermission = true
            )
        )
    }

    @Test
    fun doesNotStartMonitoringAfterBootWhenItWasPreviouslyStopped() {
        assertFalse(
            BootRecoveryPolicy.shouldRestoreAfterBoot(
                BootRecoveryPolicy.ACTION_BOOT_COMPLETED,
                monitoringWasEnabled = false,
                hasBackgroundLocationPermission = true
            )
        )
    }

    @Test
    fun doesNotStartMonitoringWithoutBackgroundLocationPermission() {
        assertFalse(
            BootRecoveryPolicy.shouldRestoreAfterBoot(
                BootRecoveryPolicy.ACTION_BOOT_COMPLETED,
                monitoringWasEnabled = true,
                hasBackgroundLocationPermission = false
            )
        )
    }

    @Test
    fun ignoresUnrelatedBroadcasts() {
        assertFalse(
            BootRecoveryPolicy.shouldRestoreAfterBoot(
                "android.intent.action.TIMEZONE_CHANGED",
                monitoringWasEnabled = true,
                hasBackgroundLocationPermission = true
            )
        )
        assertFalse(
            BootRecoveryPolicy.shouldRestoreAfterBoot(
                null,
                monitoringWasEnabled = true,
                hasBackgroundLocationPermission = true
            )
        )
    }
}
