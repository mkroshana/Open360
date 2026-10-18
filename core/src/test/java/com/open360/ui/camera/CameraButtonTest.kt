package com.open360.ui.camera

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraButtonTest {

    private fun linked(awake: Boolean) =
        BleCameraState(connectionStatus = BleConnectionStatus.CONNECTED, isCameraAwake = awake)

    @Test
    fun withNoLinkItConnects() {
        assertEquals(CameraButton.CONNECT, CameraButton.of(BleCameraState(), isConnected = false))
    }

    @Test
    fun whileLookingOrLinkingItStops() {
        listOf(BleConnectionStatus.SCANNING, BleConnectionStatus.CONNECTING).forEach {
            assertEquals(it.name, CameraButton.STOP, CameraButton.of(BleCameraState(connectionStatus = it), isConnected = false))
        }
        val reconnecting = BleCameraState(connectionStatus = BleConnectionStatus.CONNECTING, isReconnecting = true)
        assertEquals(CameraButton.STOP, CameraButton.of(reconnecting, isConnected = false))
    }

    @Test
    fun aCameraThatIsOffIsWokenAndCanBeStoppedWhileItSwitchesOn() {
        assertEquals(CameraButton.WAKE_UP, CameraButton.of(linked(awake = false), isConnected = true))
        // The link is up but the camera is not answering yet: Wake Up again would start over
        assertEquals(CameraButton.STOP, CameraButton.of(linked(awake = false).copy(isWaking = true), isConnected = true))
        // While the fresh link for the wake-up is being opened
        val opening = BleCameraState(connectionStatus = BleConnectionStatus.SCANNING, isWaking = true)
        assertEquals(CameraButton.STOP, CameraButton.of(opening, isConnected = false))
    }

    @Test
    fun aCameraThatIsOnIsDisconnected() {
        assertEquals(CameraButton.DISCONNECT, CameraButton.of(linked(awake = true), isConnected = true))
    }
}
