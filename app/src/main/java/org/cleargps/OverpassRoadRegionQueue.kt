package org.cleargps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.JsonReader
import androidx.work.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Serialized Overpass downloads. The cached JSON is source data, not yet a routable graph. */
internal object OverpassRoadRegionQueue {
    private const val QUEUE = "clear_maps_overpass_region_queue"
    private const val PREFS = "clear_maps_regions"
    private const val CURRENT_DAILY = "clear_maps_current_region_daily"
    private const val OTHERS_WEEKLY = "clear_maps_other_regions_weekly"

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueCurrent(context: Context, lat: Double, lon: Double, force: Boolean = false) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val oldLat = p.getString("last_queue_lat", null)?.toDoubleOrNull()
        val oldLon = p.getString("last_queue_lon", null)?.toDoubleOrNull()
        val oldAt = p.getLong("last_queue_at", 0L)
        if (!force && oldLat != null && oldLon != null && now - oldAt < 6L * 60 * 60 * 1000) {
            val moved = haversineMeters(oldLat, oldLon, lat, lon)
            if (moved < 2_000.0) return
        }
        p.edit().putString("last_queue_lat", lat.toString()).putString("last_queue_lon", lon.toString())
            .putLong("last_queue_at", now).apply()
        enqueue(context, Data.Builder().putString("mode", "current").putDouble("lat", lat)
            .putDouble("lon", lon).putBoolean("force", force).build())
    }

    fun enqueueRegion(context: Context, relationId: Long, name: String, force: Boolean = false) {
        if (relationId <= 0 || name.isBlank()) return
        enqueue(context, Data.Builder().putString("mode", "region").putLong("id", relationId)
            .putString("name", name.take(120)).putBoolean("force", force).build())
    }

    private fun enqueue(context: Context, data: Data) {
        val work = OneTimeWorkRequest.Builder(OverpassRoadRegionWorker::class.java)
            .setInputData(data).setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val daily = PeriodicWorkRequest.Builder(OverpassRoadRefreshWorker::class.java, 24, TimeUnit.HOURS)
            .setInputData(Data.Builder().putString("mode", "current").build())
            .setConstraints(constraints()).build()
        val weekly = PeriodicWorkRequest.Builder(OverpassRoadRefreshWorker::class.java, 7, TimeUnit.DAYS)
            .setInputData(Data.Builder().putString("mode", "others").build())
            .setConstraints(constraints()).build()
        wm.enqueueUniquePeriodicWork(CURRENT_DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
        wm.enqueueUniquePeriodicWork(OTHERS_WEEKLY, ExistingPeriodicWorkPolicy.KEEP, weekly)
    }

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    internal fun file(context: Context, id: Long) =
        File(File(context.applicationContext.filesDir, "road-regions"), "$id.overpass.json.gz")
    internal fun ids(context: Context): Set<Long> =
        prefs(context).getString("ids", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
    internal fun updated(context: Context, id: Long) = prefs(context).getLong("updated_$id", 0L)
    internal fun name(context: Context, id: Long) = prefs(context).getString("name_$id", "Регион $id") ?: "Регион $id"
    internal fun currentId(context: Context) = prefs(context).getLong("current_id", -1L)

    internal fun saved(context: Context, id: Long, name: String, isCurrent: Boolean) {
        val p = prefs(context)
        val edit = p.edit().putString("ids", (ids(context) + id).sorted().joinToString(","))
            .putString("name_$id", name).putLong("updated_$id", System.currentTimeMillis())
        if (isCurrent) edit.putLong("current_id", id).putString("current_name", name)
        edit.apply()
    }
    internal fun markCurrent(context: Context, id: Long, name: String) {
        prefs(context).edit().putLong("current_id", id).putString("current_name", name).apply()
    }
}

internal class OverpassRoadRefreshWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (!validatedInternet(applicationContext)) return Result.retry()
        val prefs = applicationContext.getSharedPreferences("clear_gps", Context.MODE_PRIVATE)
        if (inputData.getString("mode") == "current") {
            val lat = prefs.getString(LocationMonitorService.KEY_LAT, null)?.toDoubleOrNull()
                ?: prefs.getString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LAT, null)?.toDoubleOrNull()
                ?: prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT, null)?.toDoubleOrNull()
            val lon = prefs.getString(LocationMonitorService.KEY_LON, null)?.toDoubleOrNull()
                ?: prefs.getString(LocationMonitorService.KEY_LAST_TRUSTED_GPS_LON, null)?.toDoubleOrNull()
                ?: prefs.getString(LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON, null)?.toDoubleOrNull()
            if (lat == null || lon == null) return Result.retry()
            OverpassRoadRegionQueue.enqueueCurrent(applicationContext, lat, lon)
        } else {
            val current = OverpassRoadRegionQueue.currentId(applicationContext)
            OverpassRoadRegionQueue.ids(applicationContext).filter { it != current }.forEach { id ->
                if (System.currentTimeMillis() - OverpassRoadRegionQueue.updated(applicationContext, id) >= 7L * 24 * 60 * 60 * 1000) {
                    OverpassRoadRegionQueue.enqueueRegion(applicationContext, id, OverpassRoadRegionQueue.name(applicationContext, id))
                }
            }
        }
        return Result.success()
    }
}

