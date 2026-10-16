package com.open360.core.gps

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.TimeZone
import kotlin.math.abs

data class GpxTrackState(
    val fileName: String? = null,
    val folder: String? = null,
    val pointCount: Int = 0,
    val isRecording: Boolean = false,
    val lastError: String? = null
)

/**
 * Saves the GPS fixes of each recording to its own GPX file on the phone.
 *
 * A track covers one recording: it takes the fixes measured between the first video frame and
 * the stop, and nothing outside them. While the recording runs the file has a name made from the
 * start time and is kept valid after every point, so it survives the app being killed. When the
 * recording ends the file is rewritten with the video's file name and its start and end times,
 * and renamed to match the video.
 */
class GpxTrackRecorder(private val context: Context) {

    companion object {
        private const val TAG = "GpxTrackRecorder"
        private const val FOLDER = "Open360"

        // A fix reaches the app a little after it was taken, so one from just before the stop
        // can still arrive after it. The camera's reply with the video file name also comes
        // about a second after the stop.
        private const val FINALIZE_AFTER_STOP_MS = 2500L

        // A first-frame time this close to the current one is a correction, not a new recording
        private const val SAME_RECORDING_MS = 500L
    }

    private class Session(var startElapsedMs: Long) {
        var stopElapsedMs = Long.MAX_VALUE
        var startWallMs = System.currentTimeMillis()
        var videoFileName: String? = null
        val fixes = ArrayList<GpsFix>()
        var uri: Uri? = null
        var file: File? = null
        var descriptor: ParcelFileDescriptor? = null
        var channel: FileChannel? = null
        var footerPosition = 0L
        var fileName: String? = null
        var folder: String? = null
        var finalized = false

        // The FIT copy of the track, written whole once the recording has ended
        var fitUri: Uri? = null
        var fitFile: File? = null
        var fitName: String? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(GpxTrackState())
    val state: StateFlow<GpxTrackState> = _state.asStateFlow()

    private var session: Session? = null

    /**
     * A recording has started, with its first video frame at [firstFrameElapsedMs] on the
     * monotonic clock. Called again with a slightly different time, it corrects the start.
     */
    fun start(firstFrameElapsedMs: Long) {
        scope.launch {
            val current = session
            if (current != null && !current.finalized && current.stopElapsedMs == Long.MAX_VALUE &&
                abs(firstFrameElapsedMs - current.startElapsedMs) <= SAME_RECORDING_MS
            ) {
                if (current.fixes.isEmpty()) current.startElapsedMs = firstFrameElapsedMs
                return@launch
            }
            if (current != null && !current.finalized) finalize(current)
            session = Session(firstFrameElapsedMs)
            _state.value = GpxTrackState(isRecording = true)
        }
    }

    /**
     * Offers a fix to the current track. It is kept only if it was taken while recording.
     */
    fun addFix(fix: GpsFix) {
        scope.launch {
            val s = session ?: return@launch
            if (s.finalized || fix.elapsedMs < s.startElapsedMs || fix.elapsedMs > s.stopElapsedMs) return@launch
            if (s.fixes.isNotEmpty() && fix.elapsedMs <= s.fixes.last().elapsedMs) return@launch
            try {
                val out = s.channel ?: open(s)
                out.position(s.footerPosition)
                out.write(bytes(GpxWriter.trackPoint(fix.latitude, fix.longitude, fix.altitudeMeters, fix.utcMs)))
                s.footerPosition = out.position()
                out.write(bytes(GpxWriter.FOOTER))
                s.fixes.add(fix)
                _state.update { it.copy(pointCount = s.fixes.size, lastError = null) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not write GPX track", e)
                _state.update { it.copy(lastError = "Could not save the route file: ${e.message}") }
            }
        }
    }

    /**
     * The recording stopped at [stopElapsedMs]. The file is completed shortly afterwards.
     */
    fun stop(stopElapsedMs: Long) {
        scope.launch {
            val s = session ?: return@launch
            if (s.finalized || s.stopElapsedMs != Long.MAX_VALUE) return@launch
            s.stopElapsedMs = stopElapsedMs
            _state.update { it.copy(isRecording = false) }
            delay(FINALIZE_AFTER_STOP_MS)
            if (!s.finalized) finalize(s)
        }
    }

    /**
     * Names the track of the recording that is stopping, or has just stopped, after its video.
     */
    fun setVideoFileName(videoFileName: String) {
        scope.launch {
            val s = session ?: return@launch
            if (s.videoFileName == videoFileName) return@launch
            s.videoFileName = videoFileName
            if (s.finalized && s.fixes.isNotEmpty()) {
                // The name arrived after the file was completed
                try {
                    rewrite(s)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not rename GPX track", e)
                } finally {
                    closeFile(s)
                }
            }
        }
    }

    private fun closeFile(s: Session) {
        try {
            s.channel?.close()
            s.descriptor?.close()
        } catch (_: Exception) {}
        s.channel = null
        s.descriptor = null
    }

    private fun open(s: Session): FileChannel {
        val name = "Open360_${stamp(s.startWallMs)}.gpx"
        create(s, name)
        val out = s.channel!!
        out.write(bytes(GpxWriter.header("Open360 ${stamp(s.startWallMs)}")))
        s.footerPosition = out.position()
        _state.update { it.copy(fileName = s.fileName, folder = s.folder) }
        Log.i(TAG, "GPX track started: ${s.folder}/${s.fileName}")
        return out
    }

    private fun create(s: Session, name: String) {
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
            s.uri = uri
            s.descriptor = pfd
            s.channel = FileOutputStream(pfd.fileDescriptor).channel
            s.folder = "Download/$FOLDER"
        } else {
            // Before Android 10 the shared Download folder needs a storage permission.
            val dir = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
            val file = File(dir, name)
            s.file = file
            s.channel = RandomAccessFile(file, "rw").channel
            s.folder = dir.absolutePath
        }
        s.fileName = name
    }

