package org.cleargps

import android.content.Context
import android.content.SharedPreferences
import org.cleargps.core.GnssDeviceBaselineSnapshot
import org.cleargps.core.GnssMetricBaseline

/**
 * Versioned, app-private persistence for aggregate GNSS receiver statistics.
 * No coordinates, raw measurements, radio identifiers or satellite identifiers are stored.
 */
internal object GnssDeviceBaselineStore {
    private const val PREFERENCES_NAME = "clear_gps_gnss_baseline"
    private const val SCHEMA_VERSION = 1
    private const val KEY_SCHEMA = "schema_version"
    private const val KEY_SIGNAL_SNAPSHOTS = "accepted_signal_snapshots"
    private const val KEY_AGC_SNAPSHOTS = "accepted_agc_snapshots"

    fun load(context: Context): GnssDeviceBaselineSnapshot? {
        val prefs = preferences(context)
        if (prefs.all.isEmpty()) return null
        return try {
            if (prefs.getInt(KEY_SCHEMA, -1) != SCHEMA_VERSION) {
                clear(context)
                return null
            }
            val signalSamples = prefs.getInt(KEY_SIGNAL_SNAPSHOTS, -1)
            val agcSamples = prefs.getInt(KEY_AGC_SNAPSHOTS, -1)
            val snapshot = GnssDeviceBaselineSnapshot(
                acceptedSignalSnapshots = signalSamples,
                acceptedAgcSnapshots = agcSamples,
                // elapsedRealtime timestamps are session-local and intentionally not persisted.
                firstSampleElapsedRealtimeNanos = null,
                lastSampleElapsedRealtimeNanos = null,
                meanCn0DbHz = readMetric(prefs, "cn0") ?: return invalid(context),
                cn0SpreadDb = readMetric(prefs, "cn0_spread") ?: return invalid(context),
                visibleSatellites = readMetric(prefs, "visible_satellites") ?: return invalid(context),
                usedSatellites = readMetric(prefs, "used_satellites") ?: return invalid(context),
                meanAgcDb = readMetric(prefs, "agc") ?: return invalid(context)
            )
            if (signalSamples < 0 || agcSamples < 0) return invalid(context)
            snapshot
        } catch (_: RuntimeException) {
            invalid(context)
        }
    }

    /**
     * Stores only when a new accepted snapshot changes one of the stream counters.
     * The baseline is small; writes do not run on every location callback.
     */
    fun saveIfChanged(context: Context, snapshot: GnssDeviceBaselineSnapshot): Boolean {
        if (snapshot.acceptedSignalSnapshots == 0 && snapshot.acceptedAgcSnapshots == 0) return false
        val prefs = preferences(context)
        return try {
            if (prefs.getInt(KEY_SCHEMA, -1) == SCHEMA_VERSION &&
                prefs.getInt(KEY_SIGNAL_SNAPSHOTS, -1) == snapshot.acceptedSignalSnapshots &&
                prefs.getInt(KEY_AGC_SNAPSHOTS, -1) == snapshot.acceptedAgcSnapshots
            ) return false

            val editor = prefs.edit()
                .putInt(KEY_SCHEMA, SCHEMA_VERSION)
                .putInt(KEY_SIGNAL_SNAPSHOTS, snapshot.acceptedSignalSnapshots)
                .putInt(KEY_AGC_SNAPSHOTS, snapshot.acceptedAgcSnapshots)
            writeMetric(editor, "cn0", snapshot.meanCn0DbHz)
            writeMetric(editor, "cn0_spread", snapshot.cn0SpreadDb)
            writeMetric(editor, "visible_satellites", snapshot.visibleSatellites)
            writeMetric(editor, "used_satellites", snapshot.usedSatellites)
            writeMetric(editor, "agc", snapshot.meanAgcDb)
            editor.apply()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun invalid(context: Context): Nothing? {
        clear(context)
        return null
    }

    private fun readMetric(prefs: SharedPreferences, name: String): GnssMetricBaseline? {
        val count = prefs.getInt("${name}_count", -1)
        if (count < 0) return null
        val mean = prefs.getString("${name}_mean", null)?.toDoubleOrNull()
        val stddev = prefs.getString("${name}_stddev", null)?.toDoubleOrNull()
        if (count == 0) {
            if (mean != null || stddev != null) return null
        } else {
            if (mean == null || !mean.isFinite()) return null
            if (count == 1 && stddev != null) return null
            if (count >= 2 && (stddev == null || !stddev.isFinite() || stddev < 0.0)) return null
        }
        return GnssMetricBaseline(count, mean, stddev)
    }

    private fun writeMetric(
        editor: SharedPreferences.Editor,
        name: String,
        metric: GnssMetricBaseline
    ) {
        editor.putInt("${name}_count", metric.sampleCount)
        if (metric.mean != null) editor.putString("${name}_mean", metric.mean.toString())
        else editor.remove("${name}_mean")
        if (metric.standardDeviation != null) editor.putString("${name}_stddev", metric.standardDeviation.toString())
        else editor.remove("${name}_stddev")
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
