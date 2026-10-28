package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioMeasurementTimestampPolicyTest {
    @Test
    fun selectsOldestRealTimestampOnlyFromExactMatchedIdentifiers() {
        val collectedAt = 10_000_000_000L
        val signals = listOf(
            RadioSignal("wifi", "matched-wifi-newer", -50,
                observedAtElapsedRealtimeNanos = 9_000_000_000L),
            RadioSignal("wifi", "matched-wifi-older", -68,
                observedAtElapsedRealtimeNanos = 7_000_000_000L),
            RadioSignal("cell", "matched-cell", -91,
                observedAtElapsedRealtimeNanos = 8_000_000_000L),
            // Same identifier text in another radio technology is a different key.
            RadioSignal("ble", "matched-wifi-newer", -55,
                observedAtElapsedRealtimeNanos = 1_000_000_000L),
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
                    "wifi|matched-wifi-newer", "wifi|matched-wifi-older", "cell|matched-cell"
                ),
                collectedAtElapsedRealtimeNanos = collectedAt
            )
        )
    }

    @Test
    fun canonicalizesMacIdentifiersBeforeSelectingEvidenceTimestamp() {
        val result = RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals = listOf(
                RadioSignal(
                    "wifi",
                    "AA-BB-CC-DD-EE-FF",
                    -58,
                    observedAtElapsedRealtimeNanos = 6_000_000_000L
                )
            ),
            matchedRadioIdentifiers = setOf("wifi|aa:bb:cc:dd:ee:ff"),
            collectedAtElapsedRealtimeNanos = 10_000_000_000L
        )

        assertEquals(6_000_000_000L, result)
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
            signals, setOf("wifi|unknown-id"), 10L
        ))
        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals, setOf("ble|future-time"), 10L
        ))
        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals, setOf("wifi|unknown-id"), 0L
        ))
    }

    @Test
    fun rejectsEstimateWhenAnyMatchedSignalHasNoUsableTimestamp() {
        val signals = listOf(
            RadioSignal("wifi", "timed-ap", -58,
                observedAtElapsedRealtimeNanos = 9_000_000_000L),
            RadioSignal("cell", "untimed-cell", -90)
        )

        assertNull(RadioMeasurementTimestampPolicy.oldestMatchingEvidenceTimestampNanos(
            signals = signals,
            matchedRadioIdentifiers = setOf("wifi|timed-ap", "cell|untimed-cell"),
            collectedAtElapsedRealtimeNanos = 10_000_000_000L
        ))
    }
}
