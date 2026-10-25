package org.cleargps

/** Permission rules for radio sources that the app uses to derive physical position. */
internal object RadioPermissionPolicy {
    /**
     * This app targets Android 10+ SDKs, where WifiManager scan results require
     * ACCESS_FINE_LOCATION, even if a coarse permission was also granted.
     */
    fun canReadWifiScan(fineLocationGranted: Boolean): Boolean = fineLocationGranted

    /** TelephonyManager.getAllCellInfo() requires ACCESS_FINE_LOCATION. */
    fun canReadCellInfo(fineLocationGranted: Boolean): Boolean = fineLocationGranted

    fun shouldExplainPreciseLocationRequirement(
        fineLocationGranted: Boolean,
        useWifi: Boolean,
        useCells: Boolean,
        useBle: Boolean
    ): Boolean =
        !fineLocationGranted && (useWifi || useCells || useBle)
}
