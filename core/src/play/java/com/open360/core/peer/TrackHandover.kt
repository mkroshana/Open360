package com.open360.core.peer

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.open360.core.gps.SavedTrack

/**
 * Hands the watch's GPX and FIT files to the phone after each recording, over the Wear OS Data
 * Layer, which keeps them on the watch until the phone is in reach. The phone app saves them in
 * its Download/Open360 (WatchTrackReceiver) and then deletes the item, which frees it on the
 * watch too. Each recording has an item of its own, so files renamed after the video replace the
 * copies sent before.
 */
object TrackHandover {
    private const val TAG = "TrackHandover"

    const val PATH_PREFIX = "/open360/track"
    const val KEY_RECORDING_ID = "recording_id"
    const val KEY_GPX_NAME = "gpx_name"
    const val KEY_GPX = "gpx"
    const val KEY_FIT_NAME = "fit_name"
    const val KEY_FIT = "fit"

    fun send(context: Context, track: SavedTrack) {
        val request = PutDataMapRequest.create("$PATH_PREFIX/${track.recordingId}").apply {
            dataMap.putLong(KEY_RECORDING_ID, track.recordingId)
            dataMap.putString(KEY_GPX_NAME, track.gpxName)
            dataMap.putAsset(KEY_GPX, Asset.createFromBytes(track.gpx))
            if (track.fitName != null && track.fit != null) {
                dataMap.putString(KEY_FIT_NAME, track.fitName)
                dataMap.putAsset(KEY_FIT, Asset.createFromBytes(track.fit))
            }
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { Log.i(TAG, "${track.gpxName} and ${track.fitName ?: "no FIT"} handed to the phone") }
            .addOnFailureListener { Log.w(TAG, "Could not hand ${track.gpxName} to the phone: ${it.message}") }
    }
}
