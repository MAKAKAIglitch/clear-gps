package org.cleargps.core

import android.content.Context

object ZoneStore {
    private const val PREFS = "clear_gps"
    private const val KEY_ZONE = "risk_zone"

    fun load(context: Context): List<ZonePoint> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ZONE, null)
            ?: return emptyList()
        val points = raw.lineSequence().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) null
            else ZonePoint(lat, lon)
        }.toList()
        return points.takeIf { it.size >= 3 } ?: emptyList()
    }

    fun save(context: Context, points: List<ZonePoint>) {
        val valid = points.filter {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
        val raw = if (valid.size >= 3) valid.joinToString("\n") {
            it.latitude.toString() + "," + it.longitude.toString()
        } else null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ZONE, raw).apply()
    }
}
