package org.cleargps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Performs a single, explicit, cell-only BeaconDB geolocation lookup.
 * This client never calls BeaconDB's data-submission endpoint.
 */
internal object BeaconDbClient {
    private const val ENDPOINT = "https://api.beacondb.net/v1/geolocate"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_RESPONSE_CHARS = 16_384
    private val lookupCache = BeaconDbLookupCache()

    /** Clears process-local estimates when the user withdraws BeaconDB consent. */
    fun clearCache() = lookupCache.clear()

    suspend fun lookup(
        cells: List<BeaconDbCell>,
        unsupportedCellCount: Int
    ): BeaconDbLookupResult = withContext(Dispatchers.IO) {
        if (cells.none { it.isValid() }) {
            return@withContext BeaconDbLookupResult(
                cells = emptyList(),
                unsupportedCellCount = unsupportedCellCount,
                message = "Нет поддерживаемых сотовых ячеек. Запрос в BeaconDB не отправлялся."
            )
        }

        var connection: HttpsURLConnection? = null
        val requestCells = cells.filter { it.isValid() }.distinctBy { it.identifier }
        val cacheGeneration = lookupCache.generation()
        lookupCache.get(requestCells)?.let { cached ->
            val ageText = if (cached.ageMillis < 1_000L) {
                "менее 1 с"
            } else {
                (cached.ageMillis / 1_000L).toString() + " с"
            }
            return@withContext BeaconDbLookupResult(
                cells = requestCells,
                unsupportedCellCount = unsupportedCellCount,
                coordinate = cached.coordinate,
                queriedAtMillis = cached.queriedAtMillis,
                message = "Использован временный кэш BeaconDB (возраст при повторном поиске: " +
                    ageText + "). Сетевой запрос не отправлялся; результат не сохранён в SQLite.",
                fromCache = true,
                cacheAgeMillis = cached.ageMillis
            )
        }
        try {
            if (!lookupCache.isGenerationCurrent(cacheGeneration)) {
                return@withContext BeaconDbLookupResult(
                    cells = requestCells,
                    unsupportedCellCount = unsupportedCellCount,
                    message = "Запрос BeaconDB отменён: разрешение было выключено. Сетевого обращения не было."
                )
            }
            val body = BeaconDbProtocol.buildCellOnlyRequestBody(requestCells)
            val activeConnection = URL(ENDPOINT).openConnection() as? HttpsURLConnection
                ?: return@withContext BeaconDbLookupResult(
                    cells = requestCells,
                    unsupportedCellCount = unsupportedCellCount,
                    message = "Не удалось открыть защищённое HTTPS-соединение с BeaconDB."
                )
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
                return@withContext BeaconDbLookupResult(
                    cells = requestCells,
                    unsupportedCellCount = unsupportedCellCount,
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
            val responseStream = if (statusCode in 200..299) {
                activeConnection.inputStream
            } else {
                activeConnection.errorStream
            }
            val responseBody = responseStream?.use(::readBounded).orEmpty()
            val queriedAt = System.currentTimeMillis()

            if (statusCode !in 200..299) {
                val statusMessage = when (statusCode) {
                    404 -> "BeaconDB не смогла оценить положение по этим ячейкам."
                    429 -> "BeaconDB временно ограничила частоту запросов. Попробуй позже."
                    in 500..599 -> "Сервис BeaconDB временно недоступен (HTTP " + statusCode + ")."
                    else -> "BeaconDB отклонила запрос (HTTP " + statusCode + ")."
                }
                return@withContext BeaconDbLookupResult(
                    cells = requestCells,
                    unsupportedCellCount = unsupportedCellCount,
                    queriedAtMillis = queriedAt,
                    message = statusMessage + " В локальную базу ничего не записано."
                )
            }

            val coordinate = BeaconDbProtocol.parseCoordinateResponse(responseBody)
                ?: return@withContext BeaconDbLookupResult(
                    cells = requestCells,
                    unsupportedCellCount = unsupportedCellCount,
                    queriedAtMillis = queriedAt,
                    message = "BeaconDB вернула ответ без пригодных координат. Результат не сохранён."
                )

            lookupCache.put(requestCells, coordinate, queriedAt, expectedGeneration = cacheGeneration)
            BeaconDbLookupResult(
                cells = requestCells,
                unsupportedCellCount = unsupportedCellCount,
                coordinate = coordinate,
                queriedAtMillis = queriedAt,
                message = "Получена оценка BeaconDB только по сотовым ячейкам. Результат хранится в памяти экрана, локальная база не изменена."
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BeaconDbLookupResult(
                cells = requestCells,
                unsupportedCellCount = unsupportedCellCount,
                queriedAtMillis = System.currentTimeMillis(),
                message = "Не удалось выполнить запрос BeaconDB: " + error.javaClass.simpleName +
                    ". Проверь интернет и попробуй ещё раз."
            )
        } finally {
            connection?.disconnect()
        }
    }

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
