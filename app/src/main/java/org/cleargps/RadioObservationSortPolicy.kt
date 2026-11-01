package org.cleargps

/**
 * Orders stored radio observations by received signal strength.
 * RSSI is negative dBm, so values closer to zero are stronger; unknown/out-of-range
 * values are placed last. For equal strengths the newest sample appears first.
 */
internal object RadioObservationSortPolicy {
    fun strongestFirst(observations: List<RadioPointObservation>): List<RadioPointObservation> =
        observations.sortedWith(
            compareByDescending<RadioPointObservation> {
                it.rssi.takeIf { strength -> strength in -127..-1 } ?: Int.MIN_VALUE
            }.thenByDescending { it.observedAt }
        )
}
