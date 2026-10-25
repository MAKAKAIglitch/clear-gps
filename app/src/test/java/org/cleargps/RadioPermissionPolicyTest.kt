package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPermissionPolicyTest {
    @Test fun approximatePermissionIsInsufficientForWifiOnAndroid10AndNewer() {
        assertFalse(RadioPermissionPolicy.canReadWifiScan(29, false, true))
        assertFalse(RadioPermissionPolicy.canReadWifiScan(35, false, true))
        assertTrue(RadioPermissionPolicy.canReadWifiScan(35, true, true))
    }

    @Test fun olderAndroidCanUseCoarseLocationForWifiScanResults() {
        assertTrue(RadioPermissionPolicy.canReadWifiScan(28, false, true))
        assertFalse(RadioPermissionPolicy.canReadWifiScan(28, false, false))
        assertTrue(RadioPermissionPolicy.canReadWifiScan(28, true, false))
    }

    @Test fun cellIdentifiersRequireFineLocation() {
        assertFalse(RadioPermissionPolicy.canReadCellInfo(false))
        assertTrue(RadioPermissionPolicy.canReadCellInfo(true))
    }

    @Test fun missingPrecisePermissionIsExplainedOnlyWhenEnabledSourceNeedsIt() {
        assertTrue(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(35, false, true, false, false))
        assertTrue(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(28, false, false, true, false))
        assertTrue(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(28, false, false, false, true))
        assertFalse(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(28, false, true, false, false))
        assertFalse(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(35, true, true, true, true))
    }
}
