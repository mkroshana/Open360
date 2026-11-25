package com.open360.core.peer

import android.content.Context

/**
 * No-op FOSS build of [PeerLink]: Wear OS Data Layer communication is omitted when building
 * without Google Play services.
 */
@Suppress("unused", "UNUSED_PARAMETER")
class PeerLink(
    context: Context,
    private val onSnapshot: (PeerSnapshot) -> Unit,
    private val onCommand: (PeerCommand) -> Unit
) {
    fun start(onReady: (hasDevice: Boolean) -> Unit) {
        onReady(false)
    }

    fun stop() = Unit

    fun sendSnapshot(bytes: ByteArray) = Unit

    fun sendCommand(command: PeerCommand) = Unit
}
