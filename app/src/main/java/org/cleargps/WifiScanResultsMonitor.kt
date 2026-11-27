package org.cleargps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal data class WifiScanBroadcast(
    val resultsUpdated: Boolean,
    val receivedAtElapsedRealtimeMicros: Long
)

/**
 * Process-scoped listener for Android's Wi-Fi scan completion broadcasts.
 *
 * Android 10+ sends this broadcast for full scans started by the system or another app too.
 * The receiver only signals that the system cache may have changed; callers still read and
 * validate WifiManager.scanResults and their per-result timestamps.
 */
internal class WifiScanResultsMonitor(context: Context) {
    private data class PendingWaiter(
        val afterElapsedRealtimeMicros: Long,
        val continuation: CancellableContinuation<WifiScanBroadcast>
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private var registered = false
    private var latestBroadcast: WifiScanBroadcast? = null
    private val pendingWaiters = LinkedHashSet<PendingWaiter>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleScanResultsBroadcast(intent)
        }
    }

    internal fun handleScanResultsBroadcast(
        intent: Intent?,
        receivedAtElapsedRealtimeMicros: Long = SystemClock.elapsedRealtimeNanos() / 1_000L
    ) {
        if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
        val event = WifiScanBroadcast(
            resultsUpdated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false),
            receivedAtElapsedRealtimeMicros = receivedAtElapsedRealtimeMicros
        )
        val waiters = synchronized(lock) {
            latestBroadcast = event
            pendingWaiters.filter {
                event.receivedAtElapsedRealtimeMicros > it.afterElapsedRealtimeMicros
            }.also { pendingWaiters.removeAll(it.toSet()) }
        }
        waiters.forEach { waiter ->
            waiter.continuation.let { continuation ->
                if (continuation.isActive) continuation.resume(event)
            }
        }
    }

    fun ensureRegistered(): Boolean = synchronized(lock) {
        if (registered) return@synchronized true
        return@synchronized try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Exported is required to receive broadcasts from privileged system components.
                // The action is system-owned; the payload is treated only as a wake-up hint.
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to register Wi-Fi scan receiver (permission)", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to register Wi-Fi scan receiver", error)
            false
        }
    }

    suspend fun awaitSuccessfulResultAfter(
        startedAtElapsedRealtimeMicros: Long,
        timeoutMillis: Long
    ): Boolean {
        require(startedAtElapsedRealtimeMicros >= 0L)
        require(timeoutMillis >= 0L)
        val deadlineElapsedMillis = SystemClock.elapsedRealtime() + timeoutMillis
        var cursorMicros = startedAtElapsedRealtimeMicros
        while (true) {
            val remainingMillis = deadlineElapsedMillis - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) return false
            val event = awaitResultAfter(cursorMicros, remainingMillis) ?: return false
            cursorMicros = event.receivedAtElapsedRealtimeMicros
            if (event.resultsUpdated) return true
        }
    }

    suspend fun awaitResultAfter(
        startedAtElapsedRealtimeMicros: Long,
        timeoutMillis: Long
    ): WifiScanBroadcast? {
        require(startedAtElapsedRealtimeMicros >= 0L)
        require(timeoutMillis >= 0L)
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val immediate = synchronized(lock) {
                    latestBroadcast?.takeIf {
                        it.receivedAtElapsedRealtimeMicros > startedAtElapsedRealtimeMicros
                    } ?: run {
                        PendingWaiter(startedAtElapsedRealtimeMicros, continuation).also(pendingWaiters::add)
                        null
                    }
                }
                if (immediate != null) continuation.resume(immediate)
                continuation.invokeOnCancellation {
                    synchronized(lock) {
                        pendingWaiters.removeAll { it.continuation === continuation }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "WifiScanMonitor"
    }
}
