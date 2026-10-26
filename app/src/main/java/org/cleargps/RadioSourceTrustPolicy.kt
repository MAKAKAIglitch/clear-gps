package org.cleargps

/** Conservative, bounded trust weighting for local data sources. */
internal object RadioSourceTrustPolicy {
    fun normalizeWeight(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0

    /**
     * Converts a source's stated accuracy into effective accuracy.
     * A zero-weight source contributes no fix; callers must exclude it before calling this method.
     */
    fun effectiveAccuracy(accuracyMeters: Double, weight: Double): Double {
        require(accuracyMeters.isFinite() && accuracyMeters > 0.0)
        val normalized = normalizeWeight(weight)
        require(normalized > 0.0)
        return (accuracyMeters / normalized.coerceAtLeast(0.01)).coerceAtMost(1_000_000.0)
    }
}
