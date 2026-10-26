package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioDatabaseRetentionPolicyTest {
    @Test fun supportedLimitsAreBoundedAndStable() {
        assertEquals(listOf(5_000, 10_000, 20_000, 50_000), RadioDatabaseRetentionPolicy.LIMIT_OPTIONS)
        assertEquals(20_000, RadioDatabaseRetentionPolicy.normalizeLimit(20_000))
        assertEquals(20_000, RadioDatabaseRetentionPolicy.normalizeLimit(-1))
        assertEquals(20_000, RadioDatabaseRetentionPolicy.normalizeLimit(20_001))
    }

    @Test fun pruneCountNeverGoesNegativeAndKeepsNewestLimit() {
        assertEquals(0, RadioDatabaseRetentionPolicy.recordsToDelete(0, 5_000))
        assertEquals(0, RadioDatabaseRetentionPolicy.recordsToDelete(4_999, 5_000))
        assertEquals(0, RadioDatabaseRetentionPolicy.recordsToDelete(5_000, 5_000))
        assertEquals(1, RadioDatabaseRetentionPolicy.recordsToDelete(5_001, 5_000))
        assertEquals(15_000, RadioDatabaseRetentionPolicy.recordsToDelete(35_000, 20_000))
        assertTrue(RadioDatabaseRetentionPolicy.LIMIT_OPTIONS.all {
            RadioDatabaseRetentionPolicy.recordsToDelete(60_000, it) in 10_000..55_000
        })
    }

    @Test fun limitsHaveReadableLabels() {
        assertEquals("5 тыс.", RadioDatabaseRetentionPolicy.label(5_000))
        assertEquals("10 тыс.", RadioDatabaseRetentionPolicy.label(10_000))
        assertEquals("20 тыс.", RadioDatabaseRetentionPolicy.label(20_000))
        assertEquals("50 тыс.", RadioDatabaseRetentionPolicy.label(50_000))
        assertEquals("20 тыс.", RadioDatabaseRetentionPolicy.label(0))
    }
}
