package org.cleargps

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiScanResultsMonitorTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun successfulScanBroadcastWakesWaitingCollector() = runBlocking {
        val monitor = WifiScanResultsMonitor(context)
        // Test the monitor's event/state logic deterministically; Android receiver registration
        // itself is platform behavior and is verified by instrumentation/device checks.
        val startedAtMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
        val waiting = async {
            monitor.awaitResultAfter(startedAtMicros, timeoutMillis = 2_000L)
        }
        yield()

        monitor.handleScanResultsBroadcast(
            Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true),
            receivedAtElapsedRealtimeMicros = startedAtMicros + 1L
        )

        val event = waiting.await()
        assertNotNull(event)
        assertTrue(event!!.resultsUpdated)
        assertTrue(event.receivedAtElapsedRealtimeMicros > startedAtMicros)
    }

    @Test
    fun serviceWaitsForSuccessfulBroadcastAfterAnUnsuccessfulOne() = runBlocking {
        val monitor = WifiScanResultsMonitor(context)
        // Android receiver registration is platform behavior; these tests isolate event logic.
        val startedAtMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
        val waiting = async {
            monitor.awaitSuccessfulResultAfter(startedAtMicros, timeoutMillis = 2_000L)
        }
        yield()

        monitor.handleScanResultsBroadcast(
            Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false),
            receivedAtElapsedRealtimeMicros = startedAtMicros + 1L
        )
        assertTrue(!waiting.isCompleted)

        monitor.handleScanResultsBroadcast(
            Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true),
            receivedAtElapsedRealtimeMicros = startedAtMicros + 2L
        )

        assertTrue(waiting.await())
    }

    @Test
    fun failedScanBroadcastIsReportedWithoutBeingTreatedAsFreshResults() = runBlocking {
        val monitor = WifiScanResultsMonitor(context)
        // Android receiver registration is platform behavior; these tests isolate event logic.
        val startedAtMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
        val waiting = async {
            monitor.awaitResultAfter(startedAtMicros, timeoutMillis = 2_000L)
        }
        yield()

        monitor.handleScanResultsBroadcast(
            Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
        )

        val event = waiting.await()
        assertNotNull(event)
        assertTrue(!event!!.resultsUpdated)
    }
}
