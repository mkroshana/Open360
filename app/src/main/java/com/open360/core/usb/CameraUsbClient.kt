package com.open360.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct USB-C / OTG transport implementing [CameraTransportClient] over Android `UsbManager`
 * bulk endpoints (`0x81` IN, `0x01` OUT) with the 64 KB buffer and 5000 ms timeout defined in
 * [Insta360UsbConfig].
 */
class CameraUsbClient(
    private val context: Context,
    private val targetDeviceName: String? = null,
    private val keepAliveIntervalMs: Long = 1000L
) : CameraTransportClient {

    data class AttachedUsbDevice(
        val device: UsbDevice,
        val role: Insta360UsbConfig.UsbDeviceRole,
        val displayName: String,
        val hasPermission: Boolean,
        val model: Insta360CameraModel?
    )

    companion object {
        private const val TAG = "CameraUsbClient"
        const val ACTION_USB_PERMISSION = "com.open360.USB_CAMERA_PERMISSION"

        /** Inspects all currently attached USB devices and classifies cameras vs Quick Readers. */
        fun inspectAttachedDevices(context: Context): List<AttachedUsbDevice> {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return usbManager.deviceList.values.map { device ->
                val hasBulk = findBulkInterfaceAndEndpoints(device) != null
                val role = Insta360UsbConfig.classifyDevice(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    hasBulkInAndOutEndpoints = hasBulk
                )
                val productName = runCatching { device.productName }.getOrNull()?.trim().orEmpty()
                val label = productName.ifEmpty {
                    String.format("USB %04X:%04X", device.vendorId, device.productId)
                }
                AttachedUsbDevice(
                    device = device,
                    role = role,
                    displayName = label,
                    hasPermission = usbManager.hasPermission(device),
                    model = Insta360CameraModel.resolveByIdentity(deviceName = label)
                )
            }
        }

        private data class BulkMapping(
            val usbInterface: UsbInterface,
            val epIn: UsbEndpoint,
            val epOut: UsbEndpoint
        )

        private fun findBulkInterfaceAndEndpoints(device: UsbDevice): BulkMapping? {
            for (ifaceIdx in 0 until device.interfaceCount) {
                val iface = device.getInterface(ifaceIdx)
                var candidateIn: UsbEndpoint? = null
                var candidateOut: UsbEndpoint? = null
                for (epIdx in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(epIdx)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        if (ep.address == Insta360UsbConfig.BULK_ENDPOINT_IN || candidateIn == null) {
                            candidateIn = ep
                        }
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        if (ep.address == Insta360UsbConfig.BULK_ENDPOINT_OUT || candidateOut == null) {
                            candidateOut = ep
                        }
                    }
                }
                if (candidateIn != null && candidateOut != null) {
                    return BulkMapping(iface, candidateIn, candidateOut)
                }
            }
            return null
        }

        /** Prompts the user for Android `UsbManager` device permission and awaits grant/deny. */
        suspend fun requestDevicePermission(
            context: Context,
            device: UsbDevice,
            timeoutMs: Long = 30_000L
        ): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            if (usbManager.hasPermission(device)) return true

            val result = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    result.complete(granted)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            return try {
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    device.deviceId,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                usbManager.requestPermission(device, permissionIntent)
                withTimeoutOrNull(timeoutMs) { result.await() } ?: false
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    @Volatile
    override var onStreamPacket: ((LiveStreamProtocol.StreamPacket) -> Unit)? = null

    private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<CameraNotification> = _notifications.asSharedFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    override val isConnected: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var readJob: Job? = null
    private var keepAliveJob: Job? = null

    private val writeMutex = Mutex()
    private val seqCounter = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<PacketFramer.Message>>()

    override suspend fun connect(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            if (connection != null) return@withContext
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: throw IOException("USB host service unavailable on this device")

            val attached = inspectAttachedDevices(context)
            val candidate = if (!targetDeviceName.isNullOrBlank()) {
                attached.firstOrNull {
                    it.device.deviceName == targetDeviceName &&
                        it.role == Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL
                }
            } else {
                attached.firstOrNull { it.role == Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL }
            } ?: run {
                val hasReader = attached.any { it.role == Insta360UsbConfig.UsbDeviceRole.QUICK_READER_STORAGE }
                if (hasReader) {
                    throw IOException("Quick Reader detected (storage only). Switch USB mode or attach camera directly for control/stream.")
                }
                throw IOException("No Insta360 USB camera detected. Connect via USB-C cable and enable USB mode if prompted.")
            }

            if (!usbManager.hasPermission(candidate.device)) {
                val granted = requestDevicePermission(context, candidate.device)
                if (!granted) {
                    throw SecurityException("USB permission denied for ${candidate.displayName}")
                }
            }

            val mapping = findBulkInterfaceAndEndpoints(candidate.device)
                ?: throw IOException("No bulk IN/OUT endpoints exposed by ${candidate.displayName}")
            val usbConnection = usbManager.openDevice(candidate.device)
                ?: throw IOException("Failed to open USB connection to ${candidate.displayName}")
            if (!usbConnection.claimInterface(mapping.usbInterface, true)) {
                usbConnection.close()
                throw IOException("Failed to claim USB interface on ${candidate.displayName}")
            }

            connection = usbConnection
            claimedInterface = mapping.usbInterface
            epIn = mapping.epIn
            epOut = mapping.epOut
            _connectionState.emit(true)

            sendRawPacket(PacketFramer.buildSyncPacket())

            readJob = scope.launch(Dispatchers.IO) {
                readLoop()
            }
            keepAliveJob = scope.launch(Dispatchers.IO) {
                while (isActive && connection != null) {
                    delay(keepAliveIntervalMs)
                    try {
                        sendRawPacket(PacketFramer.buildKeepAlivePacket())
                    } catch (e: IOException) {
                        Log.w(TAG, "USB keep-alive failed: ${e.message}")
                        break
                    }
                }
            }
        }
    }

    private suspend fun readLoop() {
        val unpacker = PacketStreamUnpacker()
        val buffer = ByteArray(Insta360UsbConfig.TRANSFER_BUFFER_SIZE_BYTES)
        val conn = connection ?: return
        val inEndpoint = epIn ?: return

        try {
            while (connection != null) {
                val transferred = conn.bulkTransfer(inEndpoint, buffer, buffer.size, 250)
                if (transferred < 0) continue
                if (transferred == 0) continue
                unpacker.append(buffer, transferred)
                val drained = ArrayList<ByteArray>()
                unpacker.drainPackets { drained.add(it) }
                for (payload in drained) {
                    handleIncomingPayload(payload)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "USB read loop ended: ${e.message}")
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
        val conn = connection ?: throw IOException("USB camera not connected")
        val outEndpoint = epOut ?: throw IOException("USB OUT endpoint missing")
        var offset = 0
        while (offset < packet.size) {
            val slice = if (offset == 0) packet else packet.copyOfRange(offset, packet.size)
            val written = conn.bulkTransfer(
                outEndpoint,
                slice,
                slice.size,
                Insta360UsbConfig.TRANSFER_TIMEOUT_MS
            )
            if (written <= 0) {
                throw IOException("USB bulkTransfer write failed ($written)")
            }
            offset += written
        }
    }

    override fun disconnect() {
        readJob?.cancel()
        keepAliveJob?.cancel()
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        val conn = connection
        val iface = claimedInterface
        connection = null
        claimedInterface = null
        epIn = null
        epOut = null
        if (conn != null) {
            if (iface != null) runCatching { conn.releaseInterface(iface) }
            runCatching { conn.close() }
        }
        _connectionState.tryEmit(false)
    }
}
