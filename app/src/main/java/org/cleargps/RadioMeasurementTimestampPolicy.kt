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
        matchedRadioIdentifiers: Set<String>,
        collectedAtElapsedRealtimeNanos: Long
    ): Long? {
        if (collectedAtElapsedRealtimeNanos <= 0L || matchedRadioIdentifiers.isEmpty()) return null

        val matchingSignals = signals.filter { signal ->
            "${signal.type}|${canonicalRadioIdentifier(signal.identifier)}" in matchedRadioIdentifiers
        }
        val observedKeys = matchingSignals.map { signal ->
            "${signal.type}|${canonicalRadioIdentifier(signal.identifier)}"
        }.toSet()
        // Every item used by the estimate must have a timestamp. Ignoring an unknown timestamp
        // could make a different, fresher radio sample falsely represent the whole estimate.
        if (!observedKeys.containsAll(matchedRadioIdentifiers)) return null

        val timestamps = matchingSignals.map { it.observedAtElapsedRealtimeNanos ?: return null }
        if (timestamps.isEmpty() || timestamps.size != matchingSignals.size ||
            timestamps.any { it <= 0L || it > collectedAtElapsedRealtimeNanos }
        ) return null
        return timestamps.minOrNull()
    }
}
