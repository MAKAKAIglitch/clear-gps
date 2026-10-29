package org.cleargps.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentClassifierTest {
    private val classifier = EnvironmentClassifier()

    @Test fun strongMultiSatelliteTrackIsOpenSkyLikely() {
        val result = classifier.classify(EnvironmentSnapshot(12, 9, 31.0, 2))
        assertEquals(EnvironmentClass.OPEN_SKY_LIKELY, result.environment)
        assertEquals(EnvironmentReason.CONSISTENT_MULTI_SATELLITE_SIGNAL, result.reason)
    }

    @Test fun weakGnssWithDenseRadioOnlySuggestsObstructionOrMultipath() {
        val result = classifier.classify(EnvironmentSnapshot(3, 1, 18.0, 24))
        assertEquals(EnvironmentClass.OBSTRUCTED_OR_MULTIPATH_POSSIBLE, result.environment)
        assertEquals(EnvironmentReason.WEAK_GNSS_WITH_DENSE_RADIO, result.reason)
    }

    @Test fun weakGnssAloneDoesNotAssignAnEnvironmentCause() {
        val result = classifier.classify(EnvironmentSnapshot(2, 0, 16.0, null))
        assertEquals(EnvironmentClass.UNKNOWN, result.environment)
        assertEquals(EnvironmentReason.LOW_GNSS_CAUSE_AMBIGUOUS, result.reason)
    }

    @Test fun emptyAndInvalidSnapshotsRemainUnknown() {
        assertEquals(EnvironmentReason.NO_METRICS, classifier.classify(EnvironmentSnapshot()).reason)
        assertEquals(
            EnvironmentReason.INVALID_METRICS,
            classifier.classify(EnvironmentSnapshot(visibleSatellites = 999)).reason
        )
    }

    @Test fun contextIsNotASpoofingDecision() {
        val result = classifier.classify(EnvironmentSnapshot(3, 1, 19.0, 10))
        assertEquals(EnvironmentClass.OBSTRUCTED_OR_MULTIPATH_POSSIBLE, result.environment)
        assertEquals(0.55, result.confidence, 0.0001)
    }
}
