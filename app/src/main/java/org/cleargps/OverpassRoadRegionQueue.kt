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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPOutputStream

/** Serialized Overpass downloads. The cached JSON is source data, not yet a routable graph. */
internal object OverpassRoadRegionQueue {
    private const val QUEUE = "clear_maps_overpass_region_queue"
    private const val PREFS = "clear_maps_regions"
    private const val CURRENT_DAILY = "clear_maps_current_region_daily"
    private const val OTHERS_WEEKLY = "clear_maps_other_regions_weekly"
    private const val CATALOG_STARTUP = "clear_maps_region_catalog_startup"
    private const val CATALOG_WEEKLY = "clear_maps_region_catalog_weekly"

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueCurrent(context: Context, lat: Double, lon: Double, force: Boolean = false) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val oldLat = p.getString("last_queue_lat", null)?.toDoubleOrNull()
        val oldLon = p.getString("last_queue_lon", null)?.toDoubleOrNull()
        val oldAt = p.getLong("last_queue_at", 0L)
        if (!force && oldAt > 0L) {
            val elapsed = now - oldAt
            // A moving device must not turn location callbacks into a stream of Overpass queries.
            if (elapsed < 30L * 60 * 1000) return
            if (oldLat != null && oldLon != null && elapsed < 6L * 60 * 60 * 1000) {
                if (haversineMeters(oldLat, oldLon, lat, lon) < 1_000.0) return
            }
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

    fun enqueueRegionCatalog(context: Context, countryRelationId: Long) {
        if (countryRelationId <= 0L) return
        val request = OneTimeWorkRequest.Builder(OverpassRegionCatalogWorker::class.java)
            .setInputData(Data.Builder().putLong("country_id", countryRelationId).build())
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "clear_maps_region_catalog_$countryRelationId",
            ExistingWorkPolicy.KEEP,
            request
        )
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
        val startupCatalog = OneTimeWorkRequest.Builder(OverpassRegionCatalogWorker::class.java)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(CATALOG_STARTUP, ExistingWorkPolicy.KEEP, startupCatalog)
        val daily = PeriodicWorkRequest.Builder(OverpassRoadRefreshWorker::class.java, 24, TimeUnit.HOURS)
            .setInputData(Data.Builder().putString("mode", "current").build())
            .setConstraints(constraints()).build()
        val weekly = PeriodicWorkRequest.Builder(OverpassRoadRefreshWorker::class.java, 7, TimeUnit.DAYS)
            .setInputData(Data.Builder().putString("mode", "others").build())
            .setConstraints(constraints()).build()
        val catalogWeekly = PeriodicWorkRequest.Builder(OverpassRegionCatalogWorker::class.java, 7, TimeUnit.DAYS)
            .setConstraints(constraints()).build()
        wm.enqueueUniquePeriodicWork(CURRENT_DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
        wm.enqueueUniquePeriodicWork(OTHERS_WEEKLY, ExistingPeriodicWorkPolicy.KEEP, weekly)
        wm.enqueueUniquePeriodicWork(CATALOG_WEEKLY, ExistingPeriodicWorkPolicy.KEEP, catalogWeekly)
    }

    internal fun workInfos(context: Context): List<WorkInfo> =
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWork(QUEUE).get()
        }.getOrDefault(emptyList())

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

    fun deleteRegion(context: Context, id: Long) {
        file(context, id).delete()
        val p = prefs(context)
        val edit = p.edit()
            .putString("ids", (ids(context) - id).sorted().joinToString(","))
            .remove("name_$id")
            .remove("updated_$id")
        if (currentId(context) == id) {
            edit.remove("current_id").remove("current_name")
        }
        edit.apply()
    }
}

