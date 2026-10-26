package org.cleargps

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

data class Obd2Adapter(val name: String, val address: String)

class Obd2Repository(context: Context) {
    private val appContext = context.applicationContext
    private val ioMutex = Mutex()

    @Volatile
    private var activeSession: Session? = null

    private data class Session(
        val device: BluetoothDevice,
        val socket: BluetoothSocket,
        val input: InputStream,
        val output: OutputStream,
        var supportedPids: Set<Int>? = null,
        var supportProbeCompleted: Boolean = false
    )

    /**
     * Uses already-paired Bluetooth Classic devices only. No discovery scan is started,
     * so this feature never scans the environment for arbitrary nearby devices.
     */
    suspend fun pairedAdapters(): List<Obd2Adapter> = withContext(Dispatchers.IO) {
        requireBluetoothPermission()
        val bluetoothAdapter = getBluetoothAdapter()
        if (!bluetoothAdapter.isEnabled) {
            throw IllegalStateException("Bluetooth выключен. Включи его в системных настройках.")
        }
        bluetoothAdapter.bondedDevices
            .map { device ->
                Obd2Adapter(
                    name = safeDeviceName(device).ifBlank { "Bluetooth OBD-II адаптер" },
                    address = device.address.orEmpty().uppercase(Locale.ROOT)
                )
            }
            .filter { it.address.isNotBlank() }
            .sortedWith(compareBy<Obd2Adapter> { it.name.lowercase(Locale.ROOT) }.thenBy { it.address })
    }

    suspend fun connect(address: String): Obd2Adapter {
        require(address.matches(Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$"))) {
            "Некорректный Bluetooth-адрес адаптера."
        }
        disconnect()
        return withContext(Dispatchers.IO) {
            requireBluetoothPermission()
            val bluetoothAdapter = getBluetoothAdapter()
            if (!bluetoothAdapter.isEnabled) {
                throw IllegalStateException("Bluetooth выключен. Включи его в системных настройках.")
            }
            val device = bluetoothAdapter.getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                connectSocketCancellable(socket)
                val newSession = Session(device, socket, socket.inputStream, socket.outputStream)
                activeSession = newSession

                // Adapter-local ELM settings: plain text, adaptive timing and automatic
                // protocol detection. These do not write to the vehicle ECU.
                ADAPTER_OUTPUT_SETUP.forEach { command ->
                    exchange(newSession, command, ADAPTER_COMMAND_TIMEOUT_MS)
                }
                Obd2Adapter(safeDeviceName(device).ifBlank { "Bluetooth OBD-II адаптер" }, device.address)
            } catch (cancelled: CancellationException) {
                if (activeSession?.socket === socket) activeSession = null
                try { socket.close() } catch (_: Exception) { }
                throw cancelled
            } catch (error: Exception) {
                if (activeSession?.socket === socket) activeSession = null
                try { socket.close() } catch (_: Exception) { }
                if (error is SecurityException || error is IllegalStateException) throw error
                throw IOException("Не удалось подключиться к адаптеру. Проверь сопряжение и совместимость Bluetooth Classic SPP.", error)
            }
        }
    }

