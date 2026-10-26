package org.cleargps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskFeedbackCodecTest {
    private fun record(
        at: Long = 1_000L,
        label: RiskFeedbackLabel = RiskFeedbackLabel.FALSE_ALARM,
        reasons: List<String> = listOf("GNSS_SIGNAL", "MOTION_JUMP")
    ) = RiskFeedbackRecord(
        timestampEpochMillis = at,
        label = label,
        filterState = "SUSPECT",
        riskScore = 45,
        sourceCode = "GNSS_SIGNAL_ONLY",
        motionScore = 0,
        crossSourceScore = null,
        gnssSignalScore = 30,
        inRiskZone = false,
        temporalHoldFloorApplied = false,
        reasonCodes = reasons
    )

    @Test fun storageRoundTripPreservesOnlyValidatedSummaryFields() {
        val records = listOf(record(), record(2_500L, RiskFeedbackLabel.SUSPECTED_SPOOFING))
        assertEquals(records, RiskFeedbackCodec.decodeStorage(RiskFeedbackCodec.encodeStorage(records)))
    }

    @Test fun malformedRowsAreIgnoredInsteadOfDestroyingOtherLabels() {
        val valid = RiskFeedbackCodec.encodeStorage(listOf(record()))
        val decoded = RiskFeedbackCodec.decodeStorage("broken|row\n$valid\n1|1|UNKNOWN|SUSPECT|999|GPS|-|-|-|0|0|")
        assertEquals(1, decoded.size)
        assertEquals(RiskFeedbackLabel.FALSE_ALARM, decoded.single().label)
    }

    @Test fun exportContainsNoCoordinatesRadioIdsOrAbsoluteTime() {
        val json = RiskFeedbackCodec.toExportJson(listOf(record()))
        assertTrue(json.contains("\"absoluteTimestampsIncluded\": false"))
        assertTrue(json.contains("\"coordinatesIncluded\": false"))
        assertTrue(json.contains("\"radioIdentifiersIncluded\": false"))
        assertFalse(json.contains("timestampEpochMillis"))
        assertFalse(json.contains("\"latitude\""))
        assertFalse(json.contains("\"longitude\""))
        assertFalse(json.contains("BSSID"))
        assertFalse(json.contains("SSID"))
    }

    @Test fun reasonClassificationNeverCopiesArbitraryTextIntoDataset() {
        val codes = RiskFeedbackCodec.classifyReasons(
            listOf("GPS скачок координат", "raw bssid=aa:bb:cc:dd:ee:ff"),
            "EXTERNAL_MOCK",
            null
        )
        assertTrue(codes.contains("MOTION_JUMP"))
        assertTrue(codes.contains("EXTERNAL_MOCK"))
        assertFalse(codes.any { it.contains("aa:bb") || it.contains("bssid", ignoreCase = true) })
        assertEquals("UNKNOWN", RiskFeedbackCodec.normalizeSource("radio location with raw identifiers"))
    }

    @Test fun ledgerKeepsOnlyTheNewestTwoHundredRows() {
        val records = (1L..240L).map { record(it) }
        val decoded = RiskFeedbackCodec.decodeStorage(RiskFeedbackCodec.encodeStorage(records))
        assertEquals(200, decoded.size)
        assertEquals(41L, decoded.first().timestampEpochMillis)
        assertEquals(240L, decoded.last().timestampEpochMillis)
    }
}
