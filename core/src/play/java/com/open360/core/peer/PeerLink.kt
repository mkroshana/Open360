package com.open360.core.peer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * Carries PeerSnapshot and PeerCommand messages between the phone and watch apps over the Wear
 * OS Data Layer, which joins the two as they have the same package name and signing key.
 * Messages reach the other app only while it is running, and nothing is kept, so its snapshots
 * stop arriving once it closes or goes out of reach.
 */
class PeerLink(
    context: Context,
    private val onSnapshot: (PeerSnapshot) -> Unit,
    private val onCommand: (PeerCommand) -> Unit
) : MessageClient.OnMessageReceivedListener {

    private companion object {
        const val TAG = "PeerLink"
        const val PATH_PREFIX = "/open360/peer"
        const val PATH_SNAPSHOT = "$PATH_PREFIX/snapshot"
        const val PATH_COMMAND = "$PATH_PREFIX/command"

        // The paired devices are read again this often, and after a send fails
        const val NODES_REFRESH_MS = 30_000L
    }

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    @Volatile
    private var nodeIds: List<String> = emptyList()
    @Volatile
    private var nodesReadAtMs = 0L
    // Logged once, as on a phone with no watch paired every call fails the same way
    @Volatile
    private var unavailableLogged = false

    /**
     * Starts listening, then reads which devices are in reach and calls [onReady] with whether
     * there is one, on the main thread.
     */
    fun start(onReady: (hasDevice: Boolean) -> Unit) {
        messageClient.addListener(this, "wear://*$PATH_PREFIX".toUri(), MessageClient.FILTER_PREFIX)
            .addOnFailureListener { logUnavailable(it) }
        readNodes(onReady)
    }

    fun stop() {
        messageClient.removeListener(this)
    }

    fun sendSnapshot(bytes: ByteArray) = send(PATH_SNAPSHOT, bytes)

    fun sendCommand(command: PeerCommand) {
        Log.i(TAG, "Sending $command")
        send(PATH_COMMAND, PeerMessages.encodeCommand(command))
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_SNAPSHOT -> PeerMessages.decodeSnapshot(event.data, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                ?.let(onSnapshot)
                ?: Log.w(TAG, "Could not read a snapshot from the other app")
            PATH_COMMAND -> PeerMessages.decodeCommand(event.data)
                ?.also { Log.i(TAG, "Received $it") }
                ?.let(onCommand)
                ?: Log.w(TAG, "Could not read a command from the other app: ${event.data.toString(Charsets.UTF_8)}")
        }
    }

    private fun send(path: String, data: ByteArray) {
        if (SystemClock.elapsedRealtime() - nodesReadAtMs > NODES_REFRESH_MS) readNodes()
        // A device without the app, or with it closed, drops the message
        nodeIds.forEach { node ->
            messageClient.sendMessage(node, path, data)
                .addOnFailureListener {
                    Log.i(TAG, "Could not send to $node: ${it.message}")
                    nodesReadAtMs = 0L
                }
        }
    }

    private fun readNodes(onReady: ((Boolean) -> Unit)? = null) {
        nodesReadAtMs = SystemClock.elapsedRealtime()
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                nodeIds = nodes.map { it.id }
                onReady?.invoke(nodes.isNotEmpty())
            }
            .addOnFailureListener {
                logUnavailable(it)
                nodeIds = emptyList()
                onReady?.invoke(false)
            }
    }

    private fun logUnavailable(error: Exception) {
        if (unavailableLogged) return
        unavailableLogged = true
        // As on a phone with no watch paired
        Log.i(TAG, "The Data Layer is not available: ${error.message}")
    }
}
