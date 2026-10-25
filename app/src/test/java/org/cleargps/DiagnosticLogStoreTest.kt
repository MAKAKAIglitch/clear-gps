package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogStoreTest {
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
