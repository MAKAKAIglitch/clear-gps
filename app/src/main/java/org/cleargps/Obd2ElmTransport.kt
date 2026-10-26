package org.cleargps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** One serialized ELM exchange: send a command and return its response without the prompt. */
internal interface Obd2ElmTransport {
    suspend fun exchange(command: String, timeoutMs: Long): String
    fun close()
}

/** Stream implementation for already-paired Bluetooth Classic RFCOMM/SPP adapters. */
internal class ClassicObd2Transport(private val socket: android.bluetooth.BluetoothSocket) : Obd2ElmTransport {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    override suspend fun exchange(command: String, timeoutMs: Long): String {
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        return readUntilPrompt(input, timeoutMs)
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) { }
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

    companion object {
        private const val MAX_RESPONSE_CHARS = 2_048
    }
}

/**
 * BLE/GATT ELM transport. It selects only a characteristic pair from discovered GATT services:
 * one writable characteristic and one notify/indicate characteristic. It never sends arbitrary
 * commands; the repository still owns the fixed AT setup list and SAE-J1979 request allowlists.
 */
internal class BleGattObd2Transport(
    private val context: Context,
    private val device: BluetoothDevice
) : BluetoothGattCallback(), Obd2ElmTransport {
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Unit>()
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val exchangeMutex = Mutex()
    @Volatile private var pendingWrite: CompletableDeferred<Int>? = null

    @SuppressLint("MissingPermission")
    suspend fun connect(): BleGattObd2Transport {
        try {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("Android не смог создать BLE-соединение.")
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
            return this
        } catch (error: Exception) {
            close()
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw IOException("Не удалось подключиться к BLE OBD-II адаптеру или настроить его GATT-канал.", error)
        }
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String = exchangeMutex.withLock {
        val currentGatt = gatt ?: throw IOException("BLE-соединение закрыто.")
        val writable = writeCharacteristic ?: throw IOException("BLE write-характеристика не найдена.")
        withTimeout(timeoutMs) {
            val acknowledgement = CompletableDeferred<Int>()
            pendingWrite = acknowledgement
            writable.writeType = if (
                writable.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            ) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            val accepted = run {
                writable.value = (command + "\r").toByteArray(Charsets.US_ASCII)
                currentGatt.writeCharacteristic(writable)
            }
            if (!accepted) {
                pendingWrite = null
                throw IOException("BLE-адаптер отклонил команду записи.")
            }
            val writeStatus = acknowledgement.await()
            pendingWrite = null
            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("BLE-запись завершилась ошибкой GATT: $writeStatus.")
            }

            val response = StringBuilder()
            while (true) {
                coroutineContext.ensureActive()
                val notification = notifications.receiveCatching()
                val bytes = notification.getOrNull()
                    ?: throw IOException("BLE-канал уведомлений закрыт.", notification.exceptionOrNull())
                response.append(String(bytes, Charsets.US_ASCII))
                val promptIndex = response.lastIndexOf(">")
                if (promptIndex >= 0) return@withTimeout response.toString().substring(0, promptIndex)
                if (response.length > MAX_RESPONSE_CHARS) {
                    throw IOException("Слишком длинный BLE-ответ адаптера.")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    override fun close() {
        val error = IOException("BLE-соединение закрыто.")
        if (!ready.isCompleted) ready.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        pendingWrite = null
        notifications.close(error)
        val current = gatt
        gatt = null
        try { current?.disconnect() } catch (_: Exception) { }
        try { current?.close() } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(IOException("BLE-соединение завершилось с ошибкой GATT: $status."))
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (!gatt.discoverServices()) fail(IOException("Не удалось обнаружить BLE-сервисы."))
            }
            BluetoothProfile.STATE_DISCONNECTED -> fail(IOException("BLE-соединение с OBD-II адаптером потеряно."))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(IOException("Не удалось прочитать BLE-сервисы: $status."))
            return
        }
        val pair = findCharacteristicPair(gatt.services)
        if (pair == null) {
            fail(IOException("Не найдена совместимая BLE-пара характеристик записи и уведомлений."))
            return
        }
        writeCharacteristic = pair.first
        notifyCharacteristic = pair.second
        if (!gatt.setCharacteristicNotification(pair.second, true)) {
            fail(IOException("Не удалось включить BLE-уведомления."))
            return
        }
        val descriptor = pair.second.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            fail(IOException("BLE notify-характеристика не содержит CCCD-дескриптор."))
            return
        }
        @Suppress("DEPRECATION")
        val writeStarted = run {
            descriptor.value = if (
                pair.second.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                pair.second.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        if (!writeStarted) fail(IOException("BLE-адаптер отклонил настройку уведомлений."))
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.uuid != CCCD_UUID) return
        if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
        else fail(IOException("Не удалось подписаться на BLE-ответы: $status."))
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (characteristic.uuid != writeCharacteristic?.uuid) return
        pendingWrite?.complete(status)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid == notifyCharacteristic?.uuid) {
            characteristic.value?.let { notifications.trySend(it.copyOf()) }
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid == notifyCharacteristic?.uuid) {
            notifications.trySend(value.copyOf())
        }
    }

    private fun fail(error: Exception) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        notifications.close(error)
    }

    private fun findCharacteristicPair(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val preferredServices = listOf(
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), // Nordic UART Service
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb")
        )
        val preferredNotify = listOf(
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        )
        val preferredWrite = listOf(
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        )
        val ordered = services.sortedBy { service ->
            val index = preferredServices.indexOf(service.uuid)
            if (index < 0) Int.MAX_VALUE else index
        }
        for (service in ordered) {
            val characteristics = service.characteristics
            val notifiers = characteristics.filter {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            }.sortedBy {
                val index = preferredNotify.indexOf(it.uuid)
                if (index < 0) Int.MAX_VALUE else index
            }
            val writers = characteristics.filter {
                it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            }.sortedBy {
                val index = preferredWrite.indexOf(it.uuid)
                if (index < 0) Int.MAX_VALUE else index
            }
            for (notifier in notifiers) {
                for (writer in writers) {
                    if (notifier.uuid != writer.uuid || (
                        notifier.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                            notifier.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                        )
                    ) return writer to notifier
                }
            }
        }
        return null
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val MAX_RESPONSE_CHARS = 2_048
    }
}
