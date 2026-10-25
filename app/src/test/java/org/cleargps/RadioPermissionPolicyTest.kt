package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPermissionPolicyTest {
    @Test fun wifiScanRequiresPreciseLocationForThisTargetSdk() {
        assertFalse(RadioPermissionPolicy.canReadWifiScan(false))
        assertTrue(RadioPermissionPolicy.canReadWifiScan(true))
    }

    @Test fun cellIdentifiersRequireFineLocation() {
        assertFalse(RadioPermissionPolicy.canReadCellInfo(false))
        assertTrue(RadioPermissionPolicy.canReadCellInfo(true))
    }

    @Test fun missingPrecisePermissionIsExplainedOnlyWhenAnEnabledSourceNeedsIt() {
        assertTrue(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(false, true, false, false))
        assertTrue(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(false, false, true, false))
        assertTrue(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(false, false, false, true))
        assertFalse(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(false, false, false, false))
        assertFalse(RadioPermissionPolicy.shouldExplainPreciseLocationRequirement(true, true, true, true))
    }
}
