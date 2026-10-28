package org.cleargps.fusion

/**
 * Bounded chronological wrapper around [PositionFusionEngine].
 *
 * Android radio results can arrive after a newer GNSS callback even though the radio evidence
 * itself was measured earlier. A plain sequential filter rejects that sample. This timeline
 * retains a short, bounded set of absolute measurements and replays it in measurement-time order
 * when a late sample arrives. Only measurements accepted during replay are retained.
 *
 * This class does not establish source trust. Callers must validate GNSS, radio and other sources
 * before submitting them. It also does not turn IMU/OBD data into absolute positions.
 */
class PositionFusionTimeline(
    private val config: PositionFusionConfig = PositionFusionConfig(),
    private val historyWindowNanos: Long = DEFAULT_HISTORY_WINDOW_NANOS,
    private val maxMeasurements: Int = DEFAULT_MAX_MEASUREMENTS
) {
    private data class Entry(
        val id: Long,
        val measurement: PositionMeasurement
    )

    private var nextId = 1L
    private var history: List<Entry> = emptyList()
    private var engine = PositionFusionEngine(config)

    init {
        require(historyWindowNanos > 0L)
        require(maxMeasurements >= 1)
    }

    @Synchronized
    fun reset() {
        history = emptyList()
        engine = PositionFusionEngine(config)
        nextId = 1L
    }

    @Synchronized
    fun update(measurement: PositionMeasurement): PositionFusionUpdateResult {
        // Reuse the engine's input validation without mutating the retained timeline.
        val validation = PositionFusionEngine(config).update(measurement)
        if (validation.status == PositionFusionUpdateStatus.REJECTED) return validation

        val newestRetainedTimestamp = history.lastOrNull()?.measurement?.elapsedRealtimeNanos ?: 0L
        if (newestRetainedTimestamp > 0L &&
            measurement.elapsedRealtimeNanos <= newestRetainedTimestamp - historyWindowNanos
        ) {
            return rejected(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP)
        }
        if (history.any { it.measurement.elapsedRealtimeNanos == measurement.elapsedRealtimeNanos }) {
            return rejected(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP)
        }

        val newestTimestamp = maxOf(newestRetainedTimestamp, measurement.elapsedRealtimeNanos)
        val cutoff = (newestTimestamp - historyWindowNanos).coerceAtLeast(0L)
        val candidateId = nextId++
        val candidate = Entry(candidateId, measurement)
        val ordered = (history.filter {
            it.measurement.elapsedRealtimeNanos >= cutoff
        } + candidate).sortedBy { it.measurement.elapsedRealtimeNanos }

        // Keep memory and replay work bounded. An old candidate that would be trimmed is not useful.
        val bounded = if (ordered.size > maxMeasurements) ordered.takeLast(maxMeasurements) else ordered
        if (bounded.none { it.id == candidateId }) {
            return rejected(PositionFusionRejectionReason.OUT_OF_ORDER_TIMESTAMP)
        }

        val rebuiltEngine = PositionFusionEngine(config)
        val accepted = ArrayList<Entry>(bounded.size)
        var candidateResult: PositionFusionUpdateResult? = null
        for (entry in bounded) {
            val result = rebuiltEngine.update(entry.measurement)
            if (entry.id == candidateId) candidateResult = result
            if (result.status != PositionFusionUpdateStatus.REJECTED) accepted += entry
        }

        val incomingResult = candidateResult
            ?: return rejected(PositionFusionRejectionReason.NUMERIC_FAILURE)
        if (incomingResult.status == PositionFusionUpdateStatus.REJECTED) {
            // A rejected candidate must not perturb either the live engine or its accepted history.
            return incomingResult
        }

        history = accepted
        engine = rebuiltEngine
        val latestAcceptedTimestamp = history.lastOrNull()?.measurement?.elapsedRealtimeNanos
            ?: measurement.elapsedRealtimeNanos
        val arrivedOutOfOrder = newestRetainedTimestamp > measurement.elapsedRealtimeNanos
        return PositionFusionUpdateResult(
            status = if (arrivedOutOfOrder) {
                PositionFusionUpdateStatus.REPLAYED_OUT_OF_ORDER
            } else {
                incomingResult.status
            },
            estimate = engine.estimate(latestAcceptedTimestamp)
        )
    }

    @Synchronized
    fun estimate(nowElapsedRealtimeNanos: Long): FusedPosition? =
        engine.estimate(nowElapsedRealtimeNanos)

    @Synchronized
    fun retainedMeasurementCount(): Int = history.size

    private fun rejected(reason: PositionFusionRejectionReason) =
        PositionFusionUpdateResult(
            status = PositionFusionUpdateStatus.REJECTED,
            rejectionReason = reason
        )

    private companion object {
        const val DEFAULT_HISTORY_WINDOW_NANOS = 45_000_000_000L
        const val DEFAULT_MAX_MEASUREMENTS = 128
    }
}
