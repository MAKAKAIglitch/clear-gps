package org.cleargps

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection

/** Explicit read-only multi-radio BeaconDB lookup. Never calls the data-submission endpoint. */
internal object BeaconDbClient {
    private const val ENDPOINT = "https://api.beacondb.net/v1/geolocate"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_RESPONSE_CHARS = 16_384
    private const val MAX_REQUEST_CHARS = 64 * 1_024
    private val lookupCache = BeaconDbLookupCache()

    fun clearCache() = lookupCache.clear()

    /**
     * Returns only an already-cached response. Live positioning uses this method so an enabled
     * BeaconDB source contributes to fusion without turning periodic radio scans into network calls.
     */
    fun lookupCached(request: BeaconDbRequest, unsupportedCellCount: Int): BeaconDbLookupResult? {
        val normalized = request.normalized()
        if (!normalized.canLookup()) return null
        val fingerprint = BeaconDbProtocol.cacheFingerprint(normalized)
        val cached = lookupCache.getRequest(fingerprint) ?: return null
        return normalized.toResult(
            unsupportedCellCount,
            coordinate = cached.coordinate,
            queriedAtMillis = cached.queriedAtMillis,
            message = "Использована сохранённая оценка BeaconDB; сетевой запрос не отправлялся.",
            fromCache = true,
            cacheAgeMillis = cached.ageMillis
        )
    }

    suspend fun lookup(context: Context, request: BeaconDbRequest, unsupportedCellCount: Int): BeaconDbLookupResult =
        withContext(Dispatchers.IO) {
            val normalized = request.normalized()
            if (!normalized.canLookup()) {
                return@withContext normalized.toResult(
                    unsupportedCellCount,
                    message = "BeaconDB нужны поддерживаемая сотовая ячейка или минимум два Wi-Fi/BLE-идентификатора. Запрос не отправлялся."
                )
            }
            val body = try {
                BeaconDbProtocol.buildRequestBody(normalized)
            } catch (_: IllegalArgumentException) {
                return@withContext normalized.toResult(
                    unsupportedCellCount,
                    message = "Не удалось подготовить поддерживаемые радиосигналы. Запрос не отправлялся."
                )
            }
            if (body.length > MAX_REQUEST_CHARS) {
                return@withContext normalized.toResult(
                    unsupportedCellCount,
                    message = "Слишком много радиосигналов для одного запроса BeaconDB; запрос не отправлялся."
                )
            }

            var connection: HttpsURLConnection? = null
            val fingerprint = BeaconDbProtocol.cacheFingerprint(normalized)
            val cacheGeneration = lookupCache.generation()
            lookupCache.getRequest(fingerprint)?.let { cached ->
                val ageText = if (cached.ageMillis < 1_000L) "менее 1 с"
                    else (cached.ageMillis / 1_000L).toString() + " с"
                return@withContext normalized.toResult(
                    unsupportedCellCount,
                    coordinate = cached.coordinate,
                    queriedAtMillis = cached.queriedAtMillis,
                    message = "Использован временный кэш BeaconDB (возраст: $ageText). Сетевой запрос не отправлялся; результат не сохранён в SQLite.",
                    fromCache = true,
                    cacheAgeMillis = cached.ageMillis
                )
            }
            try {
                if (!lookupCache.isGenerationCurrent(cacheGeneration)) {
                    return@withContext normalized.toResult(
                        unsupportedCellCount,
                        message = "Запрос BeaconDB отменён: разрешение было выключено. Сетевого обращения не было."
                    )
                }
                val activeConnection = NetworkGate.openBeaconDbLookupConnection(context, ENDPOINT)
                connection = activeConnection
                activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
                activeConnection.readTimeout = READ_TIMEOUT_MS
                activeConnection.requestMethod = "POST"
                activeConnection.instanceFollowRedirects = false
                activeConnection.useCaches = false
                activeConnection.doInput = true
                activeConnection.doOutput = true
                activeConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                activeConnection.setRequestProperty("Accept", "application/json")
                activeConnection.setRequestProperty(
                    "User-Agent",
                    "ClearGPS/0.4.0 (+https://github.com/MAKAKAIglitch/clear-gps)"
                )
                if (!lookupCache.isGenerationCurrent(cacheGeneration)) {
                    return@withContext normalized.toResult(
                        unsupportedCellCount,
                        message = "Запрос BeaconDB отменён: разрешение было выключено. Сетевого обращения не было."
                    )
                }

                val requestBytes = body.toByteArray(Charsets.UTF_8)
                activeConnection.setFixedLengthStreamingMode(requestBytes.size)
                activeConnection.outputStream.use { output ->
                    output.write(requestBytes)
                    output.flush()
                }
                val statusCode = activeConnection.responseCode
                val responseStream = if (statusCode in 200..299) activeConnection.inputStream
                    else activeConnection.errorStream
                val responseBody = responseStream?.use(::readBounded).orEmpty()
                val queriedAt = System.currentTimeMillis()
                if (statusCode !in 200..299) {
                    val statusMessage = when (statusCode) {
                        404 -> "BeaconDB не смогла оценить положение по этим сигналам."
                        429 -> "BeaconDB временно ограничила частоту запросов. Попробуй позже."
                        in 500..599 -> "Сервис BeaconDB временно недоступен (HTTP $statusCode)."
                        else -> "BeaconDB отклонила запрос (HTTP $statusCode)."
                    }
                    return@withContext normalized.toResult(
                        unsupportedCellCount,
                        queriedAtMillis = queriedAt,
                        message = "$statusMessage В локальную базу ничего не записано."
                    )
                }
                val coordinate = BeaconDbProtocol.parseCoordinateResponse(responseBody)
                    ?: return@withContext normalized.toResult(
                        unsupportedCellCount,
                        queriedAtMillis = queriedAt,
                        message = "BeaconDB вернула ответ без пригодных координат. Результат не сохранён."
                    )
                lookupCache.putRequest(fingerprint, coordinate, queriedAt, expectedGeneration = cacheGeneration)
                normalized.toResult(
                    unsupportedCellCount,
                    coordinate = coordinate,
                    queriedAtMillis = queriedAt,
                    message = "Получена оценка BeaconDB по текущим Wi-Fi/BLE-сигналам и поддерживаемым сотовым ячейкам. Результат остался в памяти экрана; локальная база не изменена."
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                normalized.toResult(
                    unsupportedCellCount,
                    queriedAtMillis = System.currentTimeMillis(),
                    message = "Не удалось выполнить запрос BeaconDB: " + error.javaClass.simpleName + ". Проверь интернет и попробуй ещё раз."
                )
            } finally {
                connection?.disconnect()
            }
        }

    private fun BeaconDbRequest.toResult(
        unsupportedCellCount: Int,
        coordinate: BeaconDbCoordinate? = null,
        queriedAtMillis: Long = 0L,
        message: String,
        fromCache: Boolean = false,
        cacheAgeMillis: Long = 0L
    ) = BeaconDbLookupResult(
        cells = cells,
        unsupportedCellCount = unsupportedCellCount,
        coordinate = coordinate,
        queriedAtMillis = queriedAtMillis,
        message = message,
        fromCache = fromCache,
        cacheAgeMillis = cacheAgeMillis,
        wifiAccessPoints = wifiAccessPoints,
        bluetoothBeacons = bluetoothBeacons
    )

    private fun readBounded(stream: InputStream): String {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(1_024)
            while (output.length < MAX_RESPONSE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - output.length))
                if (count <= 0) break
                output.append(buffer, 0, count)
            }
            return output.toString()
        }
    }
}
