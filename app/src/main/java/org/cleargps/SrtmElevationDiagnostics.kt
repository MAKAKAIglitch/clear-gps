package org.cleargps

/** Ephemeral diagnostic result; it deliberately never stores the coordinate used for sampling. */
internal data class SrtmElevationDiagnosticSnapshot(
    val elevationMeters: Double?,
    val verticalDatum: SrtmVerticalDatum?,
    val sampledAtElapsedRealtimeNanos: Long
)

internal object SrtmElevationDiagnosticsLiveStore {
    private const val MAX_SAMPLE_AGE_NANOS = 120_000_000_000L

    @Volatile
    private var latest: SrtmElevationDiagnosticSnapshot? = null

    /** A null sample records a completed lookup with no usable tile/height, not a zero elevation. */
    fun update(sample: SrtmHgtElevationSample?, sampledAtElapsedRealtimeNanos: Long) {
        if (sampledAtElapsedRealtimeNanos <= 0L) return
        val valid = sample?.takeIf { it.elevationMeters.isFinite() }
        latest = SrtmElevationDiagnosticSnapshot(
            elevationMeters = valid?.elevationMeters,
            verticalDatum = valid?.verticalDatum,
            sampledAtElapsedRealtimeNanos = sampledAtElapsedRealtimeNanos
        )
    }

    /** Stale, future-dated or invalid-clock snapshots are not shown as current diagnostics. */
    fun snapshot(
        nowElapsedRealtimeNanos: Long,
        maxAgeNanos: Long = MAX_SAMPLE_AGE_NANOS
    ): SrtmElevationDiagnosticSnapshot? {
        if (nowElapsedRealtimeNanos <= 0L || maxAgeNanos < 0L) return null
        val sample = latest ?: return null
        val age = nowElapsedRealtimeNanos - sample.sampledAtElapsedRealtimeNanos
        return sample.takeIf { age in 0L..maxAgeNanos }
    }

    fun clear() {
        latest = null
    }
}

internal object SrtmElevationDiagnosticSamplingPolicy {
    const val MINIMUM_INTERVAL_NANOS = 15_000_000_000L

    fun shouldSample(
        lastAttemptElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long
    ): Boolean {
        if (nowElapsedRealtimeNanos <= 0L) return false
        if (lastAttemptElapsedRealtimeNanos <= 0L) return true
        if (nowElapsedRealtimeNanos <= lastAttemptElapsedRealtimeNanos) return false
        return nowElapsedRealtimeNanos - lastAttemptElapsedRealtimeNanos >= MINIMUM_INTERVAL_NANOS
    }
}
