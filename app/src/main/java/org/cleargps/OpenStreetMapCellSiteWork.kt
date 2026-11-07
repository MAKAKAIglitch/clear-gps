package org.cleargps

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Bounds OSM lookups for both precise cell matching and unassigned nearby-structure discovery. */
internal object OpenStreetMapCellSiteLookupPolicy {
    const val MAX_SITE_UNCERTAINTY_METERS = 500.0
    const val MIN_SITE_CONFIDENCE = 0.30
    private const val SEARCH_RADIUS_ALLOWANCE_METERS = 150.0
    private const val RADIUS_STEP_METERS = 50

    fun shouldEnqueue(group: org.cleargps.cell.CellSiteGroup): Boolean =
        !group.lockedByUser &&
            group.members.isNotEmpty() &&
            group.members.all { it.mncLengthKnown } &&
            group.latitude.isFinite() && group.latitude in -90.0..90.0 &&
            group.longitude.isFinite() && group.longitude in -180.0..180.0 &&
            group.uncertaintyRadiusMeters.isFinite() &&
            group.uncertaintyRadiusMeters in 1.0..MAX_SITE_UNCERTAINTY_METERS &&
            group.confidenceScore.isFinite() &&
            group.confidenceScore in MIN_SITE_CONFIDENCE..1.0

    /**
     * Coarse candidates may discover nearby OSM infrastructure without assigning it to a cell.
     * The map presents these objects as unverified until a tighter local estimate can match them.
     */
    fun shouldDiscover(group: org.cleargps.cell.CellSiteGroup): Boolean =
        !group.lockedByUser &&
            group.members.isNotEmpty() &&
            group.members.all { it.mncLengthKnown } &&
            group.latitude.isFinite() && group.latitude in -90.0..90.0 &&
            group.longitude.isFinite() && group.longitude in -180.0..180.0 &&
            group.uncertaintyRadiusMeters.isFinite() &&
            group.uncertaintyRadiusMeters in 1.0..5_000.0 &&
            group.confidenceScore.isFinite() &&
            group.confidenceScore in 0.18..1.0

    fun searchRadiusMeters(uncertaintyMeters: Double): Int {
        require(uncertaintyMeters.isFinite() && uncertaintyMeters > 0.0)
        val requested = kotlin.math.ceil(
            (uncertaintyMeters + NODE_MATCH_ALLOWANCE_METERS) / RADIUS_STEP_METERS
        ).toInt() * RADIUS_STEP_METERS
        return requested.coerceIn(
            OpenStreetMapCellSiteProtocol.MINIMUM_RADIUS_METERS,
            1_000
        )
    }
}

/** Schedules bounded OSM work only while its separate user consent is enabled. */
internal object OpenStreetMapCellSiteWorkScheduler {
    private const val PERIODIC_WORK_NAME = "clear-gps-cell-osm-periodic"
    private const val IMMEDIATE_WORK_NAME = "clear-gps-cell-osm-immediate"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val enabled = appContext.getSharedPreferences(
            ClearGpsPreferences.PREFS, Context.MODE_PRIVATE
        ).getBoolean(ClearGpsPreferences.KEY_OPENSTREETMAP_CELL_SITE_LOOKUP_ENABLED, false)
        try {
            val workManager = WorkManager.getInstance(appContext)
            if (!enabled) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val periodic = PeriodicWorkRequestBuilder<OpenStreetMapCellSiteWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(constraints).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic
            )
            enqueueNow(appContext)
        } catch (error: Exception) {
            DiagnosticLogStore.log(
                appContext, "CELL_OSM",
                "Could not schedule OSM lookup work: ${error.javaClass.simpleName}", "WARN"
            )
        }
    }

    fun enqueueNow(context: Context) {
        val appContext = context.applicationContext
        val enabled = appContext.getSharedPreferences(
            ClearGpsPreferences.PREFS, Context.MODE_PRIVATE
        ).getBoolean(ClearGpsPreferences.KEY_OPENSTREETMAP_CELL_SITE_LOOKUP_ENABLED, false)
        if (!enabled) return
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<OpenStreetMapCellSiteWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, request
            )
        } catch (error: Exception) {
            DiagnosticLogStore.log(
                appContext, "CELL_OSM",
                "Could not enqueue OSM lookup: ${error.javaClass.simpleName}", "WARN"
            )
        }
    }
}

