package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRadioPositionSearchPolicyTest {
    @Test
    fun foregroundRadioOnlySearchRunsWithoutMonitoring() {
        assertTrue(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = true,
                monitoring = false,
                positionModeKey = "radio",
                hasActiveAndroidLocation = false,
                hasLocationPermission = true
            )
        )
    }

    @Test
    fun foregroundAutoSearchRunsOnlyWhenAndroidLocationIsUnavailable() {
        assertTrue(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = true,
                monitoring = false,
                positionModeKey = "auto",
                hasActiveAndroidLocation = false,
                hasLocationPermission = true
            )
        )
        assertFalse(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = true,
                monitoring = false,
                positionModeKey = "auto",
                hasActiveAndroidLocation = true,
                hasLocationPermission = true
            )
        )
    }

    @Test
    fun foregroundSearchStopsInBackgroundGpsOnlyOrWhenServiceOwnsScans() {
        assertFalse(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = false,
                monitoring = false,
                positionModeKey = "radio",
                hasActiveAndroidLocation = false,
                hasLocationPermission = true
            )
        )
        assertFalse(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = true,
                monitoring = true,
                positionModeKey = "radio",
                hasActiveAndroidLocation = false,
                hasLocationPermission = true
            )
        )
        assertFalse(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = true,
                monitoring = false,
                positionModeKey = "gps",
                hasActiveAndroidLocation = false,
                hasLocationPermission = true
            )
        )
        assertFalse(
            ForegroundRadioPositionSearchPolicy.shouldSearch(
                appVisible = true,
                monitoring = false,
                positionModeKey = "radio",
                hasActiveAndroidLocation = false,
                hasLocationPermission = false
            )
        )
    }

    @Test
    fun mapCentreRefreshesRadioOnlyOrAutoFallbackButNotGpsOrHealthyAuto() {
        assertTrue(ForegroundRadioPositionSearchPolicy.shouldRefreshMapCenter("radio", true))
        assertTrue(ForegroundRadioPositionSearchPolicy.shouldRefreshMapCenter("auto", false))
        assertTrue(
            ForegroundRadioPositionSearchPolicy.shouldRefreshMapCenter(
                positionModeKey = "auto",
                hasActiveAndroidLocation = true,
                autoRadioFallbackActive = true
            )
        )
        assertFalse(
            ForegroundRadioPositionSearchPolicy.shouldRefreshMapCenter(
                positionModeKey = "auto",
                hasActiveAndroidLocation = true,
                autoRadioFallbackActive = false
            )
        )
        assertFalse(ForegroundRadioPositionSearchPolicy.shouldRefreshMapCenter("gps", false))
    }

    @Test
    fun periodicSearchIntervalIsOneMinute() {
        assertEquals(60_000L, ForegroundRadioPositionSearchPolicy.INTERVAL_MILLIS)
    }

    @Test
    fun openCellIdFallbackRequiresNoFreshEstimateAndEveryConsentGate() {
        fun allowed(
            hasCurrentEstimate: Boolean = false,
            useCells: Boolean = true,
            sourceEnabled: Boolean = true,
            networkEnabled: Boolean = true,
            apiKeyAvailable: Boolean = true,
            fineLocationGranted: Boolean = true,
            safeMode: Boolean = false
        ) = ForegroundRadioPositionSearchPolicy.shouldUseOpenCellIdFallback(
            hasCurrentEstimate = hasCurrentEstimate,
            useCells = useCells,
            sourceEnabled = sourceEnabled,
            networkEnabled = networkEnabled,
            apiKeyAvailable = apiKeyAvailable,
            fineLocationGranted = fineLocationGranted,
            safeMode = safeMode
        )

        assertTrue(allowed())
        assertFalse(allowed(hasCurrentEstimate = true))
        assertFalse(allowed(useCells = false))
        assertFalse(allowed(sourceEnabled = false))
        assertFalse(allowed(networkEnabled = false))
        assertFalse(allowed(apiKeyAvailable = false))
        assertFalse(allowed(fineLocationGranted = false))
        assertFalse(allowed(safeMode = true))
    }
}