internal class OverpassRoadRegionWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(4, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            NetworkGate.validateRequest(chain.request().url.toString(), chain.request().method,
                NetworkGate.Operation.OVERPASS_ROAD_DOWNLOAD, consentGranted = true)
            chain.proceed(chain.request())
        }.build()

    override fun doWork(): Result {
        if (!validatedInternet(applicationContext)) return Result.retry()
        return try {
            if (inputData.getString("mode") == "current") {
                val lat = inputData.getDouble("lat", Double.NaN)
                val lon = inputData.getDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) return Result.failure()
                val region = findRegion(lat, lon) ?: return Result.retry()
                download(region.first, region.second, inputData.getBoolean("force", false), true)
            } else {
                download(inputData.getLong("id", -1), inputData.getString("name") ?: "Регион",
                    inputData.getBoolean("force", false), false)
            }
        } catch (_: IOException) { Result.retry() }
          catch (_: SecurityException) { Result.failure() }
          catch (_: Exception) { Result.retry() }
    }

    private fun request(query: String): Request {
        val url = HttpUrl.Builder().scheme("https").host("overpass-api.de")
            .addPathSegments("api/interpreter").addQueryParameter("data", query).build()
        return Request.Builder().url(url)
            .header("User-Agent", "ClearMaps-Android/0.5 (read-only OpenStreetMap regional road data)")
            .header("Accept", "application/json").get().build()
    }

    private fun findRegion(lat: Double, lon: Double): Pair<Long, String>? {
        val query = "[out:json][timeout:35];is_in($lat,$lon)->.a;relation(pivot.a)[\"boundary\"=\"administrative\"][\"admin_level\"=\"4\"];out tags;"
        http.newCall(request(query)).execute().use { response ->
            if (response.code == 429 || response.code >= 500) throw IOException("Overpass temporary failure")
            if (!response.isSuccessful) throw IOException("Overpass HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty Overpass response")
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "elements") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var id = -1L; var level = ""; var name = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> id = reader.nextLong()
                                "tags" -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) when (reader.nextName()) {
                                        "admin_level" -> level = reader.nextString()
                                        "name:ru" -> name = reader.nextString()
                                        "name" -> if (name.isBlank()) name = reader.nextString() else reader.skipValue()
                                        else -> reader.skipValue()
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (id > 0 && level == "4" && name.isNotBlank()) return id to name
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
        }
        return null
    }

    private fun download(id: Long, name: String, force: Boolean, isCurrent: Boolean): Result {
        if (id <= 0) return Result.failure()
        val target = OverpassRoadRegionQueue.file(applicationContext, id)
        val ageLimit = if (isCurrent || OverpassRoadRegionQueue.currentId(applicationContext) == id) 24L * 60 * 60 * 1000 else 7L * 24 * 60 * 60 * 1000
        if (!force && target.isFile && System.currentTimeMillis() - OverpassRoadRegionQueue.updated(applicationContext, id) < ageLimit) {
            if (isCurrent) OverpassRoadRegionQueue.markCurrent(applicationContext, id, name)
            return Result.success()
        }
        val dir = target.parentFile ?: return Result.failure()
        if (!dir.exists() && !dir.mkdirs()) return Result.retry()
        val raw = File(dir, "$id.download.tmp")
        val staged = File(dir, "$id.overpass.json.gz.tmp")
        val query = "[out:json][timeout:180];relation($id)->.r;map_to_area .r->.region;way(area.region)[\"highway\"];out geom;"
        http.newCall(request(query)).execute().use { response ->
            if (response.code == 429 || response.code >= 500) throw IOException("Overpass temporary failure")
            if (!response.isSuccessful) throw IOException("Overpass HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty Overpass response")
            if (body.contentLength() > MAX_BYTES) throw IOException("Region exceeds download limit")
            body.byteStream().use { input ->
                FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > MAX_BYTES) throw IOException("Region exceeds download limit")
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
        val wayCount = validateJsonAndCountWays(raw)
        if (wayCount <= 0) { raw.delete(); throw IOException("No highway ways in region response") }
        FileInputStream(raw).use { input ->
            FileOutputStream(staged).use { output ->
                GZIPOutputStream(BufferedOutputStream(output)).use { gzip -> input.copyTo(gzip, 64 * 1024) }
            }
        }
        if (!staged.renameTo(target)) {
            target.delete()
            if (!staged.renameTo(target)) throw IOException("Cannot activate region cache")
        }
        raw.delete()
        OverpassRoadRegionQueue.saved(applicationContext, id, name, isCurrent)
        return Result.success()
    }

    private fun validateJsonAndCountWays(file: File): Long {
        var ways = 0L
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file)), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "elements") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var type = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "type") type = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                    if (type == "way") ways++
                }
                r.endArray()
            }
            r.endObject()
        }
        return ways
    }

    companion object { private const val MAX_BYTES = 256L * 1024 * 1024 }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {\n    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)\n    val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)\n    val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)\n    return 6_371_000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))\n}\n\nprivate fun validatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}


