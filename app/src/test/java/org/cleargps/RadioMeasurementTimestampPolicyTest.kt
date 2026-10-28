package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioMeasurementTimestampPolicyTest {
    @Test
    fun selectsOldestRealTimestampOnlyFromMatchedRadioTypes() {
        val collectedAt = 10_000_000_000L
        val signals = listOf(
            RadioSignal("wifi", "matched-wifi-newer", -50,
                observedAtElapsedRealtimeNanos = 9_000_000_000L),
            RadioSignal("wifi", "matched-wifi-older", -68,
                observedAtElapsedRealtimeNanos = 7_000_000_000L),
            RadioSignal("cell", "matched-cell", -91,
                observedAtElapsedRealtimeNanos = 8_000_000_000L),
            // An unrelated old signal of a matched radio type must not make this fix look older.
            RadioSignal("wifi", "unmatched-wifi-stale", -88,
                observedAtElapsedRealtimeNanos = 1_000_000_000L),
            RadioSignal("ble", "unmatched-ble", -55,
                observedAtElapsedRealtimeNanos = 1_000_000_000L),
            RadioSignal("wifi", "future-wifi", -40,
                observedAtElapsedRealtimeNanos = 11_000_000_000L),
            RadioSignal("wifi", "unknown-time-wifi", -74)
        )

        assertEquals(
            7_000_000_000L,
            RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
                signals = signals,
                matchedRadioIdentifiers = setOf(
                    "matched-wifi-newer", "matched-wifi-older", "matched-cell"
                ),
                collectedAtElapsedRealtimeNanos = collectedAt
            )
        )
    }

    @Test
    fun returnsNoTimestampWhenEvidenceOrClockIsUnavailable() {
        val signals = listOf(
            RadioSignal("wifi", "unknown-time", -60),
            RadioSignal("ble", "future-time", -55, observedAtElapsedRealtimeNanos = 20L)
        )

        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals, emptySet(), 10L
        ))
        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals, setOf("unknown-id"), 10L
        ))
        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals, setOf("future-time"), 10L
        ))
        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals, setOf("unknown-id"), 0L
        ))
    }
}
