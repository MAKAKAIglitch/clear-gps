package org.cleargps.core

/**
 * Read-only GNSS reception diagnostics for UI/logging.
 *
 * This deliberately combines environmental context and personal-baseline deviations without
 * changing spoofing risk, detector state, or mock-location publication.
 */
data class GnssReceptionDiagnostics(
    val environment: GnssEnvironmentAssessment,
    val baselineDeviation: GnssBaselineDeviationAssessment
)
