package org.cleargps.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedRiskZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<ZonePoint>,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

object ZoneStore {
    private const val PREFS = "clear_gps"
    private const val KEY_ZONE_LEGACY = "risk_zone"
    private const val KEY_ZONES = "risk_zones_v2"

    fun loadAll(context: Context): List<SavedRiskZone> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ZONES, null)
        if (!json.isNullOrBlank()) {
            return try {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val pointsJson = obj.optJSONArray("points") ?: continue
                        val points = ArrayList<ZonePoint>()
                        for (j in 0 until pointsJson.length()) {
                            val point = pointsJson.optJSONObject(j) ?: continue
                            val lat = point.optDouble("lat", Double.NaN)
                            val lon = point.optDouble("lon", Double.NaN)
                            if (validPoint(lat, lon)) points.add(ZonePoint(lat, lon))
                        }
                        if (points.size >= 3) add(
                            SavedRiskZone(
                                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                                name = obj.optString("name").ifBlank { "Зона без названия" }.take(80),
                                points = points,
                                enabled = obj.optBoolean("enabled", true),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        val old = loadLegacy(context)
        return if (old.size >= 3) listOf(SavedRiskZone(name = "Зона риска", points = old)) else emptyList()
    }

    fun saveAll(context: Context, zones: List<SavedRiskZone>) {
        val valid = zones.filter { it.points.size >= 3 && it.points.all { point -> validPoint(point.latitude, point.longitude) } }
            .take(MAX_ZONES)
        val array = JSONArray()
        valid.forEach { zone ->
            val obj = JSONObject()
                .put("id", zone.id)
                .put("name", zone.name.trim().ifBlank { "Зона риска" }.take(80))
                .put("enabled", zone.enabled)
                .put("createdAt", zone.createdAt)
            val points = JSONArray()
            zone.points.forEach { point ->
                points.put(JSONObject().put("lat", point.latitude).put("lon", point.longitude))
            }
            obj.put("points", points)
            array.put(obj)
        }
        val legacy = valid.firstOrNull { it.enabled } ?: valid.firstOrNull()
        val legacyValue = legacy?.points?.joinToString("\n") { it.latitude.toString() + "," + it.longitude } 
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ZONES, array.toString())
            .putString(KEY_ZONE_LEGACY, legacyValue)
            .apply()
    }

    /** Compatibility method for the original single-zone service API. */
    fun load(context: Context): List<ZonePoint> =
        loadAll(context).firstOrNull { it.enabled }?.points ?: emptyList()

    /** Compatibility method for callers that still save a single risk zone. */
    fun save(context: Context, points: List<ZonePoint>) {
        val valid = points.filter { validPoint(it.latitude, it.longitude) }
        val current = loadAll(context)
        val replacement = if (valid.size >= 3) {
            val existing = current.firstOrNull()
            val zone = SavedRiskZone(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = existing?.name ?: "Зона риска",
                points = valid,
                enabled = true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            listOf(zone) + current.drop(1)
        } else {
            current.drop(1)
        }
        saveAll(context, replacement)
    }

    private fun loadLegacy(context: Context): List<ZonePoint> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ZONE_LEGACY, null)
            ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            if (validPoint(lat, lon)) ZonePoint(lat, lon) else null
        }.toList().takeIf { it.size >= 3 } ?: emptyList()
    }

    private fun validPoint(latitude: Double, longitude: Double) =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    private const val MAX_ZONES = 100
}
