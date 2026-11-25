package com.open360.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.open360.core.model.Insta360CameraModel
import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.CameraTransportClient
import com.open360.core.protocol.LiveStreamProtocol
import com.open360.core.protocol.NotificationParser
import com.open360.core.protocol.PacketFramer
import com.open360.core.protocol.PacketStreamUnpacker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bluetooth Classic RFCOMM Serial Port Profile (SPP) camera transport client for legacy
 * Insta360 cameras such as ONE X that speak the standard Insta360 framed protocol over SPP
 * UUID `00001101-0000-1000-8000-00805F9B34FB`.
 */
class ClassicSppCameraClient(
    private val context: Context,
    private val deviceAddress: String,
    private val keepAliveIntervalMs: Long = 1000L
) : CameraTransportClient {

    data class BondedDeviceInfo(
        val name: String,
        val address: String,
        val model: Insta360CameraModel?
    )

    companion object {
        private const val TAG = "ClassicSppClient"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        /** Lists paired Bluetooth devices matching known Insta360 camera names (or all paired). */
        @SuppressLint("MissingPermission")
        fun listBondedDevices(context: Context): List<BondedDeviceInfo> {
            if (!hasBluetoothConnectPermission(context)) return emptyList()
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: return emptyList()
            return adapter.bondedDevices
                .orEmpty()
                .map { device ->
                    val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                    BondedDeviceInfo(
                        name = name,
                        address = device.address,
                        model = Insta360CameraModel.resolveByIdentity(deviceName = name)
                    )
                }
                .sortedWith(compareByDescending<BondedDeviceInfo> { it.model != null }.thenBy { it.name })
        }

        private fun hasBluetoothConnectPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }

    @Volatile
    override var onStreamPacket: ((LiveStreamProtocol.StreamPacket) -> Unit)? = null

    private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<CameraNotification> = _notifications.asSharedFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    override val isConnected: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private var readJob: Job? = null
    private var keepAliveJob: Job? = null

    private val writeMutex = Mutex()
    private val seqCounter = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<PacketFramer.Message>>()

    @SuppressLint("MissingPermission")
    override suspend fun connect(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            if (socket?.isConnected == true) return@withContext
            if (!hasBluetoothConnectPermission(context)) {
                throw SecurityException("Missing BLUETOOTH_CONNECT permission for Classic SPP")
            }
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter = manager?.adapter
                ?: throw IOException("Bluetooth adapter unavailable")
            val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
            runCatching { adapter.cancelDiscovery() }

            val rfcomm = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (primaryError: IOException) {
                Log.w(TAG, "Standard SPP connect failed, attempting channel 1 fallback: ${primaryError.message}")
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (method.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            }

            socket = rfcomm
            outputStream = rfcomm.outputStream
            inputStream = rfcomm.inputStream
            _connectionState.emit(true)

            sendRawPacket(PacketFramer.buildSyncPacket())

            readJob = scope.launch(Dispatchers.IO) {
                readLoop()
            }
            keepAliveJob = scope.launch(Dispatchers.IO) {
                while (isActive && socket?.isConnected == true) {
                    kotlinx.coroutines.delay(keepAliveIntervalMs)
                    try {
                        sendRawPacket(PacketFramer.buildKeepAlivePacket())
                    } catch (e: IOException) {
                        Log.e(TAG, "Keep-alive write failed: ${e.message}")
                        break
                    }
                }
            }
        }
    }

    private suspend fun readLoop() {
        val unpacker = PacketStreamUnpacker()
        val buffer = ByteArray(16_384)
        val stream = inputStream ?: return

        try {
            while (socket?.isConnected == true) {
                val read = stream.read(buffer)
                if (read == -1) break
                if (read <= 0) continue
                unpacker.append(buffer, read)
                val drained = ArrayList<ByteArray>()
                unpacker.drainPackets { drained.add(it) }
                for (payload in drained) {
                    handleIncomingPayload(payload)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "SPP read loop closed: ${e.message}")
        } finally {
            disconnect()
        }
    }

    private suspend fun handleIncomingPayload(payload: ByteArray) {
        if (payload.contentEquals(PacketFramer.SYNC_PAYLOAD) ||
            payload.contentEquals(PacketFramer.KEEPALIVE_PAYLOAD)
        ) {
            return
        }

        if (payload.size > 4 && payload[0] == LiveStreamProtocol.PACKET_TYPE_STREAM) {
            LiveStreamProtocol.parseStreamPacket(payload)?.let { pkt ->
                onStreamPacket?.invoke(pkt)
            }
            return
        }

        PacketFramer.parseMessage(payload)?.let { msg ->
            val deferred = pendingRequests.remove(msg.sequenceNumber)
            if (deferred != null) {
                deferred.complete(msg)
            } else {
                NotificationParser.parse(payload)?.let { notif ->
                    _notifications.emit(notif)
                }
            }
        }
    }

    override suspend fun request(
        messageCode: Int,
        protobufBytes: ByteArray,
        timeoutMs: Long
    ): PacketFramer.Message? {
        val seq = seqCounter.getAndUpdate { (it % 254) + 1 }
        val deferred = CompletableDeferred<PacketFramer.Message>()
        pendingRequests[seq] = deferred
        return try {
            val packet = PacketFramer.buildCommandPacket(messageCode, seq, protobufBytes)
            sendRawPacket(packet)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingRequests.remove(seq)
        }
    }

    override suspend fun sendCommand(
        messageCode: Int,
        sequenceNumber: Int,
        protobufBytes: ByteArray
    ) {
        sendRawPacket(PacketFramer.buildCommandPacket(messageCode, sequenceNumber, protobufBytes))
    }

    private suspend fun sendRawPacket(packet: ByteArray) = writeMutex.withLock {
        val out = outputStream ?: throw IOException("Classic SPP socket not connected")
        out.write(packet)
        out.flush()
    }

    override fun disconnect() {
        readJob?.cancel()
        keepAliveJob?.cancel()
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        runCatching { socket?.close() }
        socket = null
        outputStream = null
        inputStream = null
        _connectionState.tryEmit(false)
    }
}
