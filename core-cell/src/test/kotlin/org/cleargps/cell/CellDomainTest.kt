package org.cleargps.cell

import org.junit.Assert.*
import org.junit.Test

class CellDomainTest {
    @Test fun preservesMncLeadingZeroes() {
        val id = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "01", 1024, 987654L)!!
        assertEquals("01", id.mnc)
        assertEquals("lte:250:01:1024:987654:known", id.stableKey)
    }

    @Test fun legacyNumericMncIsAmbiguousNotPadded() {
        val old = CellIdentity.fromLegacyIdentifier("lte:250:1:1024:987654")!!
        val current = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "01", 1024, 987654L)!!
        assertFalse(old.mncLengthKnown)
        assertEquals(CellIdentityMatch.POSSIBLE_MNC_LENGTH_MATCH, old.compare(current))
    }

    @Test fun differentKnownMncWidthsNeverMerge() {
        val a = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "01", 1, 9L)!!
        val b = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "001", 1, 9L)!!
        assertEquals(CellIdentityMatch.DIFFERENT, a.compare(b))
    }
}
