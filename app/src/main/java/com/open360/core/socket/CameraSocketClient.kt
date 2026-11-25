package com.open360.core.socket

import android.net.Network
import android.util.Log
import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.LiveStreamProtocol
import com.open360.core.protocol.NotificationParser
import com.open360.core.protocol.PacketFramer
import com.open360.core.protocol.PacketStreamUnpacker
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CameraSocketClient(
    private val host: String = "192.168.42.1",
    private val port: Int = 6666,
    private val keepAliveIntervalMs: Long = 1000L,
    private val network: Network? = null
) : com.open360.core.protocol.CameraTransportClient {

    private companion object {
        const val TAG = "CameraSocketClient"
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 5000
        const val SYNC_HANDSHAKE_TIMEOUT_MS = 1500L
        const val HEARTBEAT_INITIAL_DELAY_MS = 1500L
        const val SOCKET_RCVBUF_BYTES = 1024 * 1024
        const val PACKET_TYPE_SYNC: Byte = 0x06
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val writeLock = Any()
    private var readJob: Job? = null
    private var keepAliveJob: Job? = null

    /** High-throughput callback for `0x01` multiplexed media stream packets (video/audio/gyro). */
    @Volatile
    override var onStreamPacket: ((LiveStreamProtocol.StreamPacket) -> Unit)? = null

    private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<CameraNotification> = _notifications.asSharedFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    override val isConnected: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    // Requests waiting for their reply, by sequence number
    private val nextSequence = AtomicInteger(1)
    private val pendingReplies = ConcurrentHashMap<Int, CompletableDeferred<PacketFramer.Message>>()

    override suspend fun connect(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        disconnect()

        val sock = Socket()
        var boundToNetwork = false
        if (network != null) {
            try {
                network.bindSocket(sock)
                boundToNetwork = true
                Log.i(TAG, "Bound TCP socket to network=$network handle=${network.networkHandle}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind TCP socket to network=$network; falling back to default route", e)
            }
        }
        runCatching { sock.tcpNoDelay = true }
        runCatching { sock.receiveBufferSize = SOCKET_RCVBUF_BYTES }
        sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        sock.soTimeout = READ_TIMEOUT_MS
        socket = sock

        val output = sock.getOutputStream()
        outputStream = output
        val input = sock.getInputStream()

        val syncAck = CompletableDeferred<Boolean>()

        // 1. Start receiving loop before sending sync so the camera's 0x06 ack is never missed
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(64 * 1024)
            val unpacker = PacketStreamUnpacker()
            var loggedFirstStreamPacket = false
            var loggedParseFailure = false

            while (isActive) {
                try {
                    val readBytes = input.read(buffer)
                    if (readBytes == -1) {
                        Log.i(TAG, "Socket EOF from $host:$port")
                        break
                    }

                    unpacker.append(buffer, readBytes)
                    unpacker.drainPackets { packet ->
                        if (packet.isNotEmpty() && packet[0] == PACKET_TYPE_SYNC) {
                            syncAck.complete(true)
                            return@drainPackets
                        }

                        if (packet.isNotEmpty() && packet[0] == LiveStreamProtocol.PACKET_TYPE_STREAM) {
                            val parsed = LiveStreamProtocol.parseStreamPacket(packet)
                            if (parsed == null) {
                                if (!loggedParseFailure) {
                                    loggedParseFailure = true
                                    val headHex = packet.take(minOf(24, packet.size))
                                        .joinToString("") { "%02x".format(it) }
                                    Log.w(TAG, "Malformed 0x01 stream packet (${packet.size}B): $headHex")
                                }
                                return@drainPackets
                            }
                            if (!loggedFirstStreamPacket) {
                                loggedFirstStreamPacket = true
                                val headHex = parsed.data.take(minOf(16, parsed.data.size))
                                    .joinToString("") { "%02x".format(it) }
                                Log.i(
                                    TAG,
                                    "First 0x01 stream packet: type=0x%02x (${parsed.kind}), tsMs=${parsed.timestampMs}, bodySize=${parsed.data.size}, head=$headHex"
                                        .format(parsed.streamType)
                                )
                            }
                            onStreamPacket?.invoke(parsed)
                            return@drainPackets
                        }

                        // A reply to a request goes to whoever is waiting for it
                        val message = PacketFramer.parseMessage(packet)
                        if (message != null && message.messageCode != 200) {
                            Log.d(
                                TAG,
                                "RX message code=${message.messageCode} seq=${message.sequenceNumber} size=${message.body.size}"
                            )
                        }
                        val waiting = message?.let { pendingReplies[it.sequenceNumber] }
                        if (waiting != null) {
                            waiting.complete(message)
                            return@drainPackets
                        }
                        NotificationParser.parse(packet)?.let { notif ->
                            _notifications.tryEmit(notif)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal when the camera has nothing to send for 5 s; keep reading
                    continue
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Socket read error on $host:$port: ${e.message}")
                    break
                }
            }
            syncAck.complete(false)
            disconnect()
        }

        // 2. Send sync handshake and await camera ack (mirrors CameraTransportHandshake)
        try {
            synchronized(writeLock) {
                output.write(PacketFramer.buildSyncPacket())
                output.flush()
            }
        } catch (e: Exception) {
            disconnect()
            throw e
        }

        val synced = withTimeoutOrNull(SYNC_HANDSHAKE_TIMEOUT_MS) { syncAck.await() } ?: false
        Log.i(TAG, "Socket connected to $host:$port (boundNetwork=$boundToNetwork, syncAck=$synced)")

        _connectionState.emit(true)

        // 3. Start periodic keepalive heartbeat (official HeartbeatStep: initialDelayMs=1500, intervalMs=1000)
        keepAliveJob = scope.launch(Dispatchers.IO) {
            delay(HEARTBEAT_INITIAL_DELAY_MS)
            val keepAliveBytes = PacketFramer.buildKeepAlivePacket()
            while (isActive) {
                try {
                    synchronized(writeLock) {
                        output.write(keepAliveBytes)
                        output.flush()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Keepalive write failed on $host:$port: ${e.message}")
                    disconnect()
                    break
                }
                delay(keepAliveIntervalMs)
            }
        }
    }

    /**
     * Sends a command and waits for the camera's reply, which carries the same sequence number.
     * Null when the link is down or no reply comes within [timeoutMs]. A GO 3S listed 74 files
     * in 0.08 s.
     */
    override suspend fun request(messageCode: Int, protobufBytes: ByteArray, timeoutMs: Long): PacketFramer.Message? {
        if (socket == null) return null
        val seq = nextSequence.getAndIncrement()
        val reply = CompletableDeferred<PacketFramer.Message>()
        pendingReplies[seq] = reply
        try {
            sendCommand(messageCode, seq, protobufBytes)
            return withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            pendingReplies.remove(seq)
        }
    }

    override suspend fun sendCommand(messageCode: Int, sequenceNumber: Int, protobufBytes: ByteArray) = withContext(Dispatchers.IO) {
        val output = outputStream ?: return@withContext
        val packet = PacketFramer.buildCommandPacket(messageCode, sequenceNumber, protobufBytes)
        synchronized(writeLock) {
            output.write(packet)
            output.flush()
        }
    }

    override fun disconnect() {
        keepAliveJob?.cancel()
        keepAliveJob = null

        readJob?.cancel()
        readJob = null

        pendingReplies.values.forEach { it.cancel() }
        pendingReplies.clear()

        outputStream = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        _connectionState.tryEmit(false)
    }
}
