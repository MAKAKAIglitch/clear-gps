package org.cleargps

/** Pure gate for reboot restoration; never starts monitoring unless it was already enabled. */
internal object BootRecoveryPolicy {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    fun shouldRestoreAfterBoot(
        action: String?,
        monitoringWasEnabled: Boolean,
        hasBackgroundLocationPermission: Boolean
    ): Boolean =
        action == ACTION_BOOT_COMPLETED &&
            monitoringWasEnabled &&
            hasBackgroundLocationPermission
}
