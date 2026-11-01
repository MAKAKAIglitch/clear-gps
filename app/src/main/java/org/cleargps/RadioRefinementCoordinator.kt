package org.cleargps

import android.content.Context
import android.content.Context.MODE_PRIVATE
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class RadioRefinementEvent(
    val timestampMillis: Long,
    val provider: String,
    val status: String,
    val message: String
)

internal object RadioRefinementEventStore {
    private const val PREFS_NAME = "radio_refinement_events"
    private const val KEY_EVENTS = "events_json"
    private const val MAX_EVENTS = 200
    private val lock = Any()

    fun read(context: Context): List<RadioRefinementEvent> = synchronized(lock) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_EVENTS, null) ?: return@synchronized emptyList()
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val timestamp = item.optLong("time", 0L)
                    val provider = item.optString("provider", "")
                    val status = item.optString("status", "info")
                    val message = item.optString("message", "")
                    if (timestamp > 0L && provider.isNotBlank() && message.isNotBlank()) {
                        add(RadioRefinementEvent(timestamp, provider, status, message))
                    }
                }
            }.sortedByDescending { it.timestampMillis }.take(MAX_EVENTS)
        }.getOrDefault(emptyList())
    }

    fun append(
        context: Context,
        provider: String,
        status: String,
        message: String,
        nowMillis: Long = System.currentTimeMillis()
    ): RadioRefinementEvent = synchronized(lock) {
        val event = RadioRefinementEvent(
            timestampMillis = nowMillis.coerceAtLeast(1L),
            provider = provider.take(24),
            status = status.take(16),
            message = message.take(420)
        )
        val updated = (listOf(event) + read(context)).take(MAX_EVENTS)
        val json = JSONArray().apply {
            updated.forEach { item ->
                put(JSONObject()
                    .put("time", item.timestampMillis)
                    .put("provider", item.provider)
                    .put("status", item.status)
                    .put("message", item.message))
            }
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_EVENTS, json.toString()).commit()
        event
    }

    fun clear(context: Context) = synchronized(lock) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().remove(KEY_EVENTS).commit()
        Unit
    }
}

internal object RadioRefinementSettings {
    const val PREFS_NAME = "radio_refinement_settings"

    const val KEY_BEACON_INTERVAL_MINUTES = "beacondb_manual_interval_minutes"
    const val KEY_BEACON_MAX_REQUESTS = "beacondb_manual_max_requests"
    const val KEY_OPENCELLID_INTERVAL_MINUTES = "opencellid_manual_interval_minutes"
    const val KEY_OPENCELLID_MAX_REQUESTS = "opencellid_manual_max_requests"

    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 15
    const val MAX_REQUESTS_PER_RUN = 10
    const val DEFAULT_INTERVAL_MINUTES = 1
    const val DEFAULT_MAX_REQUESTS = 1

    fun intervalMillis(minutes: Int): Long =
        minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L

    fun maxRequests(requested: Int): Int =
        requested.coerceIn(1, MAX_REQUESTS_PER_RUN)
}

internal data class RadioRefinementRunSummary(
    val attempted: Int,
    val refined: Int,
    val found: Int,
    val skipped: Int,
    val message: String
)

/**
 * User-started refinement of records already present in the local database.
 *
 * BeaconDB requests contain a single coherent Wi-Fi scan (BSSID/RSSI/age/frequency only).
 * OpenCellID requests contain one existing local cell identity and the user's API key.
 * Neither flow submits measurements to contribution endpoints.
 */
