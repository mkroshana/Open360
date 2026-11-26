package com.open360.wear

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.open360.core.ble.SharedConnectionSettings

/**
 * Reads the connection settings the phone app hands over (SharedConnectionSettings): the wake-up
 * ID, the camera's serial suffix, and the speed unit, null where the phone has none. The Data Layer
 * keeps the latest copy on the watch, so they are there with the phone out of reach once they have arrived.
 */
class PhoneSettingsReceiver(
    context: Context,
    private val onSettings: (wakeUpId: String?, serialSuffix: String?, speedInMph: Boolean?) -> Unit
) : DataClient.OnDataChangedListener {

    private companion object {
        const val TAG = "PhoneSettingsReceiver"
    }

    private val dataClient = Wearable.getDataClient(context)
    private val uri = "wear://*${SharedConnectionSettings.PATH}".toUri()

    /** Reads the settings held on the watch, then follows any change while the app is open. */
    fun start() {
        dataClient.addListener(this, uri, DataClient.FILTER_LITERAL)
        dataClient.getDataItems(uri)
            .addOnSuccessListener { items ->
                if (items.count == 0) Log.i(TAG, "No connection settings from the phone yet")
                items.forEach { read(it) }
                items.release()
            }
            .addOnFailureListener { Log.w(TAG, "Could not read the phone's connection settings: ${it.message}") }
    }

    fun stop() {
        dataClient.removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) read(it.dataItem) }
    }

    private fun read(item: DataItem) {
        if (item.uri.path != SharedConnectionSettings.PATH) return
        val map = DataMapItem.fromDataItem(item).dataMap
        val wakeUpId = map.getString(SharedConnectionSettings.KEY_WAKE_UP_ID)?.takeIf { it.isNotEmpty() }
        val serialSuffix = map.getString(SharedConnectionSettings.KEY_SERIAL_SUFFIX)?.takeIf { it.isNotEmpty() }
        val speedInMph = if (map.containsKey(SharedConnectionSettings.KEY_SPEED_IN_MPH)) {
            map.getBoolean(SharedConnectionSettings.KEY_SPEED_IN_MPH)
        } else {
            null
        }
        Log.i(TAG, "Connection settings from the phone: wake-up ID ${if (wakeUpId != null) "set" else "none"}, camera $serialSuffix")
        onSettings(wakeUpId, serialSuffix, speedInMph)
    }
}
