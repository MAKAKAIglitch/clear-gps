package org.cleargps

/** Pure recovery policy with permission gates and a short task-removal grace period. */
internal object ServiceRecoveryPolicy {
    const val TASK_REMOVED_GRACE_PERIOD_MS = 45_000L
    const val HEARTBEAT_STALE_AFTER_MS = 2 * 60_000L

    fun heartbeatIsStale(
        nowElapsedMs: Long,
        lastHeartbeatElapsedMs: Long,
        taskRemovedAtElapsedMs: Long? = null
    ): Boolean {
        if (nowElapsedMs <= 0L) return false
        if (taskRemovedAtElapsedMs != null) {
            val removedAt = taskRemovedAtElapsedMs
            if (removedAt <= 0L || nowElapsedMs < removedAt) return false
            if (nowElapsedMs - removedAt < TASK_REMOVED_GRACE_PERIOD_MS) return false
            return lastHeartbeatElapsedMs <= removedAt || lastHeartbeatElapsedMs > nowElapsedMs
        }
        if (lastHeartbeatElapsedMs <= 0L || lastHeartbeatElapsedMs > nowElapsedMs) return true
        return nowElapsedMs - lastHeartbeatElapsedMs > HEARTBEAT_STALE_AFTER_MS
    }

    fun shouldAttemptRecovery(
        monitoringEnabled: Boolean,
        hasLocationPermission: Boolean,
        hasBackgroundLocationPermission: Boolean,
        nowElapsedMs: Long,
        lastHeartbeatElapsedMs: Long,
        taskRemovedAtElapsedMs: Long? = null
    ): Boolean =
        monitoringEnabled &&
            hasLocationPermission &&
            hasBackgroundLocationPermission &&
            heartbeatIsStale(nowElapsedMs, lastHeartbeatElapsedMs, taskRemovedAtElapsedMs)
}
