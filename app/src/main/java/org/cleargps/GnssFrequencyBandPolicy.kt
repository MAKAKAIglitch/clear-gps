package org.cleargps

/** Buckets observed GNSS carrier frequencies without treating GLONASS channel spacing as a new band. */
internal enum class GnssFrequencyBand(val label: String) {
    L1_E1_B1("L1 / E1 / B1"),
    L2_E5B("L2 / E5b / B2I"),
    L5_E5A("L5 / E5a / B2a")
}

internal object GnssFrequencyBandPolicy {
    fun classify(frequencyHz: Double): GnssFrequencyBand? {
        if (!frequencyHz.isFinite()) return null
        return when (frequencyHz) {
            in 1_150_000_000.0..1_190_000_000.0 -> GnssFrequencyBand.L5_E5A
            in 1_200_000_000.0..1_250_000_000.0 -> GnssFrequencyBand.L2_E5B
            in 1_550_000_000.0..1_620_000_000.0 -> GnssFrequencyBand.L1_E1_B1
            else -> null
        }
    }

    fun observedBands(frequenciesHz: Iterable<Double>): Set<GnssFrequencyBand> =
        frequenciesHz.mapNotNull(::classify).toSet()

    fun indicatesMultiFrequency(bands: Set<GnssFrequencyBand>): Boolean = bands.size >= 2
}
