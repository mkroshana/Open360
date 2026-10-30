package com.open360.core.watch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.open360.core.peer.TrackHandover
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves the GPX and FIT files the watch app hands over after each recording (TrackHandover) in
 * Download/Open360, beside the phone's own, so the Footage tab finds a clip's route by its name.
 * Google Play services starts this service when they arrive, with the app open or not. Files sent
 * again for the same recording, renamed after the video, replace the ones saved before. Once both
 * are saved the item is deleted, which frees it on the watch.
 */
class WatchTrackReceiver : WearableListenerService() {

    private companion object {
        const val TAG = "WatchTrackReceiver"
        const val FOLDER = "Open360"
        const val PREFS = "open360_watch_tracks"
        const val ASSET_TIMEOUT_S = 30L
        // Where each recording's files were saved is remembered this long, for replacing them
        const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000
    }

    // Called on a background thread, so the asset reads below may wait
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }
            .map { it.dataItem.freeze() }
            .filter { it.uri.path?.startsWith(TrackHandover.PATH_PREFIX + "/") == true }
        val dataClient = Wearable.getDataClient(this)
        for (item in items) {
            val map = DataMapItem.fromDataItem(item).dataMap
            val recordingId = map.getLong(TrackHandover.KEY_RECORDING_ID)
            val gpxName = map.getString(TrackHandover.KEY_GPX_NAME) ?: continue
            val gpx = map.getAsset(TrackHandover.KEY_GPX) ?: continue
            try {
                save(recordingId, "gpx", gpxName, "application/gpx+xml", read(gpx))
                val fitName = map.getString(TrackHandover.KEY_FIT_NAME)
                val fit = map.getAsset(TrackHandover.KEY_FIT)
                // A generic type, so the .fit extension is kept as given
                if (fitName != null && fit != null) save(recordingId, "fit", fitName, "application/octet-stream", read(fit))
                Log.i(TAG, "Saved the watch's $gpxName and ${fitName ?: "no FIT"} in Download/$FOLDER")
                dataClient.deleteDataItems(item.uri)
            } catch (e: Exception) {
                // The item stays, so it is offered again when it next changes
                Log.e(TAG, "Could not save the watch's $gpxName", e)
            }
        }
        forgetOldRecordings()
    }

    private fun read(asset: Asset): ByteArray {
        val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset), ASSET_TIMEOUT_S, TimeUnit.SECONDS)
        return response.inputStream.use { it.readBytes() }
    }

    /** Saves [bytes] as [name], over the file saved for this recording's [kind] before, if any. */
    private fun save(recordingId: Long, kind: String, name: String, mimeType: String, bytes: ByteArray) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$recordingId.$kind"
        val saved = prefs.getString(key, null)
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(saved?.let(Uri::parse), name, mimeType, bytes).toString()
        } else {
            // Before Android 10 the shared Download folder needs a storage permission
            val dir = File(getExternalFilesDir(null), "tracks").apply { mkdirs() }
            val target = File(dir, name)
            saved?.let(::File)?.takeIf { it != target }?.delete()
            target.writeBytes(bytes)
            target.absolutePath
        }
        prefs.edit().putString(key, location).apply()
    }

    private fun saveToDownloads(saved: Uri?, name: String, mimeType: String, bytes: ByteArray): Uri {
        val resolver = contentResolver
        // The earlier copy, renamed, unless it has been deleted since or is no longer this
        // install's to change
        val existing = saved?.takeIf {
            try {
                resolver.update(it, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name) }, null, null) > 0
            } catch (_: SecurityException) {
                false
            }
        }
        val target = existing ?: resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
        ) ?: throw IllegalStateException("MediaStore refused $name")
        resolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open $name")
        return target
    }

    private fun forgetOldRecordings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cutoff = System.currentTimeMillis() - REMEMBER_MS
        val old = prefs.all.keys.filter { (it.substringBefore('.').toLongOrNull() ?: 0L) < cutoff }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach(::remove) }.apply()
    }
}
