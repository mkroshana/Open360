package com.open360.core.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.open360.core.ble.SharedConnectionSettings

/**
 * Puts the phone's connection settings where the watch app reads them (SharedConnectionSettings).
 * The Data Layer keeps the latest copy on both devices and passes on only a change, so this can
 * be called whenever either setting may have changed.
 */
class WatchSettingsPublisher(context: Context) {

    private companion object {
        const val TAG = "WatchSettingsPublisher"
    }

    private val dataClient = Wearable.getDataClient(context)

    fun publish(wakeUpId: String?, serialSuffix: String, speedInMph: Boolean? = null) {
        val request = PutDataMapRequest.create(SharedConnectionSettings.PATH).apply {
            dataMap.putString(SharedConnectionSettings.KEY_WAKE_UP_ID, wakeUpId.orEmpty())
            dataMap.putString(SharedConnectionSettings.KEY_SERIAL_SUFFIX, serialSuffix)
            if (speedInMph != null) {
                dataMap.putBoolean(SharedConnectionSettings.KEY_SPEED_IN_MPH, speedInMph)
            }
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
            .addOnSuccessListener { Log.i(TAG, "Connection settings handed to the watch (wake-up ID ${if (wakeUpId != null) "set" else "none"}, camera $serialSuffix)") }
            // As on a phone with no watch paired
            .addOnFailureListener { Log.i(TAG, "Connection settings not handed to a watch: ${it.message}") }
    }
}
