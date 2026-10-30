package org.cleargps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCellIdLookupPolicyTest {
    @Test fun successfulLookupsAreReusedForThirtyDays() {
        val record = OpenCellIdCellRecord(
            OpenCellIdCellKey("LTE", 310, 260, 404, 12345L),
            51.5, -0.1, 500, 4, false, 1_000L, "found"
        )
        assertFalse(OpenCellIdLookupPolicy.shouldLookup(record, 2_000L))
        assertTrue(OpenCellIdLookupPolicy.shouldLookup(
            record, 1_000L + OpenCellIdLookupPolicy.SUCCESS_TTL_MILLIS
        ))
    }

    @Test fun negativeLookupsExpireAfterSevenDays() {
        val record = OpenCellIdCellRecord(
            OpenCellIdCellKey("LTE", 310, 260, 404, 12345L),
            null, null, null, 0, false, 1_000L, "not_found"
        )
        assertFalse(OpenCellIdLookupPolicy.shouldLookup(record, 61_000L))
        assertTrue(OpenCellIdLookupPolicy.shouldLookup(
            record, 1_000L + OpenCellIdLookupPolicy.NOT_FOUND_TTL_MILLIS
        ))
    }

    @Test fun cachedTowerCoordinatesCanBeUsedForNinetyDaysThenExpire() {
        val record = OpenCellIdCellRecord(
            OpenCellIdCellKey("LTE", 234, 15, 100, 55221L),
            51.5, -0.12, 800, 7, false, 10_000L, "found"
        )
        assertTrue(OpenCellIdLookupPolicy.shouldUseForPosition(
            record, 10_000L + OpenCellIdLookupPolicy.POSITION_MAX_AGE_MILLIS
        ))
        assertFalse(OpenCellIdLookupPolicy.shouldUseForPosition(
            record, 10_001L + OpenCellIdLookupPolicy.POSITION_MAX_AGE_MILLIS
        ))
        assertFalse(OpenCellIdLookupPolicy.shouldUseForPosition(record.copy(status = "not_found"), 10_000L))
    }

    @Test fun unknownOrFutureCacheTimestampsAreRefreshed() {
        val record = OpenCellIdCellRecord(
            OpenCellIdCellKey("GSM", 234, 15, 1, 2L),
            null, null, null, 0, false, 0L, "not_found"
        )
        assertTrue(OpenCellIdLookupPolicy.shouldLookup(record, 5_000L))
        assertTrue(OpenCellIdLookupPolicy.shouldLookup(record.copy(checkedAtMillis = 10_000L), 5_000L))
    }
}
