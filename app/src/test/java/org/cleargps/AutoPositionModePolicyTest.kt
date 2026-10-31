package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPositionModePolicyTest {
    @Test
    fun trustedGpsClearsDegradedTimer() {
        val result = AutoPositionModePolicy.update(AutoPositionModeState(degradedSinceElapsedMs = 1_000L), 9_000L, true, true, 0L)
        assertEquals(AutoPositionMode.GPS_OK, result.state.mode)
        assertNull(result.state.degradedSinceElapsedMs)
        assertEquals(AutoPositionAction.NONE, result.action)
    }

    @Test
    fun degradedGpsNeedsTenSecondsBeforeRadioTakeover() {
        val first = AutoPositionModePolicy.update(AutoPositionModePolicy.initialState(), 0L, false, true, 0L)
        val before = AutoPositionModePolicy.update(first.state, 9_999L, false, true, 0L)
        assertEquals(AutoPositionMode.GPS_OK, before.state.mode)
        assertEquals(AutoPositionAction.NONE, before.action)
        val due = AutoPositionModePolicy.update(before.state, 10_000L, false, true, 0L)
        assertEquals(AutoPositionMode.GPS_RADIO, due.state.mode)
        assertEquals(AutoPositionAction.ENTER_RADIO, due.action)
        assertEquals(130_000L, due.state.nextGpsProbeElapsedMs)
    }

    @Test
    fun firstGpsAcquisitionGetsThirtySecondsBeforeRadioFallback() {
        val before = AutoPositionModePolicy.update(AutoPositionModePolicy.initialState(), 29_999L, false, false, 0L)
        assertEquals(AutoPositionMode.GPS_OK, before.state.mode)
        assertEquals(AutoPositionAction.NONE, before.action)
        val due = AutoPositionModePolicy.update(before.state, 30_000L, false, false, 0L)
        assertEquals(AutoPositionMode.GPS_RADIO, due.state.mode)
        assertEquals(AutoPositionAction.ENTER_RADIO, due.action)
    }

    @Test
    fun firstGpsGraceCanExpandToTwiceConfiguredUpdateInterval() {
        val before = AutoPositionModePolicy.update(
            AutoPositionModePolicy.initialState(), 59_999L, false, false, 0L,
            initialGpsAcquisitionTimeoutMs = 60_000L
        )
        assertEquals(AutoPositionMode.GPS_OK, before.state.mode)
        val due = AutoPositionModePolicy.update(
            before.state, 60_000L, false, false, 0L,
            initialGpsAcquisitionTimeoutMs = 60_000L
        )
        assertEquals(AutoPositionAction.ENTER_RADIO, due.action)
    }

    @Test
    fun recoveryBetweenBadSamplesResetsDegradedTimer() {
        val bad = AutoPositionModePolicy.update(AutoPositionModePolicy.initialState(), 1_000L, false, true, 0L)
        val good = AutoPositionModePolicy.update(bad.state, 8_000L, true, true, 0L)
        val again = AutoPositionModePolicy.update(good.state, 15_000L, false, true, 0L)
        val before = AutoPositionModePolicy.update(again.state, 24_999L, false, true, 0L)
        assertEquals(AutoPositionMode.GPS_OK, before.state.mode)
        assertEquals(15_000L, before.state.degradedSinceElapsedMs)
    }

    @Test
    fun gpsProbeStartsAtTwoMinuteCadence() {
        val radio = AutoPositionModeState(mode = AutoPositionMode.GPS_RADIO, nextGpsProbeElapsedMs = 120_000L)
        val before = AutoPositionModePolicy.update(radio, 119_999L, false, true, 0L)
        assertEquals(AutoPositionAction.NONE, before.action)
        assertFalse(before.state.isGpsProbeActive)
        val due = AutoPositionModePolicy.update(before.state, 120_000L, false, true, 0L)
        assertEquals(AutoPositionAction.START_GPS_PROBE, due.action)
        assertTrue(due.state.isGpsProbeActive)
        assertEquals(150_000L, due.state.gpsProbeDeadlineElapsedMs)
    }

    @Test
    fun onlyNewTrustedPhysicalGpsFixDuringProbeCanReturnToGps() {
        val probing = AutoPositionModeState(
            mode = AutoPositionMode.GPS_RADIO, nextGpsProbeElapsedMs = 240_000L,
            gpsProbeStartedElapsedMs = 120_000L, gpsProbeDeadlineElapsedMs = 150_000L
        )
        val stale = AutoPositionModePolicy.update(probing, 121_000L, true, true, 0L, false)
        assertEquals(AutoPositionMode.GPS_RADIO, stale.state.mode)
        assertEquals(AutoPositionAction.NONE, stale.action)
        val fresh = AutoPositionModePolicy.update(stale.state, 122_000L, true, true, 0L, true)
        assertEquals(AutoPositionMode.GPS_OK, fresh.state.mode)
        assertEquals(AutoPositionAction.RETURN_TO_GPS, fresh.action)
    }

    @Test
    fun fixAfterProbeDeadlineCannotReturnToGps() {
        val probing = AutoPositionModeState(
            mode = AutoPositionMode.GPS_RADIO, nextGpsProbeElapsedMs = 240_000L,
            gpsProbeStartedElapsedMs = 120_000L, gpsProbeDeadlineElapsedMs = 150_000L
        )
        val due = AutoPositionModePolicy.update(probing, 150_000L, true, true, 0L, true)
        assertEquals(AutoPositionAction.STOP_GPS_PROBE, due.action)
        assertEquals(AutoPositionMode.GPS_RADIO, due.state.mode)
    }

    @Test
    fun failedProbeEndsAfterThirtySecondsAndRetainsNextCadence() {
        val probing = AutoPositionModeState(
            mode = AutoPositionMode.GPS_RADIO, nextGpsProbeElapsedMs = 240_000L,
            gpsProbeStartedElapsedMs = 120_000L, gpsProbeDeadlineElapsedMs = 150_000L
        )
        val before = AutoPositionModePolicy.update(probing, 149_999L, false, true, 0L)
        assertEquals(AutoPositionAction.NONE, before.action)
        val stopped = AutoPositionModePolicy.update(before.state, 150_000L, false, true, 0L)
        assertEquals(AutoPositionAction.STOP_GPS_PROBE, stopped.action)
        assertFalse(stopped.state.isGpsProbeActive)
        assertEquals(240_000L, stopped.state.nextGpsProbeElapsedMs)
        val next = AutoPositionModePolicy.update(stopped.state, 240_000L, false, true, 0L)
        assertEquals(AutoPositionAction.START_GPS_PROBE, next.action)
    }
}
