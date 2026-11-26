package org.cleargps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
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
    private var pendingWaiter: PendingWaiter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val event = WifiScanBroadcast(
                resultsUpdated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false),
                receivedAtElapsedRealtimeMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
            )
            val waiter = synchronized(lock) {
                latestBroadcast = event
                pendingWaiter?.takeIf {
                    event.receivedAtElapsedRealtimeMicros > it.afterElapsedRealtimeMicros
                }?.also { pendingWaiter = null }
            }
            waiter?.continuation?.let { continuation ->
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
        } catch (_: RuntimeException) {
            false
        } catch (_: SecurityException) {
            false
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
                        pendingWaiter = PendingWaiter(startedAtElapsedRealtimeMicros, continuation)
                        null
                    }
                }
                if (immediate != null) continuation.resume(immediate)
                continuation.invokeOnCancellation {
                    synchronized(lock) {
                        if (pendingWaiter?.continuation === continuation) pendingWaiter = null
                    }
                }
            }
        }
    }
}
