package org.cleargps

internal enum class AutoPositionMode { GPS_OK, GPS_RADIO }

internal enum class AutoPositionAction {
    NONE, ENTER_RADIO, START_GPS_PROBE, STOP_GPS_PROBE, RETURN_TO_GPS
}

internal data class AutoPositionModeState(
    val mode: AutoPositionMode = AutoPositionMode.GPS_OK,
    val degradedSinceElapsedMs: Long? = null,
    val nextGpsProbeElapsedMs: Long? = null,
    val gpsProbeStartedElapsedMs: Long? = null,
    val gpsProbeDeadlineElapsedMs: Long? = null
) {
    val isGpsProbeActive: Boolean
        get() = mode == AutoPositionMode.GPS_RADIO &&
            gpsProbeStartedElapsedMs != null && gpsProbeDeadlineElapsedMs != null
}

internal data class AutoPositionTransition(val state: AutoPositionModeState, val action: AutoPositionAction)

/** Time-based GPS/RADIO hysteresis. Probe recovery requires a new physical GPS fix. */
internal object AutoPositionModePolicy {
    const val GPS_DEGRADED_HOLD_MS = 10_000L
    const val INITIAL_GPS_ACQUISITION_TIMEOUT_MS = 30_000L
    const val GPS_PROBE_INTERVAL_MS = 120_000L
    const val GPS_PROBE_WINDOW_MS = 30_000L
    const val STATE_CHECK_INTERVAL_MS = 1_000L

    fun initialState() = AutoPositionModeState()

    fun update(
        state: AutoPositionModeState,
        nowElapsedRealtimeMs: Long,
        gpsTrusted: Boolean,
        gpsFixEverReceived: Boolean,
        autoModeStartedElapsedRealtimeMs: Long,
        gpsFixReceivedAfterProbe: Boolean = false,
        initialGpsAcquisitionTimeoutMs: Long = INITIAL_GPS_ACQUISITION_TIMEOUT_MS
    ): AutoPositionTransition {
        if (nowElapsedRealtimeMs < 0L ||
            initialGpsAcquisitionTimeoutMs < INITIAL_GPS_ACQUISITION_TIMEOUT_MS
        ) return AutoPositionTransition(state, AutoPositionAction.NONE)

        if (state.mode == AutoPositionMode.GPS_OK) {
            if (gpsTrusted) {
                return AutoPositionTransition(state.copy(degradedSinceElapsedMs = null), AutoPositionAction.NONE)
            }
            val degradedSince = state.degradedSinceElapsedMs ?: nowElapsedRealtimeMs
            val timerStartedAt = if (gpsFixEverReceived) degradedSince else autoModeStartedElapsedRealtimeMs.coerceAtLeast(0L)
            val holdMillis = if (gpsFixEverReceived) GPS_DEGRADED_HOLD_MS else initialGpsAcquisitionTimeoutMs
            val elapsed = if (nowElapsedRealtimeMs >= timerStartedAt) nowElapsedRealtimeMs - timerStartedAt else 0L
            if (elapsed < holdMillis) {
                return AutoPositionTransition(state.copy(degradedSinceElapsedMs = degradedSince), AutoPositionAction.NONE)
            }
            return AutoPositionTransition(
                AutoPositionModeState(
                    mode = AutoPositionMode.GPS_RADIO,
                    nextGpsProbeElapsedMs = safeAdd(nowElapsedRealtimeMs, GPS_PROBE_INTERVAL_MS)
                ),
                AutoPositionAction.ENTER_RADIO
            )
        }

        val probeStart = state.gpsProbeStartedElapsedMs
        if (probeStart != null) {
            val deadline = state.gpsProbeDeadlineElapsedMs ?: safeAdd(probeStart, GPS_PROBE_WINDOW_MS)
            if (nowElapsedRealtimeMs < deadline && gpsTrusted && gpsFixReceivedAfterProbe) {
                return AutoPositionTransition(initialState(), AutoPositionAction.RETURN_TO_GPS)
            }
            if (nowElapsedRealtimeMs >= deadline) {
                val nextProbe = maxOf(safeAdd(probeStart, GPS_PROBE_INTERVAL_MS), safeAdd(nowElapsedRealtimeMs, 1L))
                return AutoPositionTransition(
                    state.copy(
                        nextGpsProbeElapsedMs = nextProbe,
                        gpsProbeStartedElapsedMs = null,
                        gpsProbeDeadlineElapsedMs = null
                    ),
                    AutoPositionAction.STOP_GPS_PROBE
                )
            }
            return AutoPositionTransition(state, AutoPositionAction.NONE)
        }

        val nextProbe = state.nextGpsProbeElapsedMs ?: safeAdd(nowElapsedRealtimeMs, GPS_PROBE_INTERVAL_MS)
        if (nowElapsedRealtimeMs < nextProbe) return AutoPositionTransition(state, AutoPositionAction.NONE)
        return AutoPositionTransition(
            state.copy(
                nextGpsProbeElapsedMs = safeAdd(nowElapsedRealtimeMs, GPS_PROBE_INTERVAL_MS),
                gpsProbeStartedElapsedMs = nowElapsedRealtimeMs,
                gpsProbeDeadlineElapsedMs = safeAdd(nowElapsedRealtimeMs, GPS_PROBE_WINDOW_MS)
            ),
            AutoPositionAction.START_GPS_PROBE
        )
    }

    private fun safeAdd(value: Long, delta: Long) =
        if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta
}
