package com.open360.core.socket

import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.NotificationParser
import com.open360.core.protocol.PacketFramer
import kotlinx.coroutines.CancellationException
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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class CameraSocketClient(
    private val host: String = "192.168.42.1",
    private val port: Int = 6666
) {
    private var socket: Socket? = null
    private var readJob: Job? = null
    private var keepAliveJob: Job? = null

    private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<CameraNotification> = _notifications.asSharedFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    val isConnected: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    suspend fun connect(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        disconnect()

        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), 4000)
        sock.soTimeout = 5000
        socket = sock

        val output = sock.getOutputStream()
        val input = sock.getInputStream()

        // 1. Send sync handshake:
        output.write(PacketFramer.buildSyncPacket())
        output.flush()

        // 2. Send initial keepalive:
        output.write(PacketFramer.buildKeepAlivePacket())
        output.flush()

        _connectionState.emit(true)

        // 3. Start periodic keepalive heartbeat (every 1.8 seconds)
        keepAliveJob = scope.launch(Dispatchers.IO) {
            val keepAliveBytes = PacketFramer.buildKeepAlivePacket()
            while (isActive) {
                delay(1800)
                try {
                    synchronized(output) {
                        output.write(keepAliveBytes)
                        output.flush()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    disconnect()
                    break
                }
            }
        }

        // 4. Start receiving loop
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            var accumulated = ByteArray(0)

            while (isActive) {
                try {
                    val readBytes = input.read(buffer)
                    if (readBytes == -1) break

                    val incoming = buffer.copyOf(readBytes)
                    accumulated += incoming

                    val (packets, remaining) = PacketFramer.unpackStreams(accumulated)
                    accumulated = remaining

                    for (packet in packets) {
                        NotificationParser.parse(packet)?.let { notif ->
                            _notifications.emit(notif)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    break
                }
            }
            disconnect()
        }
    }

    suspend fun sendCommand(messageCode: Int, sequenceNumber: Int, protobufBytes: ByteArray = ByteArray(0)) = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext
        val packet = PacketFramer.buildCommandPacket(messageCode, sequenceNumber, protobufBytes)
        synchronized(sock.getOutputStream()) {
            sock.getOutputStream().write(packet)
            sock.getOutputStream().flush()
        }
    }

    fun disconnect() {
        keepAliveJob?.cancel()
        keepAliveJob = null

        readJob?.cancel()
        readJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        _connectionState.tryEmit(false)
    }
}