/** Coalesces local calculations after new observations; this worker needs no network. */
internal object CellSiteRecalculationSchedulePolicy {
    const val INITIAL_DELAY_MILLIS = 60_000L
    const val RETRY_DELAY_MILLIS = PowerPolicy.PAUSED_OPTIONAL_WORK_RECHECK_INTERVAL_MILLIS
}

internal object CellSiteRecalculationScheduler {
    private const val WORK_NAME = "clear-gps-cell-site-local-recalculation"
    const val KEY_LAST_COMPLETED_AT = "cell_site_recalculation_last_completed_at"

    fun enqueue(context: Context) {
        val appContext = context.applicationContext
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<CellSiteRecalculationWorker>()
                .setConstraints(constraints)
                .setInitialDelay(
                    CellSiteRecalculationSchedulePolicy.INITIAL_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    CellSiteRecalculationSchedulePolicy.RETRY_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request
            )
        } catch (error: Exception) {
            DiagnosticLogStore.log(
                appContext, "CELL_SITE",
                "Could not schedule local site recalculation: ${error.javaClass.simpleName}", "WARN"
            )
        }
    }
}

/** Refreshes the UI after a newly completed local calculation. */
internal object CellSiteRecalculationRefreshPolicy {
    fun shouldRefresh(lastSeenAtMillis: Long, completedAtMillis: Long): Boolean =
        completedAtMillis > lastSeenAtMillis
}

/** Builds local candidates from stored observations and already-cached catalogues only. */
class CellSiteRecalculationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val powerDecision = PowerPolicy.optionalBackgroundDecision(applicationContext)
        if (!powerDecision.allowed) {
            DiagnosticLogStore.log(
                applicationContext, "CELL_SITE",
                "Local site recalculation deferred by power policy: ${powerDecision.blockReason}"
            )
            return Result.retry()
        }

        return try {
            val calculationStartedAt = android.os.SystemClock.elapsedRealtime()
            val result = LocalRadioDatabase(applicationContext).use { database ->
                RadioRepository(database).calculateAndSaveCachedOpenCellIdCellSites(
                    onStageTiming = { stage, elapsedMillis ->
                        DiagnosticLogStore.log(
                            applicationContext, "CELL_SITE",
                            "Calculation stage=$stage elapsed=${elapsedMillis}ms."
                        )
                    }
                )
            }
            val elapsedMillis = android.os.SystemClock.elapsedRealtime() - calculationStartedAt
            DiagnosticLogStore.log(
                applicationContext, "CELL_SITE",
                "Local recalculation complete: ${result.estimatesByIdentity.size} cell estimates, " +
                    "${result.groups.size} physical sites, elapsed=${elapsedMillis}ms."
            )
            // Publish only after SQLite has committed and closed successfully.
            val completionPrefs = applicationContext.getSharedPreferences(
                ClearGpsPreferences.PREFS, Context.MODE_PRIVATE
            )
            val previousCompletion = completionPrefs.getLong(CellSiteRecalculationScheduler.KEY_LAST_COMPLETED_AT, 0L)
            completionPrefs.edit().putLong(
                CellSiteRecalculationScheduler.KEY_LAST_COMPLETED_AT,
                maxOf(System.currentTimeMillis(), previousCompletion + 1L)
            ).apply()
            Result.success()
        } catch (error: Exception) {
            DiagnosticLogStore.log(
                applicationContext, "CELL_SITE",
                "Local site recalculation failed: ${error.javaClass.simpleName}", "WARN"
            )
            Result.retry()
        }
    }
}

