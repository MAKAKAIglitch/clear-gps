package org.cleargps

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioRefinementManualIntervalGateTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }

    @After
    fun tearDown() {
        RadioRefinementManualIntervalGate.clearForTests(context)
    }

    @Test
    fun enforcesSelectedMinimumIntervalIndependentlyForEachProvider() {
        val start = 1_800_000_000_000L
        val oneMinute = 60_000L

        assertTrue(RadioRefinementManualIntervalGate.tryReserve(context, "beacondb", oneMinute, start))
        assertFalse(RadioRefinementManualIntervalGate.tryReserve(context, "beacondb", oneMinute, start + oneMinute - 1L))
        assertTrue(RadioRefinementManualIntervalGate.tryReserve(context, "beacondb", oneMinute, start + oneMinute))

        // OpenCellID has its own interval clock; a BeaconDB request does not consume it.
        assertTrue(RadioRefinementManualIntervalGate.tryReserve(context, "opencellid", oneMinute, start))
        assertFalse(RadioRefinementManualIntervalGate.tryReserve(context, "opencellid", oneMinute, start + 30_000L))
    }

    @Test
    fun requestedIntervalCannotLowerTheHardOneMinuteFloor() {
        val start = 1_800_000_000_000L
        assertTrue(RadioRefinementManualIntervalGate.tryReserve(context, "beacondb", 1L, start))
        assertFalse(RadioRefinementManualIntervalGate.tryReserve(context, "beacondb", 1L, start + 59_999L))
        assertTrue(RadioRefinementManualIntervalGate.tryReserve(context, "beacondb", 15L * 60L * 1_000L, start + 60_000L))
        assertFalse(RadioRefinementManualIntervalGate.tryReserve(
            context, "beacondb", 15L * 60L * 1_000L, start + 15L * 60L * 1_000L - 1L
        ))
    }
}
