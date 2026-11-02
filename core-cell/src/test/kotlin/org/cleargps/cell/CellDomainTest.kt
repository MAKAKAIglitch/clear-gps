package org.cleargps.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CellDomainTest {
    @Test
    fun preservesMncLeadingZeroes() {
        val id = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "01", 1024, 987654L)!!
        assertEquals("01", id.mnc)
        assertEquals("lte:250:01:1024:987654:known", id.stableKey)
    }

    @Test
    fun legacyNumericMncIsAlwaysWidthAmbiguous() {
        val oldTwoDigit = CellIdentity.fromLegacyIdentifier("lte:250:15:1024:987654")!!
        val oldThreeDigit = CellIdentity.fromLegacyIdentifier("lte:250:1:1024:987654")!!
        val currentTwoDigit = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "15", 1024, 987654L)!!
        val currentThreeDigit = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "015", 1024, 987654L)!!

        assertFalse(oldTwoDigit.mncLengthKnown)
        assertFalse(oldThreeDigit.mncLengthKnown)
        assertEquals(CellIdentityMatch.POSSIBLE_MNC_LENGTH_MATCH, oldTwoDigit.compare(currentThreeDigit))
        assertEquals(CellIdentityMatch.POSSIBLE_MNC_LENGTH_MATCH, oldThreeDigit.compare(currentTwoDigit))
    }

    @Test
    fun differentKnownMncWidthsNeverMerge() {
        val twoDigit = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "01", 1, 9L)!!
        val threeDigit = CellIdentity.fromAndroidStrings(CellTechnology.LTE, "250", "001", 1, 9L)!!
        assertEquals(CellIdentityMatch.DIFFERENT, twoDigit.compare(threeDigit))
    }
}
