package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssBaselineDeviationAnalyzerTest {
    private fun signal(
        at: Long = 10,
        visible: Int = 10,
        used: Int = 8,
        cn0: Double? = 38.0
    ) = GnssSignalSnapshot(
        elapsedRealtimeNanos = at * 1_000_000_000L,
        satellitesVisible = visible,
        satellitesUsedInFix = used,
        meanCn0DbHz = cn0,
        cn0SpreadDb = 4.0,
        constellationCount = 3,
        strongSignalCount = used
    )

    private fun rf(at: Long = 10, agc: Double? = -62.0, agcCount: Int = 8) = GnssRfSnapshot(
        elapsedRealtimeNanos = at * 1_000_000_000L,
        signalCount = 10,
        constellationCount = 3,
        meanCn0DbHz = 38.0,
        meanAgcDb = agc,
        agcSampleCount = agcCount
    )

    private fun metric(count: Int, mean: Double?, sd: Double?) =
        GnssMetricBaseline(count, mean, sd)

    private fun baseline(
        signalCount: Int = 40,
        agcCount: Int = 40,
        cn0: Double = 38.0,
        cn0Sd: Double = 2.0,
        visible: Double = 10.0,
        visibleSd: Double = 1.0,
        used: Double = 8.0,
        usedSd: Double = 1.0,
        agc: Double = -62.0,
        agcSd: Double = 1.0
    ) = GnssDeviceBaselineSnapshot(
        acceptedSignalSnapshots = signalCount,
        acceptedAgcSnapshots = agcCount,
        firstSampleElapsedRealtimeNanos = null,
        lastSampleElapsedRealtimeNanos = null,
        meanCn0DbHz = metric(signalCount, cn0, cn0Sd),
        cn0SpreadDb = metric(signalCount, 4.0, 1.0),
        visibleSatellites = metric(signalCount, visible, visibleSd),
        usedSatellites = metric(signalCount, used, usedSd),
        meanAgcDb = metric(agcCount, agc, agcSd)
    )

    @Test
    fun insufficientBaselineIsNotReadyAndProducesNoHints() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            signal(), rf(), baseline(signalCount = 29, agcCount = 29), 10_500_000_000L
        )
        assertFalse(result.ready)
        assertTrue(result.deviations.isEmpty())
    }

    @Test
    fun largeCn0AndSatelliteDropsAreReportedAsHintsOnly() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            signal(visible = 5, used = 3, cn0 = 25.0), null, baseline(), 10_500_000_000L
        )
        assertTrue(result.ready)
        assertTrue(result.deviations.any { it.feature == GnssBaselineFeature.MEAN_CN0_DROP })
        assertTrue(result.deviations.any { it.feature == GnssBaselineFeature.VISIBLE_SATELLITES_DROP })
        assertTrue(result.deviations.any { it.feature == GnssBaselineFeature.USED_SATELLITES_DROP })
        assertTrue(result.deviations.none { it.reason.contains("спуф", ignoreCase = true) })
    }

    @Test
    fun aCn0DropThatIsSmallOrWithinBaselineNoiseIsIgnored() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            signal(cn0 = 34.0), null, baseline(), 10_500_000_000L
        )
        assertTrue(result.ready)
        assertTrue(result.deviations.none { it.feature == GnssBaselineFeature.MEAN_CN0_DROP })
    }

    @Test
    fun missingAgcDoesNotCreateADeviation() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            null, rf(agc = null, agcCount = 0), baseline(), 10_500_000_000L
        )
        assertTrue(result.ready)
        assertTrue(result.deviations.none { it.feature == GnssBaselineFeature.MEAN_AGC_CHANGE })
    }

    @Test
    fun agcChangeRequiresBothAbsoluteAndStatisticalThresholds() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            null, rf(agc = -56.0), baseline(), 10_500_000_000L
        )
        assertTrue(result.deviations.any { it.feature == GnssBaselineFeature.MEAN_AGC_CHANGE })
    }

    @Test
    fun staleFutureAndInvalidSamplesAreIgnored() {
        val stale = GnssBaselineDeviationAnalyzer.assess(
            signal(at = 1), null, baseline(), 20_000_000_000L
        )
        assertTrue(stale.ready)
        assertTrue(stale.deviations.isEmpty())

        val future = GnssBaselineDeviationAnalyzer.assess(
            signal(at = 11), null, baseline(), 10_500_000_000L
        )
        assertTrue(future.deviations.isEmpty())

        val invalid = GnssBaselineDeviationAnalyzer.assess(
            signal(visible = 4, used = 5), null, baseline(), 10_500_000_000L
        )
        assertTrue(invalid.deviations.isEmpty())
    }

    @Test
    fun zeroVarianceBaselineDoesNotGenerateInfiniteZScores() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            signal(cn0 = 20.0), null, baseline(cn0 = 38.0, cn0Sd = 0.0), 10_500_000_000L
        )
        assertTrue(result.deviations.none { it.feature == GnssBaselineFeature.MEAN_CN0_DROP })
    }

    @Test
    fun invalidClockAndNoFreshInputsRemainNotReady() {
        val result = GnssBaselineDeviationAnalyzer.assess(
            signal(), rf(), baseline(), 0L
        )
        assertFalse(result.ready)
        assertTrue(result.deviations.isEmpty())
    }
}
