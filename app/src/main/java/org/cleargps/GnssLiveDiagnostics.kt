package org.cleargps

import kotlin.math.roundToInt

internal data class GnssSatelliteReading(
    val constellationType: Int, val svid: Int, val cn0DbHz: Double?,
    val elevationDegrees: Double?, val azimuthDegrees: Double?, val usedInFix: Boolean,
    val reportedCarrierFrequencyHz: Double? = null, val basebandCn0DbHz: Double? = null,
    val measurementCarrierFrequencyHz: Double? = null, val agcDb: Double? = null
) {
    fun sanitized(): GnssSatelliteReading? {
        if (svid !in 1..4096 || constellationType !in 0..8) return null
        return copy(
            cn0DbHz = cn0DbHz.finiteRange(0.0, 63.0),
            elevationDegrees = elevationDegrees.finiteRange(-90.0, 90.0),
            azimuthDegrees = azimuthDegrees.finiteRange(0.0, 360.0),
            reportedCarrierFrequencyHz = reportedCarrierFrequencyHz.validFrequency(),
            basebandCn0DbHz = basebandCn0DbHz.finiteRange(0.0, 63.0),
            measurementCarrierFrequencyHz = measurementCarrierFrequencyHz.validFrequency(),
            agcDb = agcDb.finiteRange(-10_000.0, 10_000.0)
        )
    }
}

internal data class GnssSatelliteMeasurement(
    val constellationType: Int, val svid: Int, val carrierFrequencyHz: Double?, val agcDb: Double?
) {
    fun sanitized(): GnssSatelliteMeasurement? {
        if (svid !in 1..4096 || constellationType !in 0..8) return null
        val frequency = carrierFrequencyHz.validFrequency()
        val agc = agcDb.finiteRange(-10_000.0, 10_000.0)
        if (frequency == null && agc == null) return null
        return copy(carrierFrequencyHz = frequency, agcDb = agc)
    }
}

internal data class GnssAgcBandReading(
    val constellationType: Int, val carrierFrequencyHz: Double, val levelDb: Double
) {
    fun sanitized(): GnssAgcBandReading? {
        if (constellationType !in 0..8) return null
        val frequency = carrierFrequencyHz.validFrequency() ?: return null
        val level = levelDb.finiteRange(-10_000.0, 10_000.0) ?: return null
        return copy(carrierFrequencyHz = frequency, levelDb = level)
    }
}

internal data class GnssDiagnosticsLiveSnapshot(
    val statusElapsedRealtimeNanos: Long,
    val satellites: List<GnssSatelliteReading>,
    val measurementsElapsedRealtimeNanos: Long? = null,
    val agcBands: List<GnssAgcBandReading> = emptyList()
) {
    val usedInFixCount: Int get() = satellites.count { it.usedInFix }
    val meanCn0DbHz: Double?
        get() = satellites.mapNotNull { it.cn0DbHz }.takeIf { it.isNotEmpty() }?.average()
}

internal object GnssDiagnosticsLiveStore {
    private const val MAX_STATUS_AGE_NANOS = 10_000_000_000L
    private const val MAX_MEASUREMENT_AGE_NANOS = 10_000_000_000L
    private const val MAX_SATELLITES = 128
    private const val MAX_AGC_BANDS = 24
    private data class SatelliteKey(val constellationType: Int, val svid: Int)
    private data class AgcBandKey(val constellationType: Int, val frequencyMHz: Int)

    @Volatile private var latest: GnssDiagnosticsLiveSnapshot? = null

