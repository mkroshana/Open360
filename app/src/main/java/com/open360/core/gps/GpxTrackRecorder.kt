package com.open360.core.gps

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GpxTrackState(
    val fileName: String? = null,
    val folder: String? = null,
    val pointCount: Int = 0,
    val lastError: String? = null
)

/**
 * Logs GPS fixes to a GPX file that Insta360 Studio can import, as a fallback for when the
 * camera does not store the GPS itself.
 *
 * The file is created on the first fix and the closing tags are rewritten after every point,
 * so it stays a valid GPX file even if the app is killed mid-track.
 */
class GpxTrackRecorder(private val context: Context) {

    companion object {
        private const val TAG = "GpxTrackRecorder"
        private const val FOLDER = "Open360"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(GpxTrackState())
    val state: StateFlow<GpxTrackState> = _state.asStateFlow()

    private var descriptor: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var footerPosition = 0L
    private var lastTimestampMs = 0L
    private var failed = false

    /**
     * Appends a fix. Fixes that are not newer than the last one written are skipped.
     */
    fun addPoint(latitude: Double, longitude: Double, altitudeMeters: Double, timestampMs: Long) {
        scope.launch {
            if (failed || timestampMs <= lastTimestampMs) return@launch
            try {
                val out = channel ?: open(timestampMs)
                out.position(footerPosition)
                out.write(bytes(GpxWriter.trackPoint(latitude, longitude, altitudeMeters, timestampMs)))
                footerPosition = out.position()
                out.write(bytes(GpxWriter.FOOTER))
                lastTimestampMs = timestampMs
                _state.update { it.copy(pointCount = it.pointCount + 1, lastError = null) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not write GPX track", e)
                failed = true
                close()
                _state.update { it.copy(lastError = "Could not write GPX track: ${e.message}") }
            }
        }
    }

    /**
     * Closes the current file. The next fix starts a new one.
     */
    fun stop() {
        scope.launch {
            close()
            failed = false
            lastTimestampMs = 0L
        }
    }

    private fun open(timestampMs: Long): FileChannel {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
        val name = "Open360_$stamp.gpx"
        val folder: String
        val out: FileChannel

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore refused the new file")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Could not open $name")
            descriptor = pfd
            out = FileOutputStream(pfd.fileDescriptor).channel
            folder = "Download/$FOLDER"
        } else {
            // Before Android 10 the shared Download folder needs a storage permission.
            val dir = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
            out = RandomAccessFile(File(dir, name), "rw").channel
            folder = dir.absolutePath
        }

        channel = out
        out.write(bytes(GpxWriter.header("Open360 $stamp")))
        footerPosition = out.position()
        _state.value = GpxTrackState(fileName = name, folder = folder)
        Log.i(TAG, "GPX track started: $folder/$name")
        return out
    }

    private fun close() {
        try {
            channel?.close()
            descriptor?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GPX track: ${e.message}")
        }
        channel = null
        descriptor = null
    }

    private fun bytes(text: String): ByteBuffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
}
