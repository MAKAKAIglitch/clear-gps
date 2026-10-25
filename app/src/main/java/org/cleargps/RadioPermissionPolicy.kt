package org.cleargps

/** Platform permission rules for radio sources that Android uses to reveal nearby location data. */
internal object RadioPermissionPolicy {
    /** Android 10+ requires ACCESS_FINE_LOCATION for WifiManager scan results. */
    fun canReadWifiScan(sdkInt: Int, fineLocationGranted: Boolean, coarseLocationGranted: Boolean): Boolean =
        fineLocationGranted || (sdkInt < 29 && coarseLocationGranted)

    /** TelephonyManager.getAllCellInfo() requires ACCESS_FINE_LOCATION. */
    fun canReadCellInfo(fineLocationGranted: Boolean): Boolean = fineLocationGranted

    fun shouldExplainPreciseLocationRequirement(
        sdkInt: Int,
        fineLocationGranted: Boolean,
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean
    ): Boolean =
        !fineLocationGranted && (useCells || useBle || (sdkInt >= 29 && useWifi))
}
