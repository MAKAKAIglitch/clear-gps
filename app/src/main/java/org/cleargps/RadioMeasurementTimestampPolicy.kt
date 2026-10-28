package org.cleargps

/**
 * Keeps a radio fix tied to the original monotonic time of its contributing observations.
 *
 * A wall-clock estimate timestamp is useful for persistence/display, but must never be converted
 * back into elapsed realtime: the wall clock can jump and elapsed realtime resets after reboot.
 */
internal object RadioMeasurementTimestampPolicy {
    fun oldestMatchingEvidenceTimestampNanos(
        signals: List<RadioSignal>,
        matchedRadioTypes: Set<String>,
        collectedAtElapsedRealtimeNanos: Long
    ): Long? {
        if (collectedAtElapsedRealtimeNanos <= 0L || matchedRadioTypes.isEmpty()) return null

        return signals.asSequence()
            .filter { it.type in matchedRadioTypes }
            .mapNotNull { it.observedAtElapsedRealtimeNanos }
            .filter { timestamp -> timestamp > 0L && timestamp <= collectedAtElapsedRealtimeNanos }
            .minOrNull()
    }
}
