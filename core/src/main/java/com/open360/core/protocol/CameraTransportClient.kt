package com.open360.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Transport-agnostic framed camera control & streaming session contract implemented across
 * Wi-Fi TCP socket (`CameraSocketClient`), USB-C bulk (`CameraUsbClient`), and Classic SPP
 * (`ClassicSppCameraClient`).
 */
interface CameraTransportClient {
    var onStreamPacket: ((LiveStreamProtocol.StreamPacket) -> Unit)?
    val notifications: SharedFlow<CameraNotification>
    val isConnected: SharedFlow<Boolean>

    suspend fun connect(scope: CoroutineScope)

    suspend fun request(
        messageCode: Int,
        protobufBytes: ByteArray = ByteArray(0),
        timeoutMs: Long = 5_000L
    ): PacketFramer.Message?

    suspend fun sendCommand(
        messageCode: Int,
        sequenceNumber: Int,
        protobufBytes: ByteArray = ByteArray(0)
    )

    fun disconnect()
}
