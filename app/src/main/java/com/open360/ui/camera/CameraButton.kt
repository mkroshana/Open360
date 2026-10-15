package com.open360.ui.camera

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus

/**
 * The camera card's one button, which does what the link calls for next: Connect when there is
 * none (switching the camera on if it is off, once Wake Up is set up), Stop while the camera is
 * being looked for, linked to or switched on, Wake Up when the camera is linked but off, and
 * Disconnect when it is on.
 */
enum class CameraButton(val label: String) {
    CONNECT("Connect"),
    STOP("Stop"),
    WAKE_UP("Wake Up"),
    DISCONNECT("Disconnect");

    companion object {
        /** The button for [ble]; [isConnected] also counts a camera linked as a GPS Remote. */
        fun of(ble: BleCameraState, isConnected: Boolean): CameraButton = when {
            ble.isWaking || ble.isReconnecting -> STOP
            ble.connectionStatus == BleConnectionStatus.SCANNING ||
                ble.connectionStatus == BleConnectionStatus.CONNECTING -> STOP
            ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake -> WAKE_UP
            isConnected -> DISCONNECT
            else -> CONNECT
        }
    }
}
