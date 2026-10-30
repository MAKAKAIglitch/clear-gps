package org.cleargps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object OpenCellIdClient {
    private const val ENDPOINT = "https://opencellid.org/cell/get"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 10_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    suspend fun lookup(apiKey: String, cell: OpenCellIdCellKey): OpenCellIdParsedResponse =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val query = listOf(
                    "key" to apiKey,
                    "mcc" to cell.mcc.toString(),
                    "mnc" to cell.mnc.toString(),
                    "lac" to cell.area.toString(),
                    "cellid" to cell.cellId.toString(),
                    "radio" to cell.radio,
                    "format" to "json"
                ).joinToString("&") { (name, value) ->
                    name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                }
                connection = URL("$ENDPOINT?$query").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ClearGPS/0.4.0 (https://github.com/MAKAKAIglitch/clear-gps)")
                val httpStatus = connection.responseCode
                val stream = if (httpStatus in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use(::readBoundedUtf8).orEmpty()
                OpenCellIdProtocol.parseResponse(httpStatus, body, cell, System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenCellIdParsedResponse(
                    OpenCellIdLookupStatus.TEMPORARY_ERROR, null,
                    "Не удалось связаться с OpenCellID по HTTPS. Проверь интернет; запрос не повторяется автоматически."
                )
            } finally {
                connection?.disconnect()
            }
        }

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("OpenCellID response too large")
            output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }
}
