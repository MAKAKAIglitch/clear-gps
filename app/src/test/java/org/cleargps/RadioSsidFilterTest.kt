package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSsidFilterTest {
    @Test
    fun defaultRuleMatchesMobileHotspotNamesCaseInsensitively() {
        assertTrue(RadioSsidFilter.excludes("wifi", "70mai_123456", RadioSsidFilter.DEFAULT_PATTERNS))
        assertTrue(RadioSsidFilter.excludes("WIFI", "70MAI-CAR", RadioSsidFilter.DEFAULT_PATTERNS))
        assertFalse(RadioSsidFilter.excludes("wifi", "Home-WiFi", RadioSsidFilter.DEFAULT_PATTERNS))
    }

    @Test
    fun supportsMultiplePatternsAndSingleCharacterWildcard() {
        val patterns = "70mai*, guest-?, office-*"
        assertTrue(RadioSsidFilter.excludes("wifi", "guest-A", patterns))
        assertTrue(RadioSsidFilter.excludes("wifi", "office-router", patterns))
        assertFalse(RadioSsidFilter.excludes("wifi", "guest-AB", patterns))
        assertFalse(RadioSsidFilter.excludes("wifi", "guest-", patterns))
    }

    @Test
    fun appliesOnlyToWifiSignals() {
        val patterns = "70mai*"
        assertFalse(RadioSsidFilter.excludes("cell", "70mai-network", patterns))
        assertFalse(RadioSsidFilter.excludes("ble", "70mai-beacon", patterns))
        assertTrue(RadioSsidFilter.excludes("wifi", "70mai-hotspot", patterns))
    }

    @Test
    fun emptyOrUnknownSsidIsNotFiltered() {
        assertFalse(RadioSsidFilter.excludes("wifi", "", RadioSsidFilter.DEFAULT_PATTERNS))
        assertFalse(RadioSsidFilter.excludes("wifi", "   ", RadioSsidFilter.DEFAULT_PATTERNS))
        assertFalse(RadioSsidFilter.excludes("wifi", "70mai-router", ""))
    }

    @Test
    fun parsingIsBoundedAndIgnoresEmptyOrOversizedPatterns() {
        assertEquals(
            listOf("70mai*", "guest-*"),
            RadioSsidFilter.parsePatterns(" ,70mai*,,\nguest-*;xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
        )
        val tooMany = (1..100).joinToString(",") { "ssid-$it" }
        assertEquals(RadioSsidFilter.MAX_PATTERN_COUNT, RadioSsidFilter.parsePatterns(tooMany).size)
        assertFalse(RadioSsidFilter.parsePatterns("x".repeat(513) + ",valid").contains("valid"))
    }

    @Test
    fun regexMetacharactersAreTreatedLiterally() {
        assertTrue(RadioSsidFilter.excludes("wifi", "router.[1]-guest", "router.[1]*"))
        assertFalse(RadioSsidFilter.excludes("wifi", "routerX1]-guest", "router.[1]*"))
    }
}