    suspend fun readTelemetry(): Obd2Telemetry = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val current = activeSession ?: throw IllegalStateException("OBD-II адаптер не подключён.")
            try {
                // Walk the SAE J1979 support-bitmap chain only when each page advertises
                // its continuation. These requests discover capabilities; they do not read
                // RPM, load, temperature, VIN or other vehicle telemetry.
                if (!current.supportProbeCompleted) {
                    current.supportedPids = probeSupportedPids(current)
                    current.supportProbeCompleted = true
                }
                val speedPidSupported = current.supportedPids?.contains(0x0D)
                val speedResponse = if (speedPidSupported != false) {
                    queryPid(current, 0x0D)
                } else {
                    // The ECU explicitly reports that speed is unsupported. Skip the PID and
                    // let the GPS/radio source continue without vehicle-speed assistance.
                    "NO DATA>"
                }
                Obd2Protocol.telemetry(
                    speedResponse = speedResponse,
                    speedPidSupported = speedPidSupported
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeSession?.socket === current.socket) activeSession = null
                try { current.socket.close() } catch (_: Exception) { }
                throw IOException("Чтение OBD-II прервано; переподключи адаптер.", error)
            }
        }
    }

    fun disconnect() {
        val current = activeSession
        activeSession = null
        if (current != null) {
            try { current.socket.close() } catch (_: Exception) { }
        }
    }

    fun close() = disconnect()

    private suspend fun probeSupportedPids(session: Session): Set<Int>? {
        val combined = linkedSetOf<Int>()
        for ((index, basePid) in Obd2Protocol.supportBitmapBases.withIndex()) {
            val response = queryPid(session, basePid)
            val page = Obd2Protocol.supportedPids(response, basePid)
                ?: return if (index == 0) null else combined

            // The last bit is the page-continuation flag, not a telemetry PID to query directly.
            val continuationPid = basePid + 0x20
            combined += page.filterNot { it == continuationPid }
            if (continuationPid !in page) break
        }
        return combined
    }

    private suspend fun queryPid(session: Session, pid: Int): String {
        val request = Obd2Protocol.requestFor(pid)
        return exchange(session, request, PID_RESPONSE_TIMEOUT_MS)
    }

    private suspend fun exchange(session: Session, command: String, timeoutMs: Long): String {
        // Only the fixed adapter-output setup list and allowlisted Mode 01 PID requests can
        // reach this method. The UI never accepts arbitrary diagnostic commands.
        session.output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        session.output.flush()
        return readUntilPrompt(session.input, timeoutMs)
    }

    private suspend fun readUntilPrompt(input: InputStream, timeoutMs: Long): String {
        val response = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buffer = ByteArray(256)
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val available = input.available()
            if (available > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) throw EOFException("Bluetooth stream closed.")
                response.append(String(buffer, 0, count, Charsets.US_ASCII))
                val promptIndex = response.lastIndexOf(">")
                if (promptIndex >= 0) return response.toString().substring(0, promptIndex)
                if (response.length > MAX_RESPONSE_CHARS) {
                    throw IOException("Слишком длинный ответ адаптера.")
                }
            } else {
                delay(10L)
            }
        }
        throw SocketTimeoutException("Адаптер не вернул ответ вовремя.")
    }

    private suspend fun connectSocketCancellable(socket: BluetoothSocket) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val connector = Thread({
                try {
                    socket.connect()
                    if (continuation.isActive) {
                        try {
                            continuation.resume(Unit)
                        } catch (_: IllegalStateException) {
                            try { socket.close() } catch (_: Exception) { }
                        }
                    } else {
                        try { socket.close() } catch (_: Exception) { }
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        try {
                            continuation.resumeWithException(error)
                        } catch (_: IllegalStateException) {
                            try { socket.close() } catch (_: Exception) { }
                        }
                    } else {
                        try { socket.close() } catch (_: Exception) { }
                    }
                }
            }, "ClearGPS-OBD2-connect").apply { isDaemon = true }
            continuation.invokeOnCancellation {
                try { socket.close() } catch (_: Exception) { }
                connector.interrupt()
            }
            connector.start()
        }

    private fun getBluetoothAdapter(): BluetoothAdapter {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IllegalStateException("Bluetooth не поддерживается этим устройством.")
        return manager.adapter ?: throw IllegalStateException("На устройстве нет Bluetooth-адаптера.")
    }

    private fun requireBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Нужно разрешение «Устройства поблизости» для подключения к сопряжённому адаптеру.")
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        try { device.name.orEmpty() } catch (_: SecurityException) { "" }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val ADAPTER_COMMAND_TIMEOUT_MS = 1_500L
        private const val PID_RESPONSE_TIMEOUT_MS = 2_200L
        private const val MAX_RESPONSE_CHARS = 2_048

        // These AT commands configure only the ELM-compatible adapter, not the ECU.
        // ATSP0 improves compatibility across standard vehicle OBD protocols.
        private val ADAPTER_OUTPUT_SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")
    }
}