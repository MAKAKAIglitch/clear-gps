package org.cleargps.core

/**
 * Conservative GNSS reception context derived only from a fresh aggregate satellite snapshot.
 *
 * This is environmental context, not attack evidence. A poor reception context cannot confirm
 * spoofing or jamming; it exists to help the caller distinguish insufficient GNSS visibility
 * from a suspicious trajectory. The classifier deliberately does not guess "tunnel" or "urban
 * canyon" from GNSS statistics alone.
 */
enum class GnssEnvironmentContext {
    LIKELY_OPEN_SKY,
    POSSIBLE_PARTIAL_OBSTRUCTION,
    POSSIBLE_SEVERE_OBSTRUCTION,
    GNSS_UNAVAILABLE,
    UNKNOWN
}

data class GnssEnvironmentAssessment(
    val context: GnssEnvironmentContext,
    val reasons: List<String> = emptyList()
)

object GnssEnvironmentClassifier {
    private const val MAX_SNAPSHOT_AGE_NANOS = 10_000_000_000L
    private const val MAX_SATELLITES = 128
    private const val MAX_CONSTELLATIONS = 8
    private const val MAX_CN0_DB_HZ = 60.0

    /**
     * Classifies only fresh, internally consistent aggregate metrics.
     * Missing C/N0 is unknown evidence, never a fabricated zero.
     */
    fun classify(
        snapshot: GnssSignalSnapshot?,
        nowElapsedRealtimeNanos: Long
    ): GnssEnvironmentAssessment {
        if (snapshot == null || nowElapsedRealtimeNanos <= 0L ||
            snapshot.elapsedRealtimeNanos <= 0L ||
            snapshot.elapsedRealtimeNanos > nowElapsedRealtimeNanos ||
            nowElapsedRealtimeNanos - snapshot.elapsedRealtimeNanos > MAX_SNAPSHOT_AGE_NANOS
        ) {
            return GnssEnvironmentAssessment(GnssEnvironmentContext.UNKNOWN)
        }

        if (snapshot.satellitesVisible !in 0..MAX_SATELLITES ||
            snapshot.satellitesUsedInFix !in 0..snapshot.satellitesVisible ||
            snapshot.constellationCount !in 0..MAX_CONSTELLATIONS
        ) {
            return GnssEnvironmentAssessment(GnssEnvironmentContext.UNKNOWN)
        }

        if (snapshot.satellitesVisible == 0) {
            return GnssEnvironmentAssessment(
                GnssEnvironmentContext.GNSS_UNAVAILABLE,
                listOf("Нет видимых спутников GNSS; причина потери сигнала неизвестна")
            )
        }

        val cn0 = snapshot.meanCn0DbHz
        if (cn0 == null || !cn0.isFinite() || cn0 !in 0.0..MAX_CN0_DB_HZ) {
            return GnssEnvironmentAssessment(GnssEnvironmentContext.UNKNOWN)
        }

        if (snapshot.satellitesVisible >= 8 &&
            snapshot.satellitesUsedInFix >= 5 &&
            snapshot.constellationCount >= 2 &&
            cn0 >= 30.0
        ) {
            return GnssEnvironmentAssessment(
                GnssEnvironmentContext.LIKELY_OPEN_SKY,
                listOf("Достаточно видимых и используемых спутников с хорошим средним C/N₀")
            )
        }

        if (snapshot.satellitesVisible <= 3 || cn0 < 18.0) {
            return GnssEnvironmentAssessment(
                GnssEnvironmentContext.POSSIBLE_SEVERE_OBSTRUCTION,
                listOf("Слабая видимость GNSS или низкий средний C/N₀")
            )
        }

        val usedRatio = snapshot.satellitesUsedInFix.toDouble() /
            snapshot.satellitesVisible.coerceAtLeast(1)
        if (snapshot.satellitesVisible <= 6 || cn0 < 26.0 || usedRatio < 0.45) {
            return GnssEnvironmentAssessment(
                GnssEnvironmentContext.POSSIBLE_PARTIAL_OBSTRUCTION,
                listOf("GNSS-приём ухудшен; возможны препятствия или сложная среда")
            )
        }

        return GnssEnvironmentAssessment(GnssEnvironmentContext.UNKNOWN)
    }
}