    @Synchronized
    fun updateSatelliteStatus(atElapsedRealtimeNanos: Long, readings: List<GnssSatelliteReading>) {
        if (atElapsedRealtimeNanos <= 0L) return
        val previous = latest
        if (previous != null && atElapsedRealtimeNanos <= previous.statusElapsedRealtimeNanos) return
        val preserveMeasurements = previous?.measurementsElapsedRealtimeNanos?.let { measuredAt ->
            atElapsedRealtimeNanos >= measuredAt &&
                atElapsedRealtimeNanos - measuredAt <= MAX_MEASUREMENT_AGE_NANOS
        } == true
        val oldByKey = if (preserveMeasurements) {
            previous?.satellites?.associateBy { SatelliteKey(it.constellationType, it.svid) }.orEmpty()
        } else emptyMap()
        val cleanReadings = readings.asSequence().mapNotNull { it.sanitized() }
            .distinctBy { SatelliteKey(it.constellationType, it.svid) }.take(MAX_SATELLITES)
            .map { item ->
                val old = oldByKey[SatelliteKey(item.constellationType, item.svid)]
                item.copy(measurementCarrierFrequencyHz = old?.measurementCarrierFrequencyHz, agcDb = old?.agcDb)
            }.toList()
        latest = GnssDiagnosticsLiveSnapshot(
            atElapsedRealtimeNanos, cleanReadings,
            if (preserveMeasurements) previous?.measurementsElapsedRealtimeNanos else null,
            if (preserveMeasurements) previous?.agcBands.orEmpty() else emptyList()
        )
    }

    @Synchronized
    fun updateMeasurements(
        atElapsedRealtimeNanos: Long,
        satelliteMeasurements: List<GnssSatelliteMeasurement>,
        agcBandReadings: List<GnssAgcBandReading>
    ) {
        if (atElapsedRealtimeNanos <= 0L) return
        val previous = latest ?: return
        if (previous.measurementsElapsedRealtimeNanos?.let { atElapsedRealtimeNanos <= it } == true) return
        if (atElapsedRealtimeNanos < previous.statusElapsedRealtimeNanos - MAX_STATUS_AGE_NANOS) return
        val byKey = satelliteMeasurements.asSequence().mapNotNull { it.sanitized() }
            .distinctBy { SatelliteKey(it.constellationType, it.svid) }
            .associateBy { SatelliteKey(it.constellationType, it.svid) }
        val enriched = previous.satellites.map { satellite ->
            val measurement = byKey[SatelliteKey(satellite.constellationType, satellite.svid)]
            satellite.copy(
                measurementCarrierFrequencyHz = measurement?.carrierFrequencyHz,
                agcDb = measurement?.agcDb
            )
        }
        val cleanBands = agcBandReadings.asSequence().mapNotNull { it.sanitized() }
            .groupBy { AgcBandKey(it.constellationType, (it.carrierFrequencyHz / 1_000_000.0).roundToInt()) }
            .entries.take(MAX_AGC_BANDS).map { (key, readings) ->
                GnssAgcBandReading(
                    key.constellationType, key.frequencyMHz * 1_000_000.0,
                    readings.map { it.levelDb }.average()
                )
            }
        latest = previous.copy(
            satellites = enriched, measurementsElapsedRealtimeNanos = atElapsedRealtimeNanos, agcBands = cleanBands
        )
    }

    fun snapshot(nowElapsedRealtimeNanos: Long): GnssDiagnosticsLiveSnapshot? {
        val value = latest ?: return null
        val statusAge = nowElapsedRealtimeNanos - value.statusElapsedRealtimeNanos
        if (nowElapsedRealtimeNanos <= 0L || statusAge < 0L || statusAge > MAX_STATUS_AGE_NANOS) return null
        val measuredAt = value.measurementsElapsedRealtimeNanos
        val measurementAge = if (measuredAt == null) Long.MAX_VALUE else nowElapsedRealtimeNanos - measuredAt
        val measurementsFresh = measuredAt != null &&
            measurementAge >= 0L && measurementAge <= MAX_MEASUREMENT_AGE_NANOS
        if (measurementsFresh) return value
        if (measuredAt == null && value.satellites.none {
                it.measurementCarrierFrequencyHz != null || it.agcDb != null
            } && value.agcBands.isEmpty()
        ) return value
        return value.copy(
            satellites = value.satellites.map { it.copy(measurementCarrierFrequencyHz = null, agcDb = null) },
            measurementsElapsedRealtimeNanos = null,
            agcBands = emptyList()
        )
    }

    @Synchronized fun clear() { latest = null }
}

private fun Double?.finiteRange(minimum: Double, maximum: Double): Double? =
    this?.takeIf { it.isFinite() && it in minimum..maximum }

private fun Double?.validFrequency(): Double? =
    this?.takeIf { it.isFinite() && it in 100_000_000.0..3_000_000_000.0 }
