package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioSourceTrustPolicyTest {
    @Test fun trustWeightIsBoundedAndNonFiniteValuesAreRejectedConservatively() {
        assertEquals(0.0, RadioSourceTrustPolicy.normalizeWeight(-1.0), 0.0)
        assertEquals(0.25, RadioSourceTrustPolicy.normalizeWeight(0.25), 0.0)
        assertEquals(1.0, RadioSourceTrustPolicy.normalizeWeight(2.0), 0.0)
        assertEquals(0.0, RadioSourceTrustPolicy.normalizeWeight(Double.NaN), 0.0)
        assertEquals(0.0, RadioSourceTrustPolicy.normalizeWeight(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test fun lowerTrustReducesInfluenceByIncreasingEffectiveAccuracy() {
        assertEquals(100.0, RadioSourceTrustPolicy.effectiveAccuracy(100.0, 1.0), 0.0)
        assertEquals(200.0, RadioSourceTrustPolicy.effectiveAccuracy(100.0, 0.5), 0.0)
        assertEquals(10_000.0, RadioSourceTrustPolicy.effectiveAccuracy(100.0, 0.01), 0.0)
        assertEquals(1_000_000.0, RadioSourceTrustPolicy.effectiveAccuracy(100_000.0, 0.01), 0.0)
    }
}