    /** Completes the file: final name, video name and times inside, then closes it. */
    private fun finalize(s: Session) {
        s.finalized = true
        if (s.fixes.isEmpty()) {
            Log.i(TAG, "Recording ended with no GPS fixes, no track saved")
            _state.value = GpxTrackState(lastError = "No GPS during the last recording, so no route was saved")
            return
        }
        try {
            rewrite(s)
        } catch (e: Exception) {
            Log.e(TAG, "Could not complete GPX track", e)
            _state.update { it.copy(lastError = "Could not finish the route file: ${e.message}") }
        } finally {
            closeFile(s)
        }
    }

    /** Writes the whole file again with what is now known, and renames it after the video. */
    private fun rewrite(s: Session) {
        val video = s.videoFileName
        val first = s.fixes.first()
        val last = s.fixes.last()
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val description = "GPS for ${video ?: "a recording"}, ${local.format(Date(first.utcMs))} to ${local.format(Date(last.utcMs))} local time"

        val content = StringBuilder(GpxWriter.header(video ?: "Open360 ${stamp(s.startWallMs)}", description, first.utcMs))
        for (fix in s.fixes) {
            content.append(GpxWriter.trackPoint(fix.latitude, fix.longitude, fix.altitudeMeters, fix.utcMs))
        }
        content.append(GpxWriter.FOOTER)

        val out = s.channel ?: reopen(s)
        out.truncate(0)
        out.position(0)
        out.write(bytes(content.toString()))
        out.force(true)

        val finalName = if (video != null) video.substringBeforeLast('.') + ".gpx" else s.fileName ?: return
        if (finalName != s.fileName) {
            rename(s, finalName)
        }
        _state.update { it.copy(fileName = s.fileName, folder = s.folder, pointCount = s.fixes.size, isRecording = false) }
        Log.i(TAG, "GPX track saved: ${s.folder}/${s.fileName} (${s.fixes.size} points)")
        saveFit(s)
    }

    /**
     * Writes the track again as a FIT file named like the GPX, for importing into Insta360
     * Studio, which reads more from FIT than from GPX. A failure here leaves the GPX as it is.
     */
    private fun saveFit(s: Session) {
        val name = (s.fileName ?: return).substringBeforeLast('.') + ".fit"
        try {
            val content = FitWriter.activity(s.fixes, TimeZone.getDefault().getOffset(s.fixes.first().utcMs))
            val uri = s.fitUri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val target = uri ?: resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        // A generic type, so the .fit extension is kept as given
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                    }
                ) ?: throw IllegalStateException("MediaStore refused $name")
                if (uri != null && s.fitName != name) {
                    resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name) }, null, null)
                }
                resolver.openOutputStream(target, "wt")?.use { it.write(content) }
                    ?: throw IllegalStateException("Could not open $name")
                s.fitUri = target
            } else {
                val dir = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
                val target = File(dir, name)
                s.fitFile?.takeIf { it != target }?.delete()
                target.writeBytes(content)
                s.fitFile = target
            }
            s.fitName = name
            Log.i(TAG, "FIT track saved: ${s.folder}/$name (${content.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not save FIT track", e)
        }
    }

    private fun reopen(s: Session): FileChannel {
        val uri = s.uri
        val out = if (uri != null) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Could not reopen ${s.fileName}")
            s.descriptor = pfd
            FileOutputStream(pfd.fileDescriptor).channel
        } else {
            RandomAccessFile(s.file ?: throw IllegalStateException("No track file"), "rw").channel
        }
        s.channel = out
        return out
    }

    private fun rename(s: Session, newName: String) {
        val uri = s.uri
        if (uri != null) {
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, newName) }
            context.contentResolver.update(uri, values, null, null)
            s.fileName = newName
        } else {
            val file = s.file ?: return
            val target = File(file.parentFile, newName)
            if (file.renameTo(target)) {
                s.file = target
                s.fileName = newName
            }
        }
    }

    private fun stamp(wallMs: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(wallMs))

    private fun bytes(text: String): ByteBuffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
}