internal class RadioRefinementCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(ClearGpsPreferences.PREFS, MODE_PRIVATE)
    private val database = LocalRadioDatabase(appContext)
    private val spoofZones = SpoofZoneService(appContext)

    suspend fun refineBeaconDb(
        requestedMaxRequests: Int,
        intervalMinutes: Int,
        onEvent: (RadioRefinementEvent) -> Unit
    ): RadioRefinementRunSummary {
        val maxRequests = RadioRefinementSettings.maxRequests(requestedMaxRequests)
        val intervalMillis = RadioRefinementSettings.intervalMillis(intervalMinutes)
        var attempted = 0
        var refined = 0
        var skipped = 0
        var lastReason = ""

        fun emit(status: String, message: String) {
            val event = RadioRefinementEventStore.append(appContext, "BeaconDB", status, message)
            onEvent(event)
        }

        if (!preferences.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) ||
            !preferences.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_BEACONDB_ENABLED, true)
        ) {
            val message = "BeaconDB не разрешена. Включи отдельное сетевое согласие и источник BeaconDB."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }
        if (CrashLoopGuard.isSafeModeActive(appContext)) {
            val message = "Безопасный режим блокирует сетевое уточнение BeaconDB."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }
        if (!NetworkAccessPolicy.hasValidatedWifi(appContext)) {
            val message = "Для ручного уточнения BeaconDB нужна подтверждённая Wi-Fi-сеть с доступом в Интернет."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }

        val groups = withContext(Dispatchers.IO) {
            database.loadBeaconDbRefinementSnapshots(maxGroups = 100, limitPerGroup = 32)
        }
        if (groups.isEmpty()) {
            val message = "В локальной базе нет подходящих недавних Wi-Fi-выборок: нужны минимум два BSSID с известным SSID."
            emit("info", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }

        for (candidates in groups) {
            if (attempted >= maxRequests) break
            if (!preferences.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false) ||
                CrashLoopGuard.isSafeModeActive(appContext)
            ) {
                lastReason = "Разрешение BeaconDB выключено; очередь остановлена."
                break
            }
            if (!NetworkAccessPolicy.hasValidatedWifi(appContext)) {
                lastReason = "Wi-Fi-интернет больше не подтверждён; очередь остановлена."
                break
            }

            val now = System.currentTimeMillis()
            val request = BeaconDbRequest(wifiAccessPoints = candidates.map { candidate ->
                BeaconDbWifiAccessPoint(
                    macAddress = candidate.identifier,
                    signalStrength = candidate.rssi,
                    ageMillis = (now - candidate.observedAtMillis).coerceAtLeast(0L),
                    frequencyMhz = candidate.frequency.takeIf { it > 0 },
                    ssid = candidate.ssid
                )
            }).normalized()
            if (!request.canLookup() || request.wifiAccessPoints.size < 2) {
                skipped++
                continue
            }

            val identity = BeaconDbProtocol.identityFingerprint(request)
            val previous = withContext(Dispatchers.IO) {
                database.getAutoRefinementAttempt("beacondb", identity)
            }
            if (previous != null && now < previous.nextAllowedAtMillis) {
                skipped++
                continue
            }
            if (!RadioRefinementManualIntervalGate.tryReserve(
                    appContext, "beacondb", intervalMillis, now
                )
            ) {
                lastReason = "Соблюдается заданный интервал между запросами BeaconDB."
                break
            }

            val result = BeaconDbClient.lookup(appContext, request, unsupportedCellCount = 0)
            if (result.fromCache) {
                skipped++
                emit("info", "Использован временный ответ BeaconDB из памяти; новый сетевой запрос и запись автоуточнения не выполнялись.")
                continue
            }
            attempted++

            val coordinate = result.coordinate
            val zoneBlocked = coordinate?.let {
                spoofZones.refreshPolicy()
                spoofZones.assess(it.latitude, it.longitude, it.accuracyMeters).blocksRadioData
            } == true
            val acceptable = coordinate != null &&
                coordinate.accuracyMeters in 1.0..BeaconDbAutoRefinementPolicy.MAX_ACCEPTED_ACCURACY_METERS &&
                !zoneBlocked

            val saved = if (acceptable && coordinate != null) {
                withContext(Dispatchers.IO) {
                    database.saveAutoRefinementBatch(
                        provider = "beacondb",
                        sourceName = "BeaconDB · ручное уточнение · $now",
                        fingerprint = "beacondb-auto-refined-v1:manual:${identity.take(24)}:$now",
                        trustWeight = BeaconDbAutoRefinementPolicy.SOURCE_TRUST_WEIGHT,
                        records = candidates.map { candidate ->
                            RadioAutoRefinementRecord(
                                provider = "beacondb",
                                radioType = "wifi",
                                identifier = candidate.identifier,
                                latitude = coordinate.latitude,
                                longitude = coordinate.longitude,
                                accuracyMeters = coordinate.accuracyMeters,
                                observedAtMillis = candidate.observedAtMillis
                            )
                        }
                    )
                }
            } else 0

            val outcome = when {
                saved > 0 -> "success"
                result.message.contains("429") || result.message.contains("огранич", ignoreCase = true) -> "rate_limited"
                else -> "failure"
            }
            val nextAllowed = BeaconDbAutoRefinementPolicy.nextAllowedAt(
                now, (previous?.attempts ?: 0) + 1, outcome
            )
            withContext(Dispatchers.IO) {
                database.recordAutoRefinementAttempt("beacondb", identity, now, nextAllowed, outcome)
            }

            if (saved > 0) {
                refined += saved
                val accuracyText = String.format(java.util.Locale.US, "%.0f", coordinate!!.accuracyMeters)
                emit(
                    "success",
                    "Найдена оценка места съёмки: уточнено Wi-Fi-точек: $saved; заявленная погрешность около $accuracyText м. Это координата радиовыборки, не точное место каждого роутера."
                )
            } else {
                val message = when {
                    zoneBlocked -> "Ответ BeaconDB отклонён: координата попала в зону исключения."
                    coordinate != null && !acceptable -> "Ответ BeaconDB отклонён: погрешность превышает локальный предел 5 км."
                    else -> "BeaconDB: ${result.message}"
                }
                lastReason = message
                emit(if (outcome == "rate_limited") "warning" else "info", message)
            }

            if (outcome == "rate_limited") {
                lastReason = "BeaconDB ограничила частоту запросов; оставшаяся очередь остановлена."
                break
            }
            if (attempted < maxRequests) delay(intervalMillis)
        }

        val message = buildString {
            append("BeaconDB: запросов выполнено ").append(attempted)
            append("; локальных Wi-Fi-точек уточнено ").append(refined)
            append("; пропущено ").append(skipped).append('.')
            if (lastReason.isNotBlank()) append(' ').append(lastReason)
            else if (attempted == 0) append(" Подходящих новых выборок для запроса не осталось.")
        }
        emit(if (refined > 0) "success" else "info", message)
        return RadioRefinementRunSummary(attempted, refined, refined, skipped, message)
    }

    suspend fun refineOpenCellId(
        requestedMaxRequests: Int,
        intervalMinutes: Int,
        onEvent: (RadioRefinementEvent) -> Unit
    ): RadioRefinementRunSummary {
        val maxRequests = RadioRefinementSettings.maxRequests(requestedMaxRequests)
        val intervalMillis = RadioRefinementSettings.intervalMillis(intervalMinutes)
        var attempted = 0
        var refined = 0
        var found = 0
        var skipped = 0
        var lastReason = ""

        fun emit(status: String, message: String) {
            val event = RadioRefinementEventStore.append(appContext, "OpenCellID", status, message)
            onEvent(event)
        }

        if (!preferences.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true) ||
            !OpenCellIdKeyStore.isEnabled(appContext) ||
            !OpenCellIdKeyStore.hasKey(appContext)
        ) {
            val message = "OpenCellID не разрешён: нужны включённый источник, сетевое согласие и личный API-ключ."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }
        if (CrashLoopGuard.isSafeModeActive(appContext)) {
            val message = "Безопасный режим блокирует сетевое уточнение OpenCellID."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }
        if (!NetworkAccessPolicy.hasValidatedInternet(appContext)) {
            val message = "Нет подтверждённого доступа в Интернет для OpenCellID."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }

        val apiKey = OpenCellIdKeyStore.read(appContext)
        if (apiKey.isNullOrBlank()) {
            val message = "API-ключ OpenCellID отсутствует; запросы не выполнялись."
            emit("warning", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }

        val candidates = withContext(Dispatchers.IO) {
            database.listOpenCellIdRefinementCandidates(200)
        }
        if (candidates.isEmpty()) {
            val message = "В локальной базе нет подходящих сотовых точек с собственными локальными наблюдениями."
            emit("info", message)
            return RadioRefinementRunSummary(0, 0, 0, 0, message)
        }

        for (point in candidates) {
            if (attempted >= maxRequests) break
            if (!preferences.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true) ||
                !OpenCellIdKeyStore.isEnabled(appContext) || !OpenCellIdKeyStore.hasKey(appContext) ||
                CrashLoopGuard.isSafeModeActive(appContext)
            ) {
                lastReason = "Разрешение OpenCellID выключено; очередь остановлена."
                break
            }
            if (!NetworkAccessPolicy.hasValidatedInternet(appContext)) {
                lastReason = "Интернет недоступен; очередь остановлена."
                break
            }

            val cell = OpenCellIdCellKey.fromRadioSignal(
                RadioSignal("cell", point.identifier, point.rssi)
            ) ?: continue
            val fingerprint = "${cell.radio}:${cell.mcc}:${cell.mnc}:${cell.area}:${cell.cellId}"
            val now = System.currentTimeMillis()
            val previous = withContext(Dispatchers.IO) {
                database.getAutoRefinementAttempt("opencellid", fingerprint)
            }
            if (previous != null && now < previous.nextAllowedAtMillis) {
                skipped++
                continue
            }
            val cached = withContext(Dispatchers.IO) { database.getOpenCellIdCell(cell) }
            if (!OpenCellIdLookupPolicy.shouldLookup(cached, now)) {
                skipped++
                continue
            }

            val batchBudget = OpenCellIdUsageBudget.tryStartBatch(
                appContext, now, minimumIntervalMillis = intervalMillis
            )
            if (batchBudget != OpenCellIdBudgetBlock.NONE) {
                lastReason = when (batchBudget) {
                    OpenCellIdBudgetBlock.DAILY_BUDGET ->
                        "Достигнут внутренний дневной лимит Clear GPS на запросы OpenCellID."
                    OpenCellIdBudgetBlock.SERVER_LIMIT ->
                        "OpenCellID сообщил об исчерпании своей квоты; повторы остановлены до следующей UTC-даты."
                    OpenCellIdBudgetBlock.COOLDOWN ->
                        "Не выдержан выбранный интервал между запросами OpenCellID."
                    OpenCellIdBudgetBlock.NONE -> ""
                }
                emit("warning", lastReason)
                break
            }
            if (!OpenCellIdLocalDbAutoLookupBudget.tryReserve(
                    appContext, now, minimumIntervalMillis = intervalMillis
                )
            ) {
                val used = OpenCellIdLocalDbAutoLookupBudget.requestsUsedToday(appContext, now)
                lastReason = if (used >= OpenCellIdLocalDbAutoLookupBudget.MAX_REQUESTS_PER_UTC_DAY) {
                    "Достигнут внутренний лимит уточнения локальной базы OpenCellID: 10 попыток за UTC-день."
                } else {
                    "Не выдержан интервал между уточнениями OpenCellID."
                }
                emit("warning", lastReason)
                break
            }
            if (!OpenCellIdUsageBudget.reserveRequest(appContext, now)) {
                lastReason = "Достигнут общий внутренний дневной лимит OpenCellID."
                emit("warning", lastReason)
                break
            }

            attempted++
            val response = OpenCellIdClient.lookup(appContext, apiKey, cell)
            if (response.status == OpenCellIdLookupStatus.RATE_LIMITED) {
                OpenCellIdUsageBudget.markServerLimitReached(appContext, now)
            }
            response.record?.let { record ->
                withContext(Dispatchers.IO) { database.saveOpenCellIdCell(record) }
            }

            var outcome = when (response.status) {
                OpenCellIdLookupStatus.FOUND -> "found"
                OpenCellIdLookupStatus.NOT_FOUND -> "not_found"
                OpenCellIdLookupStatus.RATE_LIMITED -> "rate_limited"
                else -> "failure"
            }
            var saved = 0
            val record = response.record
            if (response.status == OpenCellIdLookupStatus.FOUND && record != null) {
                val latitude = record.latitude
                val longitude = record.longitude
                if (latitude != null && longitude != null) {
                    spoofZones.refreshPolicy()
                    val accuracy = (record.rangeMeters ?: 5_000).toDouble().coerceIn(1.0, 100_000.0)
                    val blocked = spoofZones.assess(latitude, longitude, accuracy).blocksRadioData
                    if (blocked) {
                        outcome = "zone_blocked"
                    } else {
                        saved = withContext(Dispatchers.IO) {
                            database.saveAutoRefinementBatch(
                                provider = "opencellid",
                                sourceName = "OpenCellID · ручное уточнение · $now",
                                fingerprint = "opencellid-auto-refined-v1:manual:$fingerprint:$now",
                                trustWeight = 0.35,
                                records = listOf(
                                    RadioAutoRefinementRecord(
                                        provider = "opencellid",
                                        radioType = "cell",
                                        identifier = point.identifier,
                                        latitude = latitude,
                                        longitude = longitude,
                                        accuracyMeters = accuracy,
                                        observedAtMillis = record.checkedAtMillis
                                    )
                                )
                            )
                        }
                        if (saved == 0) outcome = "failure"
                    }
                } else outcome = "failure"
            }

            val previousAttempts = withContext(Dispatchers.IO) {
                database.getAutoRefinementAttempt("opencellid", fingerprint)?.attempts ?: 0
            }
            val nextAllowed = OpenCellIdLocalDbAutoLookupPolicy.nextAllowedAt(
                now, previousAttempts + 1, outcome
            )
            withContext(Dispatchers.IO) {
                database.recordAutoRefinementAttempt(
                    "opencellid", fingerprint, now, nextAllowed, outcome
                )
            }

            when (outcome) {
                "found" -> {
                    if (saved > 0) {
                        refined += saved
                        found++
                        val accuracy = record?.rangeMeters ?: 5_000
                        emit("success", "Найдена сотовая ячейка; локальная точка уточнена. Радиус из ответа: около $accuracy м.")
                    } else {
                        emit("info", "OpenCellID нашёл ячейку, но координата не прошла локальную проверку сохранения.")
                    }
                }
                "not_found" -> {
                    emit("info", "Эта сотовая ячейка не найдена в OpenCellID; отрицательный результат сохранён в кэше.")
                }
                "rate_limited" -> {
                    lastReason = "OpenCellID вернул HTTP 429/код лимита; очередь остановлена."
                    emit("warning", lastReason)
                }
                "zone_blocked" -> {
                    lastReason = "Ответ OpenCellID отклонён из-за зоны исключения."
                    emit("warning", lastReason)
                }
                else -> {
                    lastReason = response.message
                    emit("warning", "Запрос OpenCellID не завершён: ${response.message}")
                }
            }

            if (outcome == "rate_limited") break
            if (response.status == OpenCellIdLookupStatus.UNAUTHORIZED) {
                lastReason = "OpenCellID отклонил API-ключ; очередь остановлена до исправления ключа."
                emit("warning", lastReason)
                break
            }
            if (attempted < maxRequests) delay(intervalMillis)
        }

        val usedToday = OpenCellIdLocalDbAutoLookupBudget.requestsUsedToday(appContext)
        val message = buildString {
            append("OpenCellID: запросов выполнено ").append(attempted)
            append("; координат найдено ").append(found)
            append("; локальных сотовых точек уточнено ").append(refined)
            append("; пропущено ").append(skipped)
            append("; использовано попыток за UTC-день ").append(usedToday).append("/10.")
            if (lastReason.isNotBlank()) append(' ').append(lastReason)
        }
        emit(if (refined > 0) "success" else "info", message)
        return RadioRefinementRunSummary(attempted, refined, found, skipped, message)
    }
}

internal object RadioRefinementManualIntervalGate {
    private const val PREFS_NAME = "radio_refinement_manual_interval"
    private const val MIN_HARD_INTERVAL_MILLIS = 60_000L

    @Synchronized
    fun tryReserve(
        context: Context,
        provider: String,
        requestedIntervalMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        require(provider == "beacondb" || provider == "opencellid")
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = "${provider}_last_request_at"
        val last = preferences.getLong(key, 0L)
        val interval = requestedIntervalMillis.coerceAtLeast(MIN_HARD_INTERVAL_MILLIS)
        if (last > 0L && (nowMillis < last || nowMillis - last < interval)) return false
        return preferences.edit().putLong(key, nowMillis).commit()
    }

    @Synchronized
    internal fun clearForTests(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().commit()
    }
}
