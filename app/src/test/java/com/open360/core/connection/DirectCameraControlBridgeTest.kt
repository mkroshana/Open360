package com.open360.core.connection

import com.open360.core.ble.CameraMode
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.CameraTransportClient
import com.open360.core.protocol.LiveStreamProtocol
import com.open360.core.protocol.PacketFramer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCameraControlBridgeTest {

    private data class RecordedRequest(
        val messageCode: Int,
        val payload: ByteArray,
        val deferred: CompletableDeferred<PacketFramer.Message?>
    )

    private class FakeDirectTransportClient(
        private val autoReply200: Boolean = true,
        private val initialStatusPayload: ByteArray? = null
    ) : CameraTransportClient {
        override var onStreamPacket: ((LiveStreamProtocol.StreamPacket) -> Unit)? = null
        private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 32)
        override val notifications: SharedFlow<CameraNotification> = _notifications.asSharedFlow()
        private val _isConnected = MutableSharedFlow<Boolean>(replay = 1)
        override val isConnected: SharedFlow<Boolean> = _isConnected.asSharedFlow()

        val requests = mutableListOf<RecordedRequest>()
        private var seq = 1

        override suspend fun connect(scope: CoroutineScope) {
            _isConnected.emit(true)
        }

        override suspend fun request(
            messageCode: Int,
            protobufBytes: ByteArray,
            timeoutMs: Long
        ): PacketFramer.Message? {
            val s = seq++
            if (messageCode == Go2BleProtocol.CMD_GET_OPTIONS &&
                protobufBytes.contentEquals(Go2BleProtocol.buildStatusOptionsRequest()) &&
                initialStatusPayload != null
            ) {
                return PacketFramer.Message(200, s, initialStatusPayload)
            }
            if (messageCode == Go2BleProtocol.CMD_GET_OPTIONS) {
                return PacketFramer.Message(200, s, ByteArray(0))
            }
            val deferred = CompletableDeferred<PacketFramer.Message?>()
            requests.add(RecordedRequest(messageCode, protobufBytes, deferred))
            return if (autoReply200) {
                PacketFramer.Message(200, s, ByteArray(0))
            } else {
                deferred.await()
            }
        }

        override suspend fun sendCommand(messageCode: Int, sequenceNumber: Int, protobufBytes: ByteArray) = Unit

        override fun disconnect() {
            _isConnected.tryEmit(false)
        }

        fun emitNotification(notif: CameraNotification) {
            _notifications.tryEmit(notif)
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value0: Int) {
        var v = value0
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun varintField(field: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, field shl 3)
        writeVarint(out, value)
        return out.toByteArray()
    }

    private fun statusOptionsReplyPayload(photoSubMode: Int, videoSubMode: Int, captureSecondsLeft: Int): ByteArray {
        val options = varintField(9, captureSecondsLeft) +
            varintField(40, photoSubMode) +
            varintField(41, videoSubMode)
        val out = ByteArrayOutputStream()
        writeVarint(out, (2 shl 3) or 2)
        writeVarint(out, options.size)
        out.write(options)
        return out.toByteArray()
    }

    @Test
    fun queryInitialDetailsAndNotificationsTrackSubModesAndCaptureSecondsLeft() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeDirectTransportClient(
            autoReply200 = true,
            initialStatusPayload = statusOptionsReplyPayload(photoSubMode = 100, videoSubMode = 5, captureSecondsLeft = 4796)
        )
        val bridge = DirectCameraControlBridge(dispatcher)
        bridge.attachConnectedClientForTesting(client)

        assertEquals(Go2BleProtocol.SubModes(100, 5), bridge.state.value.subModes)
        assertEquals(4796, bridge.state.value.captureSecondsLeft)

        // Incoming 0x2053 & 0x2021 update state and remembered photo sub-mode (HDR Photo = 1)
        client.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE,
                varintField(1, 1) + varintField(2, 100)
            )
        )
        client.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT,
                varintField(1, 1105)
            )
        )
        assertEquals(Go2BleProtocol.SubModes(1, 100), bridge.state.value.subModes)
        assertEquals(1105, bridge.state.value.captureSecondsLeft)

        // Switching group back to Video uses remembered video sub-mode 5 (FreeFrame)
        bridge.setCaptureGroup(photo = false)
        val toVideo = client.requests.last()
        assertEquals(Go2BleProtocol.CMD_SET_OPTIONS, toVideo.messageCode)
        assertTrue(Go2BleProtocol.buildSetSubModePayload(false, 5).contentEquals(toVideo.payload))
        assertEquals(Go2BleProtocol.SubModes(100, 5), bridge.state.value.subModes)

        // Switching group back to Photo uses remembered photo sub-mode 1 (HDR Photo)
        bridge.setCaptureGroup(photo = true)
        val toPhoto = client.requests.last()
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 1).contentEquals(toPhoto.payload))
        assertEquals(Go2BleProtocol.SubModes(1, 100), bridge.state.value.subModes)

        bridge.disconnect()
        assertNull(bridge.state.value.subModes)
        assertNull(bridge.state.value.captureSecondsLeft)
    }

    @Test
    fun setCameraModeSerializesCoalescesAndGuardsStaleNotifications() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeDirectTransportClient(
            autoReply200 = false,
            initialStatusPayload = statusOptionsReplyPayload(photoSubMode = 100, videoSubMode = 0, captureSecondsLeft = 3600)
        )
        val bridge = DirectCameraControlBridge(dispatcher)
        bridge.attachConnectedClientForTesting(client)

        // Rapid burst: switch 1 sends immediately on the command queue; switches 2 & 3 queue behind it.
        bridge.setCameraMode(CameraMode.FREEFRAME_PRO)
        bridge.setCameraMode(CameraMode.TIMELAPSE)
        bridge.setCameraMode(CameraMode.HDR_PHOTO)
        assertEquals(1, client.requests.size)

        // An intermediate 0x2053 for FreeFrame arriving while HDR_PHOTO is queued must NOT snap UI back
        // or invalidate the queued HDR_PHOTO request.
        client.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE,
                varintField(1, 100) + varintField(2, 5)
            )
        )
        assertEquals(Go2BleProtocol.SubModes(100, 0), bridge.state.value.subModes)

        // Complete request 1 (FreeFrame) with 200 -> queued TIMELAPSE is coalesced/skipped, HDR_PHOTO sends next!
        client.requests[0].deferred.complete(PacketFramer.Message(200, 1, ByteArray(0)))
        assertEquals(2, client.requests.size)
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 1).contentEquals(client.requests[1].payload))

        // If newest HDR_PHOTO returns 500 error, it must NOT apply HDR_PHOTO
        client.requests[1].deferred.complete(PacketFramer.Message(500, 2, ByteArray(0)))
        assertEquals(Go2BleProtocol.SubModes(100, 0), bridge.state.value.subModes)

        // Subsequent Timelapse succeeds with 200 and updates subModes cleanly
        bridge.setCameraMode(CameraMode.TIMELAPSE)
        assertEquals(3, client.requests.size)
        client.requests[2].deferred.complete(PacketFramer.Message(200, 3, ByteArray(0)))
        assertEquals(Go2BleProtocol.SubModes(100, 2), bridge.state.value.subModes)

        bridge.disconnect()
    }

    @Test
    fun captureCommandsUseModeSpecificCommandsAndClearStateOnDisconnect() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = FakeDirectTransportClient(
            autoReply200 = false,
            initialStatusPayload = statusOptionsReplyPayload(photoSubMode = 1, videoSubMode = 0, captureSecondsLeft = 1000)
        )
        val bridge = DirectCameraControlBridge(dispatcher)
        bridge.attachConnectedClientForTesting(client)

        // HDR Photo uses CMD_TAKE_HDR_PICTURE rather than plain CMD_TAKE_PICTURE
        bridge.takePhoto(CameraMode.HDR_PHOTO)
        assertEquals(1, client.requests.size)
        assertEquals(CameraMode.HDR_PHOTO.startCommand, client.requests[0].messageCode)
        assertTrue(bridge.state.value.isTakingPhoto)
        client.requests[0].deferred.complete(PacketFramer.Message(200, 1, ByteArray(0)))
        assertEquals(false, bridge.state.value.isTakingPhoto)

        // Continuous interval photo uses startCommand/stopCommand via client.request (not unserialized sendCommand)
        bridge.startRecording(CameraMode.INTERVAL_PHOTO)
        assertEquals(2, client.requests.size)
        assertEquals(CameraMode.INTERVAL_PHOTO.startCommand, client.requests[1].messageCode)
        assertTrue(bridge.state.value.isCaptureStarting)
        client.requests[1].deferred.complete(PacketFramer.Message(200, 2, ByteArray(0)))
        assertEquals(false, bridge.state.value.isCaptureStarting)
        assertTrue(bridge.state.value.isRecording)

        bridge.stopRecording(CameraMode.INTERVAL_PHOTO)
        assertEquals(3, client.requests.size)
        assertEquals(CameraMode.INTERVAL_PHOTO.stopCommand, client.requests[2].messageCode)
        client.requests[2].deferred.complete(PacketFramer.Message(200, 3, ByteArray(0)))
        assertEquals(false, bridge.state.value.isRecording)

        // After disconnect, late notifications from the old client cannot mutate state
        bridge.disconnect()
        client.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE,
                varintField(1, 1) + varintField(2, 100)
            )
        )
        client.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT,
                varintField(1, 777)
            )
        )
        assertNull(bridge.state.value.subModes)
        assertNull(bridge.state.value.captureSecondsLeft)
    }
}
