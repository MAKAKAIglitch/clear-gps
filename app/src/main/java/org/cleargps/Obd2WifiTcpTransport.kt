package org.cleargps

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * Prompt-delimited stream for a manually selected Wi-Fi OBD endpoint.
 * The repository binds this socket to the Wi-Fi subnet containing the destination before connect.
 */
internal class Obd2WifiTcpTransport(private val socket: Socket) : Obd2ElmTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override suspend fun exchange(command: String, timeoutMs: Long): String {
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val response = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buffer = ByteArray(256)
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val available = input.available()
            if (available > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) throw EOFException("Wi-Fi OBD TCP stream closed.")
                response.append(String(buffer, 0, count, Charsets.US_ASCII))
                val promptIndex = response.lastIndexOf(">")
                if (promptIndex >= 0) return response.toString().substring(0, promptIndex)
                if (response.length > MAX_RESPONSE_CHARS) {
                    throw IOException("Слишком длинный TCP-ответ OBD адаптера.")
                }
            } else {
                delay(10L)
            }
        }
        throw SocketTimeoutException("Wi-Fi OBD адаптер не вернул ответ вовремя.")
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) { }
    }

    companion object { private const val MAX_RESPONSE_CHARS = 2_048 }
}
