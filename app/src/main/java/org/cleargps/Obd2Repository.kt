package org.cleargps

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Obd2Adapter(
    val name: String,
    val address: String,
    val transportType: Obd2TransportType = Obd2TransportType.CLASSIC_SPP,
    val port: Int? = null
) {
    val selectionKey: String get() = Obd2AdapterSelection(address, transportType, port).key
}

class Obd2Repository(context: Context) {
    private val appContext = context.applicationContext
    private val ioMutex = Mutex()

    @Volatile
    private var activeSession: Session? = null

    private data class Session(
        val adapter: Obd2Adapter,
        val transport: Obd2ElmTransport,
        var supportedPids: Set<Int>? = null,
        var supportProbeCompleted: Boolean = false,
        var vinProbeCompleted: Boolean = false,
        var vehicleVin: String? = null,
        var vinIssue: Obd2VinIssue? = null,
        val pidCircuitBreaker: Obd2PidCircuitBreaker = Obd2PidCircuitBreaker(),
        val telemetryStabilityFilter: Obd2TelemetryStabilityFilter = Obd2TelemetryStabilityFilter()
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
                    address = device.address.orEmpty().uppercase(Locale.ROOT),
                    transportType = Obd2TransportType.CLASSIC_SPP
                )
            }
            .filter { it.address.isNotBlank() }
            .sortedWith(compareBy<Obd2Adapter> { it.name.lowercase(Locale.ROOT) }.thenBy { it.address })
    }

    /**
     * Connect only to the selected endpoint. Bare MAC and ble:MAC keys remain compatible;
     * Wi-Fi uses wifi:private-ip:port and never changes Android's active network.
     */
    suspend fun connect(selectionKey: String): Obd2Adapter {
        val selection = Obd2AdapterSelection.parse(selectionKey)
        disconnect()
        return withContext(Dispatchers.IO) {
            var adapter: Obd2Adapter? = null
            var transport: Obd2ElmTransport? = null
            try {
                when (selection.transportType) {
                    Obd2TransportType.WIFI_TCP -> {
                        val port = selection.port ?: throw IllegalArgumentException("Не задан TCP-порт Wi-Fi OBD.")
                        adapter = Obd2Adapter("Wi-Fi OBD-II TCP", selection.address, Obd2TransportType.WIFI_TCP, port)
                        val network = findWifiNetworkForHost(selection.address)
                        val socket = Socket()
                        try {
                            network.bindSocket(socket)
                            connectWifiSocketCancellable(socket, selection.address, port, WIFI_CONNECT_TIMEOUT_MS)
                            transport = Obd2WifiTcpTransport(socket)
                        } catch (error: Exception) {
                            try { socket.close() } catch (_: Exception) { }
                            throw error
                        }
                    }
                    Obd2TransportType.CLASSIC_SPP, Obd2TransportType.BLE_GATT -> {
                        requireBluetoothPermission()
                        val bluetoothAdapter = getBluetoothAdapter()
                        if (!bluetoothAdapter.isEnabled) {
                            throw IllegalStateException("Bluetooth выключен. Включи его в системных настройках.")
                        }
                        val device = bluetoothAdapter.getRemoteDevice(selection.address)
                        adapter = Obd2Adapter(
                            name = safeDeviceName(device).ifBlank { "Bluetooth OBD-II адаптер" },
                            address = device.address.orEmpty().uppercase(Locale.ROOT),
                            transportType = selection.transportType
                        )
                        transport = when (selection.transportType) {
                            Obd2TransportType.CLASSIC_SPP -> {
                                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                                try {
                                    connectSocketCancellable(socket)
                                    ClassicObd2Transport(socket)
                                } catch (error: Exception) {
                                    try { socket.close() } catch (_: Exception) { }
                                    throw error
                                }
                            }
                            Obd2TransportType.BLE_GATT -> BleGattObd2Transport(appContext, device).connect()
                            Obd2TransportType.WIFI_TCP -> error("Wi-Fi selection is handled separately.")
                        }
                    }
                }
                val selectedAdapter = adapter ?: throw IOException("OBD-адаптер не создан.")
                val selectedTransport = transport ?: throw IOException("OBD-транспорт не создан.")
                val session = Session(selectedAdapter, selectedTransport)
                activeSession = session
                ADAPTER_OUTPUT_SETUP.forEach { command ->
                    exchange(session, command, ADAPTER_COMMAND_TIMEOUT_MS)
                }
                selectedAdapter
            } catch (cancelled: CancellationException) {
                if (activeSession?.adapter?.selectionKey == adapter?.selectionKey) activeSession = null
                transport?.close()
                throw cancelled
            } catch (error: Exception) {
                if (activeSession?.adapter?.selectionKey == adapter?.selectionKey) activeSession = null
                transport?.close()
                if (error is SecurityException || error is IllegalStateException || error is IllegalArgumentException) throw error
                val mode = when (selection.transportType) {
                    Obd2TransportType.CLASSIC_SPP -> "Bluetooth Classic SPP"
                    Obd2TransportType.BLE_GATT -> "BLE GATT"
                    Obd2TransportType.WIFI_TCP -> "Wi-Fi TCP"
                }
                throw IOException("Не удалось подключиться через $mode. Проверь адрес, сеть и совместимость ELM327.", error)
            }
        }
    }

    /** Match the destination against an on-link Wi-Fi prefix, not a generic default route. */
    private fun findWifiNetworkForHost(host: String): Network {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IllegalStateException("Android не предоставляет информацию о сетях.")
        val destination = InetAddress.getByName(host)
        return manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            val properties = manager.getLinkProperties(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                properties?.linkAddresses?.any { linkAddress ->
                    runCatching {
                        IpPrefix(linkAddress.address, linkAddress.prefixLength).contains(destination)
                    }.getOrDefault(false)
                } == true
        } ?: throw IllegalStateException(
            "Сначала подключись в настройках Android к Wi-Fi-сети самого OBD-адаптера. " +
                "Clear GPS не переключает сеть автоматически; интернет на этой сети может быть недоступен."
        )
    }

    /**
     * BLE scanning starts only after the user explicitly requests it. Only named OBD-like
     * candidates are returned; advertisements are neither saved nor uploaded.
     */
    suspend fun scanBleAdapters(): List<Obd2Adapter> = withContext(Dispatchers.IO) {
        requireBluetoothScanPermission()
        val bluetoothAdapter = getBluetoothAdapter()
        if (!bluetoothAdapter.isEnabled) {
            throw IllegalStateException("Bluetooth выключен. Включи его в системных настройках.")
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE-сканер недоступен на этом устройстве.")
        val found = java.util.Collections.synchronizedMap(linkedMapOf<String, Obd2Adapter>())
        val failureCode = java.util.concurrent.atomic.AtomicInteger(0)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: safeDeviceName(device)
                if (!Obd2AdapterNameFilter.looksLikeObdAdapter(name)) return
                val address = device.address?.uppercase(Locale.ROOT).orEmpty()
                if (address.isBlank()) return
                found[address] = Obd2Adapter(name.trim(), address, Obd2TransportType.BLE_GATT)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failureCode.set(errorCode)
            }
        }
        try {
            @Suppress("MissingPermission")
            scanner.startScan(callback)
            delay(BLE_SCAN_DURATION_MS)
            val errorCode = failureCode.get()
            if (errorCode != 0) throw IOException("BLE-сканирование завершилось с ошибкой $errorCode.")
            synchronized(found) {
                found.values.toList()
                    .sortedWith(compareBy<Obd2Adapter> { it.name.lowercase(Locale.ROOT) }.thenBy { it.address })
            }
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) { }
        }
    }


    suspend fun readTelemetry(): Obd2Telemetry = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val current = activeSession ?: throw IllegalStateException("OBD-II адаптер не подключён.")
            try {
                // Discover capabilities once per connection. If the first bitmap is missing,
                // retain the conservative speed-only fallback rather than guessing support.
                if (!current.supportProbeCompleted) {
                    current.supportedPids = probeSupportedPids(current)
                    current.supportProbeCompleted = true
                }
                if (!current.vinProbeCompleted) {
                    probeVehicleVin(current)
                    current.vinProbeCompleted = true
                }

                val knownSupportedPids = current.supportedPids
                val responses = linkedMapOf<Int, String>()
                val issues = linkedMapOf<Int, Obd2PidIssue>()
                var speedSampleElapsedRealtimeNanos: Long? = null
                var speedQueryDurationMillis: Long? = null
                var engineRpmSampleElapsedRealtimeNanos: Long? = null
                var engineRpmQueryDurationMillis: Long? = null

                // Read less critical values first, then query RPM and speed consecutively.
                // This keeps their independent sample midpoints close enough for gear learning,
                // while speed remains the final (freshest) command in the cycle.
                val pollOrder = (Obd2Protocol.telemetryPids - 0x0C - 0x0D) + listOf(0x0C, 0x0D)
                for (pid in pollOrder) {
                    if (knownSupportedPids == null && pid != 0x0D) {
                        // No trustworthy capability bitmap: do not probe extra PIDs blindly.
                        continue
                    }
                    if (knownSupportedPids != null && pid !in knownSupportedPids) {
                        issues[pid] = Obd2PidIssue.PID_UNSUPPORTED
                        continue
                    }
                    if (current.pidCircuitBreaker.isDisabled(pid)) {
                        issues[pid] = Obd2PidIssue.PID_DISABLED_FOR_SESSION
                        continue
                    }

                    val queryStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                    val response = queryPid(current, pid)
                    val queryReceivedAtNanos = SystemClock.elapsedRealtimeNanos()
                    val queryDurationMillis = ((queryReceivedAtNanos - queryStartedAtNanos) / 1_000_000L)
                        .coerceAtLeast(0L)
                    if (Obd2Protocol.decode(pid, response) == null) {
                        val disabled = current.pidCircuitBreaker.recordFailure(pid)
                        issues[pid] = if (disabled) {
                            Obd2PidIssue.PID_DISABLED_FOR_SESSION
                        } else {
                            Obd2PidIssue.NO_VALID_RESPONSE
                        }
                    } else {
                        current.pidCircuitBreaker.recordSuccess(pid)
                        responses[pid] = response
                        val queryMidpointNanos =
                            queryStartedAtNanos + (queryReceivedAtNanos - queryStartedAtNanos) / 2L
                        if (pid == 0x0C) {
                            // ELM-compatible adapters expose no ECU timestamp; this midpoint is approximate.
                            engineRpmSampleElapsedRealtimeNanos = queryMidpointNanos
                            engineRpmQueryDurationMillis = queryDurationMillis
                        } else if (pid == 0x0D) {
                            speedSampleElapsedRealtimeNanos = queryMidpointNanos
                            speedQueryDurationMillis = queryDurationMillis
                        }
                    }
                }

                val decodedTelemetry = Obd2Protocol.telemetry(
                    responses = responses,
                    pidIssues = issues,
                    now = System.currentTimeMillis(),
                    vehicleVin = current.vehicleVin,
                    vinIssue = current.vinIssue,
                    speedSampleElapsedRealtimeNanos = speedSampleElapsedRealtimeNanos,
                    speedQueryDurationMillis = speedQueryDurationMillis,
                    engineRpmSampleElapsedRealtimeNanos = engineRpmSampleElapsedRealtimeNanos,
                    engineRpmQueryDurationMillis = engineRpmQueryDurationMillis
                )
                current.telemetryStabilityFilter.filter(decodedTelemetry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeSession?.transport === current.transport) activeSession = null
                current.transport.close()
                throw IOException("Чтение OBD-II прервано; переподключи адаптер.", error)
            }
        }
    }

    fun disconnect() {
        val current = activeSession
        activeSession = null
        if (current != null) current.transport.close()
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

    private suspend fun probeVehicleVin(session: Session) {
        // Mode 09 InfoType 00 must advertise InfoType 02 before the VIN is requested.
        val supportedInfoTypes = Obd2Protocol.supportedMode09InfoTypes(
            queryMode09InfoType(session, 0x00)
        )
        if (supportedInfoTypes == null) {
            session.vehicleVin = null
            session.vinIssue = Obd2VinIssue.NO_VALID_RESPONSE
            return
        }
        if (0x02 !in supportedInfoTypes) {
            session.vehicleVin = null
            session.vinIssue = Obd2VinIssue.INFO_TYPE_UNSUPPORTED
            return
        }

        val vin = Obd2Protocol.decodeVin(queryMode09InfoType(session, 0x02))
        session.vehicleVin = vin
        session.vinIssue = if (vin == null) Obd2VinIssue.NO_VALID_RESPONSE else null
    }

    private suspend fun queryMode09InfoType(session: Session, infoType: Int): String =
        exchange(session, Obd2Protocol.requestForMode09InfoType(infoType), PID_RESPONSE_TIMEOUT_MS)

    private suspend fun queryPid(session: Session, pid: Int): String {
        val request = Obd2Protocol.requestFor(pid)
        return exchange(session, request, PID_RESPONSE_TIMEOUT_MS)
    }

    private suspend fun exchange(session: Session, command: String, timeoutMs: Long): String {
        // Only adapter setup commands and allowlisted Mode 01/Mode 09 requests can reach this
        // method. The UI cannot issue arbitrary diagnostic or ECU-writing commands.
        return session.transport.exchange(command, timeoutMs)
    }

    private suspend fun connectWifiSocketCancellable(
        socket: Socket,
        host: String,
        port: Int,
        timeoutMs: Int
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val connector = Thread({
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
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
        }, "ClearGPS-OBD2-wifi-connect").apply { isDaemon = true }
        continuation.invokeOnCancellation {
            try { socket.close() } catch (_: Exception) { }
            connector.interrupt()
        }
        connector.start()
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
            throw SecurityException("Нужно разрешение «Устройства поблизости» для подключения к Bluetooth-адаптеру.")
        }
    }

    private fun requireBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Нужно разрешение «Устройства поблизости» для поиска BLE OBD-II адаптеров.")
            }
            requireBluetoothPermission()
        } else if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Для BLE-сканирования на этой версии Android нужно разрешение геолокации.")
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        try { device.name.orEmpty() } catch (_: SecurityException) { "" }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val ADAPTER_COMMAND_TIMEOUT_MS = 1_500L
        private const val PID_RESPONSE_TIMEOUT_MS = 2_200L
        private const val BLE_SCAN_DURATION_MS = 8_000L
        private const val WIFI_CONNECT_TIMEOUT_MS = 3_500

        // These AT commands configure only the ELM-compatible adapter, not the ECU.
        // ATSP0 improves compatibility across standard vehicle OBD protocols.
        private val ADAPTER_OUTPUT_SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")
    }
}