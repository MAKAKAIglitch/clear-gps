package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogStoreTest {
    @Test fun sanitizeRemovesRadioIdentifiersAndSsidValues() {
        val raw = "BSSID=aa:bb:cc:dd:ee:ff, SSID=Home WiFi, cell=lte:250:1:123:567890"
        val sanitized = DiagnosticLogStore.sanitizeMessage(raw)

        assertTrue(sanitized.contains("BSSID=[REDACTED]"))
        assertTrue(sanitized.contains("SSID=[REDACTED]"))
        assertTrue(sanitized.contains("[CELL-ID REDACTED]"))
        assertTrue(!sanitized.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(!sanitized.contains("Home WiFi"))
        assertTrue(!sanitized.contains("lte:250:1:123:567890"))
    }

    @Test fun sanitizeRoundsPreciseCoordinatePairsAndLabeledCoordinates() {
        val raw = "located at 52.123456, 40.654321; lat=52.123456 lng=40.654321"
        val sanitized = DiagnosticLogStore.sanitizeMessage(raw)

        assertTrue(!sanitized.contains("52.123456"))
        assertTrue(!sanitized.contains("40.654321"))
        assertTrue(sanitized.contains("52.12, 40.65"))
        assertTrue(sanitized.contains("lat= 52.12"))
        assertTrue(sanitized.contains("lng= 40.65"))
    }

    @Test fun sanitizePreservesNonSensitiveOperationalDetails() {
        val message = "Wi-Fi results=3; RSSI=-58 dBm; radio-only mode active."
        assertEquals(message, DiagnosticLogStore.sanitizeMessage(message))
    }

    @Test fun trimKeepsNewestCompleteLinesWithinLimit() {
        val content = "line-1\nline-2\nline-3\nline-4\n".toByteArray(Charsets.UTF_8)
        val trimmed = DiagnosticLogStore.trimToMaxBytes(content, 15).toString(Charsets.UTF_8)

        assertTrue(trimmed.length <= 15)
        assertTrue(trimmed.endsWith("line-4\n"))
        assertTrue(!trimmed.contains("line-1"))
        assertTrue(trimmed.lines().filter { it.isNotBlank() }.all { it.startsWith("line-") })
    }

    @Test fun trimReturnsShortInputUnchanged() {
        val bytes = "short\n".toByteArray(Charsets.UTF_8)
        assertEquals("short\n", DiagnosticLogStore.trimToMaxBytes(bytes, 64).toString(Charsets.UTF_8))
    }

    @Test fun trimHandlesEmptyInputAndNonPositiveLimit() {
        assertEquals(0, DiagnosticLogStore.trimToMaxBytes(byteArrayOf(), 64).size)
        assertEquals(0, DiagnosticLogStore.trimToMaxBytes("some log".toByteArray(), 0).size)
    }
}