internal data class OverpassRegionChoice(val relationId: Long, val name: String)

internal object OverpassRegionSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    fun search(rawName: String): List<OverpassRegionChoice> {
        val term = rawName.trim().take(48)
            .replace(Regex("[^\\p{L}\\p{N} .'-]"), "")
            .trim()
        if (term.length < 2) return emptyList()
        val escaped = term.replace("\\", "\\\\").replace("\"", "\\\"")
        val query = "[out:json][timeout:25];relation[\"boundary\"=\"administrative\"][\"admin_level\"=\"4\"][\"name\"~\"$escaped\",i];out tags 30;"
        val url = HttpUrl.Builder().scheme("https").host("overpass-api.de")
            .addPathSegments("api/interpreter").addQueryParameter("data", query).build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "ClearMaps-Android/0.5 (read-only OSM region search)")
            .header("Accept", "application/json").get().build()
        NetworkGate.validateRequest(url.toString(), "GET",
            NetworkGate.Operation.OVERPASS_ROAD_DOWNLOAD, consentGranted = true)
        client.newCall(request).execute().use { response ->
            if (response.code == 429 || response.code >= 500) throw IOException("Overpass временно недоступен")
            if (!response.isSuccessful) throw IOException("Overpass HTTP ${response.code}")
            val body = response.body ?: throw IOException("Пустой ответ Overpass")
            val found = ArrayList<OverpassRegionChoice>()
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "elements") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var id = -1L; var name = ""; var russianName = ""; var level = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> id = reader.nextLong()
                                "tags" -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) when (reader.nextName()) {
                                        "name:ru" -> russianName = reader.nextString()
                                        "name" -> name = reader.nextString()
                                        "admin_level" -> level = reader.nextString()
                                        else -> reader.skipValue()
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        val label = russianName.ifBlank { name }
                        if (id > 0 && level == "4" && label.isNotBlank()) {
                            found.add(OverpassRegionChoice(id, label))
                        }
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
            return found.distinctBy { it.relationId }.take(30)
        }
    }
}