internal class OverpassRoadRefreshWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (!validatedInternet(applicationContext)) return Result.retry()
        if (inputData.getString("mode") == "current") {
            val prefs = applicationContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            val position = readNavigationPosition(prefs)
            if (position != null) {
                OverpassRoadRegionQueue.enqueueCurrent(
                    applicationContext,
                    position.coordinate.latitude,
                    position.coordinate.longitude
                )
            } else {
                val lastRegion = OverpassRoadRegionQueue.currentId(applicationContext)
                if (lastRegion <= 0L) return Result.retry()
                OverpassRoadRegionQueue.enqueueRegion(
                    applicationContext, lastRegion, OverpassRoadRegionQueue.name(applicationContext, lastRegion)
                )
            }
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

internal class OverpassRegionCatalogWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        if (!validatedInternet(applicationContext)) return Result.retry()
        return try {
            val requestedCountryId = inputData.getLong("country_id", -1L)
            if (requestedCountryId > 0L) {
                setProgressAsync(Data.Builder().putString("phase", "Загружаем список регионов").putInt("progress", 25).build())
                OverpassRegionSearch.refreshRegions(applicationContext, requestedCountryId)
                return Result.success()
            }
            setProgressAsync(Data.Builder().putString("phase", "Обновляем каталог стран").putInt("progress", 5).build())
            OverpassRegionSearch.refreshCountries(applicationContext)
            val prefs = applicationContext.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            val position = readNavigationPosition(prefs)
            if (position != null) {
                setProgressAsync(Data.Builder().putString("phase", "Загружаем список регионов вашей страны").putInt("progress", 35).build())
                val country = OverpassRegionSearch.countryAt(position.coordinate.latitude, position.coordinate.longitude)
                if (country != null) {
                    OverpassRegionSearch.refreshRegions(applicationContext, country.relationId)
                }
                setProgressAsync(Data.Builder().putString("phase", "Ставим дорожные данные текущего региона в очередь").putInt("progress", 75).build())
                OverpassRoadRegionQueue.enqueueCurrent(applicationContext, position.coordinate.latitude, position.coordinate.longitude)
            }
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private val OVERPASS_ROAD_MIRRORS = listOf("overpass-api.de", "overpass.kumi.systems", "overpass.private.coffee")

internal class OverpassRoadRegionWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(4, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
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

    private fun request(query: String, host: String): Request {
        val url = HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments("api/interpreter").addQueryParameter("data", query).build()
        return Request.Builder().url(url)
            .header("User-Agent", "ClearMaps-Android/0.5 (read-only OpenStreetMap regional road data)")
            .header("Accept", "application/json").get().build()
    }

    private fun <T> withOverpassFallback(query: String, block: (okhttp3.Response) -> T): T {
        var lastFailure: IOException? = null
        for (host in OVERPASS_ROAD_MIRRORS) {
            try {
                http.newCall(request(query, host)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Overpass HTTP ${response.code}")
                    return block(response)
                }
            } catch (failure: IOException) { lastFailure = failure }
        }
        throw lastFailure ?: IOException("All Overpass servers are unavailable")
    }

    private fun findRegion(lat: Double, lon: Double): Pair<Long, String>? {
        val query = "[out:json][timeout:35];is_in($lat,$lon)->.a;relation(pivot.a)[\"boundary\"=\"administrative\"][\"admin_level\"=\"4\"];out tags;"
        var found: Pair<Long, String>? = null
        withOverpassFallback(query) { response ->
            val body = response.body ?: throw IOException("Empty Overpass response")
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext() && found == null) {
                    if (reader.nextName() != "elements") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext() && found == null) {
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
                        if (id > 0 && level == "4" && name.isNotBlank()) found = id to name
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
        }
        return found
    }

    private fun download(id: Long, name: String, force: Boolean, isCurrent: Boolean): Result {
        if (id <= 0) return Result.failure()
        setProgressAsync(Data.Builder().putInt("progress", 3).putString("phase", "Подготовка загрузки").putString("region_name", name).build())
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
        withOverpassFallback(query) { response ->
            val body = response.body ?: throw IOException("Empty Overpass response")
            if (body.contentLength() > MAX_BYTES) throw IOException("Region exceeds download limit")
            setProgressAsync(Data.Builder().putInt("progress", 12).putString("phase", "Загружаем дорожные данные").putString("region_name", name).build())
            body.byteStream().use { input ->
                FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReported = 0L
                    val expected = body.contentLength()
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > MAX_BYTES) throw IOException("Region exceeds download limit")
                        output.write(buffer, 0, n)
                        if (total - lastReported >= 1024L * 1024L) {
                            lastReported = total
                            val progress = if (expected > 0L) (12 + (total * 70L / expected).toInt()).coerceIn(12, 82) else 35
                            setProgressAsync(Data.Builder().putInt("progress", progress)
                                .putString("phase", "Загружаем дорожные данные")
                                .putString("region_name", name).build())
                        }
                    }
                }
            }
            setProgressAsync(Data.Builder().putInt("progress", 84).putString("phase", "Проверяем данные OpenStreetMap").putString("region_name", name).build())
        }
        val wayCount = validateJsonAndCountWays(raw)
        if (wayCount <= 0) { raw.delete(); throw IOException("No highway ways in region response") }
        setProgressAsync(Data.Builder().putInt("progress", 90).putString("phase", "Сжимаем и сохраняем регион").putString("region_name", name).build())
        FileInputStream(raw).use { input ->
            FileOutputStream(staged).use { output ->
                GZIPOutputStream(BufferedOutputStream(output)).use { gzip -> input.copyTo(gzip, 64 * 1024) }
            }
        }
        try {
            Files.move(staged.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        raw.delete()
        OverpassRoadRegionQueue.saved(applicationContext, id, name, isCurrent)
        setProgressAsync(Data.Builder().putInt("progress", 100).putString("phase", "Готово").putString("region_name", name).build())
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

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
    return 6_371_000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
}

private fun validatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}


internal data class OverpassRegionChoice(val relationId: Long, val name: String)

internal object OverpassRegionSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            NetworkGate.validateRequest(chain.request().url.toString(), chain.request().method,
                NetworkGate.Operation.OVERPASS_ROAD_DOWNLOAD, consentGranted = true)
            chain.proceed(chain.request())
        }.build()

    private const val CACHE_PREFS = "clear_maps_region_catalog_cache"
    private const val COUNTRIES_KEY = "countries_json"

    fun cachedCountries(context: Context): List<OverpassRegionChoice> = readCache(context, COUNTRIES_KEY)

    fun cachedRegions(context: Context, countryRelationId: Long): List<OverpassRegionChoice> =
        if (countryRelationId > 0L) readCache(context, "regions_$countryRelationId") else emptyList()

    fun hasCachedRegions(context: Context, countryRelationId: Long): Boolean =
        countryRelationId > 0L && context.applicationContext
            .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .contains("regions_${countryRelationId}_updated")

    fun countries(context: Context): List<OverpassRegionChoice> =
        cachedCountries(context).ifEmpty { refreshCountries(context) }

    fun regions(context: Context, countryRelationId: Long): List<OverpassRegionChoice> =
        cachedRegions(context, countryRelationId).ifEmpty { refreshRegions(context, countryRelationId) }

    fun refreshCountries(context: Context): List<OverpassRegionChoice> {
        val query = "[out:json][timeout:45];relation[\"boundary\"=\"administrative\"][\"admin_level\"=\"2\"][\"name\"];out tags;"
        val found = execute(query, 500).sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        if (found.isEmpty()) throw IOException("Overpass вернул пустой каталог стран")
        writeCache(context, COUNTRIES_KEY, found)
        return found
    }

    fun refreshRegions(context: Context, countryRelationId: Long): List<OverpassRegionChoice> {
        if (countryRelationId <= 0L) return emptyList()
        val query = "[out:json][timeout:55];relation(" + countryRelationId + ")->.country;map_to_area .country->.countryArea;relation(area.countryArea)[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(3|4|5)$\"][\"name\"];out tags 500;"
        val found = execute(query, 500).sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        writeCache(context, "regions_$countryRelationId", found)
        return found
    }

    fun countryAt(latitude: Double, longitude: Double): OverpassRegionChoice? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return null
        val query = "[out:json][timeout:25];is_in($latitude,$longitude)->.a;relation(pivot.a)[\"boundary\"=\"administrative\"][\"admin_level\"=\"2\"][\"name\"];out tags;"
        return execute(query, 5).firstOrNull()
    }

    private fun readCache(context: Context, key: String): List<OverpassRegionChoice> {
        val raw = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.optLong("id", -1L)
                    val name = item.optString("name").trim()
                    if (id > 0L && name.isNotBlank()) add(OverpassRegionChoice(id, name))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCache(context: Context, key: String, items: List<OverpassRegionChoice>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("id", item.relationId).put("name", item.name)) }
        context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, array.toString()).putLong("${key}_updated", System.currentTimeMillis()).apply()
    }

    fun search(rawName: String): List<OverpassRegionChoice> {
        val term = sanitize(rawName)
        if (term.length < 2) return emptyList()
        val escaped = term.replace("\\", "\\\\").replace("\"", "\\\"")
        val query = "[out:json][timeout:35];relation[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(3|4|5)$\"][\"name\"~\"" + escaped + "\",i];out tags 100;"
        return execute(query, 100)
    }

    private fun sanitize(rawName: String): String = rawName.trim().take(48)
        .replace(Regex("[^\\p{L}\\p{N} .'-]"), "").trim()

    private fun execute(query: String, limit: Int): List<OverpassRegionChoice> {
        var lastFailure: IOException? = null
        for (host in OVERPASS_ROAD_MIRRORS) {
            try {
                val url = HttpUrl.Builder().scheme("https").host(host).addPathSegments("api/interpreter").addQueryParameter("data", query).build()
                val request = Request.Builder().url(url).header("User-Agent", "ClearMaps-Android/0.5 (read-only OSM country and regional catalog)").header("Accept", "application/json").get().build()
                NetworkGate.validateRequest(url.toString(), "GET", NetworkGate.Operation.OVERPASS_ROAD_DOWNLOAD, consentGranted = true)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Overpass HTTP " + response.code)
                    val body = response.body ?: throw IOException("Пустой ответ Overpass")
                    val found = ArrayList<OverpassRegionChoice>()
                    JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() != "elements") { reader.skipValue(); continue }
                            reader.beginArray()
                            while (reader.hasNext()) {
                                var id = -1L; var name = ""; var russianName = ""; var englishName = ""; var level = ""
                                reader.beginObject()
                                while (reader.hasNext()) when (reader.nextName()) {
                                    "id" -> id = reader.nextLong()
                                    "tags" -> {
                                        reader.beginObject()
                                        while (reader.hasNext()) when (reader.nextName()) {
                                            "name:ru" -> russianName = reader.nextString()
                                            "name:en" -> englishName = reader.nextString()
                                            "name" -> name = reader.nextString()
                                            "admin_level" -> level = reader.nextString()
                                            else -> reader.skipValue()
                                        }
                                        reader.endObject()
                                    }
                                    else -> reader.skipValue()
                                }
                                reader.endObject()
                                val label = russianName.ifBlank { englishName.ifBlank { name } }
                                if (id > 0L && level.isNotBlank() && label.isNotBlank()) found.add(OverpassRegionChoice(id, label))
                            }
                            reader.endArray()
                        }
                        reader.endObject()
                    }
                    return found.distinctBy { it.relationId }.take(limit)
                }
            } catch (failure: IOException) { lastFailure = failure }
        }
        throw lastFailure ?: IOException("Все серверы Overpass временно недоступны")
    }}
