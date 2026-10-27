package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssEnvironmentClassifierTest {
    private fun snapshot(
        atSeconds: Long = 10,
        visible: Int = 10,
        used: Int = 8,
        cn0: Double? = 34.0,
        constellations: Int = 3
    ) = GnssSignalSnapshot(
        elapsedRealtimeNanos = atSeconds * 1_000_000_000L,
        satellitesVisible = visible,
        satellitesUsedInFix = used,
        meanCn0DbHz = cn0,
        cn0SpreadDb = 5.0,
        constellationCount = constellations,
        strongSignalCount = used
    )

    @Test
    fun healthyMultiConstellationReceptionIsLikelyOpenSky() {
        val result = GnssEnvironmentClassifier.classify(snapshot(), 10_500_000_000L)
        assertEquals(GnssEnvironmentContext.LIKELY_OPEN_SKY, result.context)
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun sparseSatellitesAreContextNotProofOfAnAttack() {
        val result = GnssEnvironmentClassifier.classify(
            snapshot(visible = 3, used = 2, cn0 = 24.0, constellations = 1),
            10_500_000_000L
        )
        assertEquals(GnssEnvironmentContext.POSSIBLE_SEVERE_OBSTRUCTION, result.context)
        assertFalse(result.reasons.any { it.contains("спуф", ignoreCase = true) })
    }

    @Test
    fun moderateReceptionLossIsPartialObstruction() {
        val result = GnssEnvironmentClassifier.classify(
            snapshot(visible = 6, used = 4, cn0 = 24.0, constellations = 2),
            10_500_000_000L
        )
        assertEquals(GnssEnvironmentContext.POSSIBLE_PARTIAL_OBSTRUCTION, result.context)
    }

    @Test
    fun zeroVisibleSatellitesAreUnavailableNotZeroCn0OrConfirmedJamming() {
        val result = GnssEnvironmentClassifier.classify(
            snapshot(visible = 0, used = 0, cn0 = null, constellations = 0),
            10_500_000_000L
        )
        assertEquals(GnssEnvironmentContext.GNSS_UNAVAILABLE, result.context)
        assertFalse(result.reasons.any { it.contains("глуш", ignoreCase = true) })
    }

    @Test
    fun missingCn0WithVisibleSatellitesIsUnknownRatherThanBadReception() {
        val result = GnssEnvironmentClassifier.classify(
            snapshot(visible = 10, used = 8, cn0 = null),
            10_500_000_000L
        )
        assertEquals(GnssEnvironmentContext.UNKNOWN, result.context)
    }

    @Test
    fun staleFutureAndOutOfOrderInvalidSnapshotsAreUnknown() {
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(snapshot(atSeconds = 1), 20_000_000_000L).context
        )
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(snapshot(atSeconds = 11), 10_500_000_000L).context
        )
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(snapshot(visible = 4, used = 5), 10_500_000_000L).context
        )
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(null, 10_500_000_000L).context
        )
    }

    @Test
    fun inconsistentSatelliteCountsAreUnknown() {
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(
                snapshot(visible = 5, used = 4, cn0 = 34.0, constellations = 2)
                    .copy(strongSignalCount = 6),
                10_500_000_000L
            ).context
        )
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(
                snapshot(visible = 2, used = 2, cn0 = 34.0, constellations = 3),
                10_500_000_000L
            ).context
        )
        assertEquals(
            GnssEnvironmentContext.UNKNOWN,
            GnssEnvironmentClassifier.classify(
                snapshot(visible = 0, used = 0, cn0 = 34.0, constellations = 0),
                10_500_000_000L
            ).context
        )
    }

    @Test
    fun singleConstellationReceptionIsNotAutomaticallyClassifiedAsAnAttackOrBadReception() {
        val result = GnssEnvironmentClassifier.classify(
            snapshot(visible = 8, used = 6, cn0 = 31.0, constellations = 1),
            10_500_000_000L
        )
        assertEquals(GnssEnvironmentContext.UNKNOWN, result.context)
    }
}