/** Executes one queued, consent-gated Overpass request per WorkManager run. */
class OpenStreetMapCellSiteWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            ClearGpsPreferences.PREFS, Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(ClearGpsPreferences.KEY_OPENSTREETMAP_CELL_SITE_LOOKUP_ENABLED, false)) {
            return Result.success()
        }

        val powerDecision = PowerPolicy.optionalBackgroundDecision(applicationContext)
        if (!powerDecision.allowed) {
            DiagnosticLogStore.log(
                applicationContext, "CELL_OSM",
                "OSM lookup deferred by power policy: ${powerDecision.blockReason}"
            )
            return Result.success()
        }

        return try {
            LocalRadioDatabase(applicationContext).use { database ->
                val task = database.claimNextOpenStreetMapCellSiteLookup()
                if (task != null) {
                    val execution = executeLookup(task)
                    val saved = database.finishOpenStreetMapCellSiteLookup(
                        taskId = task.id,
                        response = execution.response,
                        httpStatus = execution.httpStatus
                    )
                    if (saved) {
                        if (execution.response.status == OpenStreetMapCellSiteLookupStatus.FOUND &&
                            execution.response.objects.isNotEmpty()
                        ) {
                            // Cached OSM evidence must flow back through the local fusion pipeline.
                            CellSiteRecalculationScheduler.enqueue(applicationContext)
                        }
                        DiagnosticLogStore.log(
                            applicationContext, "CELL_OSM",
                            "Overpass lookup finished: ${execution.response.status}, " +
                                "HTTP=${execution.httpStatus ?: "unknown"}, " +
                                "objects=${execution.response.objects.size}."
                        )
                    } else {
                        DiagnosticLogStore.log(
                            applicationContext, "CELL_OSM",
                            "Overpass result was not applied because its queue task was no longer active.",
                            "WARN"
                        )
                    }
                }
            }
            Result.success()
        } catch (error: Exception) {
            DiagnosticLogStore.log(
                applicationContext, "CELL_OSM",
                "OSM worker failed: ${error.javaClass.simpleName}", "WARN"
            )
            Result.retry()
        }
    }

    private data class LookupExecution(
        val response: OpenStreetMapCellSiteResponse,
        val httpStatus: Int?
    )

    private class ResponseTooLargeException : IOException()

    private fun executeLookup(task: OpenStreetMapCellSiteQueueTask): LookupExecution {
        var httpStatus: Int? = null
        val response = try {
            val query = OpenStreetMapCellSiteProtocol.buildQuery(
                task.latitude, task.longitude, task.radiusMeters
            )
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val url = "https://overpass-api.de/api/interpreter?data=$encoded"
            val connection = NetworkGate.openOpenStreetMapCellSiteLookupConnection(
                applicationContext, url
            )
            try {
                val responseCode = connection.responseCode
                httpStatus = responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.use { readBounded(it) }
                } else {
                    ""
                }
                OpenStreetMapCellSiteProtocol.parseResponse(responseCode, body)
            } finally {
                connection.disconnect()
            }
        } catch (_: ResponseTooLargeException) {
            OpenStreetMapCellSiteResponse(
                OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE,
                emptyList(),
                "Ответ Overpass превышает лимит размера."
            )
        } catch (_: SecurityException) {
            OpenStreetMapCellSiteResponse(
                OpenStreetMapCellSiteLookupStatus.TEMPORARY_ERROR,
                emptyList(),
                "Сетевой запрос остановлен политикой согласия."
            )
        } catch (_: IOException) {
            OpenStreetMapCellSiteResponse(
                OpenStreetMapCellSiteLookupStatus.TEMPORARY_ERROR,
                emptyList(),
                "Временная ошибка сети при запросе Overpass."
            )
        }
        return LookupExecution(response, httpStatus)
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
            output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
