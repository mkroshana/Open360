package com.open360.core.peer

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus

/**
 * Which of the two apps holds the camera link when both are open. The camera is taken to hold one
 * link, so the app without it controls the camera through the one that has it, and does not look
 * for the camera while the other has it.
 */
object LinkSharing {

    /**
     * Whether the camera is controlled through the other app: it holds the link, or is opening one,
     * and this app does not. Not while this app is taking the link over from it ([takingLink]).
     */
    fun isLinkedThrough(own: BleCameraState, peer: PeerSnapshot?, takingLink: Boolean): Boolean =
        peer != null && !takingLink && peer.holdsLink && !own.holdsLink

    /**
     * Whether this app should let go of its link, or stop looking for the camera, as the other app
     * holds it too, which happens when both are opened at about the same time. The one further
     * along keeps it, and of two equally far along the phone. A recording keeps its link: a link
     * opened again would join it late and send its GPS from the start a second time.
     */
    fun shouldLetGo(own: BleCameraState, peer: PeerSnapshot?, self: PeerDevice, takingLink: Boolean): Boolean {
        if (peer == null || takingLink || !own.holdsLink || !peer.holdsLink) return false
        if (own.isRecording) return false
        if (peer.camera.isRecording) return true
        val ownOnlyLooking = own.isOnlyLooking
        val peerOnlyLooking = peer.camera.isOnlyLooking
        if (ownOnlyLooking != peerOnlyLooking) return ownOnlyLooking
        return self == PeerDevice.WATCH
    }

    // Scanning, with no link yet and none being opened again
    private val BleCameraState.isOnlyLooking: Boolean
        get() = connectionStatus == BleConnectionStatus.SCANNING && !isWaking && !isReconnecting
}
