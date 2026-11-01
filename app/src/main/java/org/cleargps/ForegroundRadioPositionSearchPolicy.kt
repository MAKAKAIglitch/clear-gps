package org.cleargps

/**
 * Controls the foreground-only position refresh introduced by roadmap items P2.2 and P2.7.
 * Scheduled foreground-service scans keep ownership whenever monitoring is enabled.
 */
internal object ForegroundRadioPositionSearchPolicy {
    const val INTERVAL_MILLIS = 60_000L

    private const val AUTO_MODE = "auto"
    private const val RADIO_MODE = "radio"
    private const val LEGACY_RADIO_MODE = "radio_obd2"

    fun shouldRefreshMapCenter(
        positionModeKey: String,
        hasActiveAndroidLocation: Boolean,
        autoRadioFallbackActive: Boolean = false
    ): Boolean = when (positionModeKey) {
        RADIO_MODE, LEGACY_RADIO_MODE -> true
        AUTO_MODE -> autoRadioFallbackActive || !hasActiveAndroidLocation
        else -> false
    }

    fun shouldSearch(
        appVisible: Boolean,
        monitoring: Boolean,
        positionModeKey: String,
        hasActiveAndroidLocation: Boolean,
        hasLocationPermission: Boolean,
        autoRadioFallbackActive: Boolean = false
    ): Boolean =
        appVisible &&
            !monitoring &&
            hasLocationPermission &&
            shouldRefreshMapCenter(positionModeKey, hasActiveAndroidLocation, autoRadioFallbackActive)

    fun shouldUseOpenCellIdFallback(
        hasCurrentEstimate: Boolean,
        useCells: Boolean,
        sourceEnabled: Boolean,
        networkEnabled: Boolean,
        apiKeyAvailable: Boolean,
        fineLocationGranted: Boolean,
        safeMode: Boolean
    ): Boolean =
        !hasCurrentEstimate &&
            useCells &&
            sourceEnabled &&
            networkEnabled &&
            apiKeyAvailable &&
            fineLocationGranted &&
            !safeMode
}
