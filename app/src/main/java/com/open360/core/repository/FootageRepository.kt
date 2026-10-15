package com.open360.core.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.gps.GpxParser
import com.open360.core.gps.GpxTrackSummary
import com.open360.core.model.CameraMediaFile
import com.open360.core.network.CameraWifiConnector
import com.open360.core.socket.CameraSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class FootageRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "FootageRepository"
        private const val FOLDER = "Open360"
        private const val BUFFER_SIZE = 64 * 1024
        private const val CAMERA_BASE_URL = "http://${CameraWifiConnector.DEFAULT_CAMERA_IP}"

        // Files asked for at a time. A GO 3S sent all 74 of its files in one reply.
        private const val FILE_LIST_PAGE = 500
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Lists camera files available over Wi-Fi, enriched with whether each file is already
     * downloaded locally and if a companion GPX track exists.
     */
    suspend fun fetchCameraFiles(): Result<List<CameraMediaFile>> = withContext(Dispatchers.IO) {
        val remoteList = try {
            CameraFileList.toMediaFiles(listCameraPaths(), CAMERA_BASE_URL)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Could not list the camera's files", e)
            return@withContext Result.failure(e)
        }
        val localFiles = getLocalFiles()
        val localMap = localFiles.associateBy { it.baseNameWithoutExtension }

        val enriched = remoteList.map { remote ->
            val match = localMap[remote.baseNameWithoutExtension]
            if (match != null) {
                remote.copy(
                    isImported = true,
                    localPath = match.localPath,
                    contentUri = match.contentUri,
                    hasCompanionGpx = match.hasCompanionGpx,
                    gpxSummary = match.gpxSummary
                )
            } else {
                val gpx = getCompanionGpxSummary(remote.baseNameWithoutExtension)
                remote.copy(
                    hasCompanionGpx = gpx != null,
                    gpxSummary = gpx
                )
            }
        }
        Result.success(enriched)
    }

    /**
     * Asks the camera for the path of every file on its storage, over its own protocol on
     * Wi-Fi port 6666 (GetFileList, command 13). The OSC camera.listFiles command is refused by
     * a GO 3S with disabledCommand, and its folder listing over HTTP with 403.
     */
    private suspend fun listCameraPaths(): List<String> = coroutineScope {
        val client = CameraSocketClient()
        try {
            client.connect(this)
            val paths = mutableListOf<String>()
            while (true) {
                val reply = client.request(
                    Go2BleProtocol.CMD_GET_FILE_LIST,
                    Go2BleProtocol.buildFileListRequest(paths.size, FILE_LIST_PAGE)
                ) ?: throw IOException("The camera did not answer the file list request")
                if (reply.messageCode != 200) {
                    throw IOException("The camera refused the file list request with status ${reply.messageCode}")
                }
                val page = Go2BleProtocol.parseFileListReply(reply.body)
                paths += page.paths
                if (page.paths.isEmpty() || paths.size >= page.totalCount) break
            }
            Log.i(TAG, "The camera listed ${paths.size} files")
            paths
        } finally {
            client.disconnect()
        }
    }

    /**
     * Scans local storage (Download/Open360 and app directory) for downloaded videos, photos,
     * and their companion GPX files.
     */
    suspend fun getLocalFiles(): List<CameraMediaFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<CameraMediaFile>()
        val gpxMap = mutableMapOf<String, Uri>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$FOLDER%")

            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)
                        val dateSec = cursor.getLong(dateCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

                        if (name.endsWith(".gpx", ignoreCase = true)) {
                            gpxMap[name.substringBeforeLast(".")] = uri
                        } else if (isMediaFile(name)) {
                            files.add(
                                CameraMediaFile(
                                    name = name,
                                    fileUrl = uri.toString(),
                                    contentUri = uri.toString(),
                                    sizeBytes = size,
                                    dateTime = dateSec.toString(),
                                    isVideo = isVideoFile(name),
                                    isImported = true
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore for local files", e)
            }

            // Also scan MediaStore.Video for clips downloaded by Insta360 or Open360
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED
            )
            val videoSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val videoSelectionArgs = arrayOf("%$FOLDER%", "%Insta360Download%")

            try {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    videoSelection,
                    videoSelectionArgs,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)
                        val dateSec = cursor.getLong(dateCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        val base = name.substringBeforeLast(".")
                        if (files.none { it.baseNameWithoutExtension == base }) {
                            files.add(
                                CameraMediaFile(
                                    name = name,
                                    fileUrl = uri.toString(),
                                    contentUri = uri.toString(),
                                    sizeBytes = size,
                                    dateTime = dateSec.toString(),
                                    isVideo = true,
                                    isImported = true
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore Video for local files", e)
            }
        }

        // Also check direct filesystem in Download/Open360, DCIM/Insta360Download, and external files directory
        val dirsToCheck = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Insta360Download"),
            File(context.getExternalFilesDir(null), "tracks")
        )

        for (dir in dirsToCheck) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.endsWith(".gpx", ignoreCase = true)) {
                        val base = name.substringBeforeLast(".")
                        if (!gpxMap.containsKey(base)) {
                            gpxMap[base] = Uri.fromFile(file)
                        }
                    } else if (isMediaFile(name)) {
                        val base = name.substringBeforeLast(".")
                        if (files.none { it.baseNameWithoutExtension == base }) {
                            files.add(
                                CameraMediaFile(
                                    name = name,
                                    fileUrl = Uri.fromFile(file).toString(),
                                    localPath = file.absolutePath,
                                    contentUri = Uri.fromFile(file).toString(),
                                    sizeBytes = file.length(),
                                    dateTime = (file.lastModified() / 1000).toString(),
                                    isVideo = isVideoFile(name),
                                    isImported = true
                                )
                            )
                        }
                    }
                }
            }
        }

        // Associate GPX summaries
        files.map { media ->
            val base = media.baseNameWithoutExtension
            val gpxUri = gpxMap[base]
            if (gpxUri != null) {
                val summary = readGpxSummary(gpxUri)
                media.copy(hasCompanionGpx = true, gpxSummary = summary)
            } else {
                media
            }
        }
    }

    /**
     * Scans an external directory or USB OTG drive granted via Storage Access Framework (SAF).
     */
    suspend fun getUsbFiles(treeUri: Uri): List<CameraMediaFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val mediaFiles = mutableListOf<CameraMediaFile>()
        val gpxMap = mutableMapOf<String, Uri>()

        // Look in Camera01 subfolder if present, or search root
        val targetFolder = findDcimCameraFolder(root) ?: root

        for (doc in targetFolder.listFiles()) {
            val name = doc.name ?: continue
            if (name.endsWith(".gpx", ignoreCase = true)) {
                gpxMap[name.substringBeforeLast(".")] = doc.uri
            } else if (isMediaFile(name)) {
                mediaFiles.add(
                    CameraMediaFile(
                        name = name,
                        fileUrl = doc.uri.toString(),
                        contentUri = doc.uri.toString(),
                        sizeBytes = doc.length(),
                        isVideo = isVideoFile(name),
                        isImported = false
                    )
                )
            }
        }

        mediaFiles.map { media ->
            val base = media.baseNameWithoutExtension
            val gpxUri = gpxMap[base] ?: findLocalGpxUri(base)
            if (gpxUri != null) {
                val summary = readGpxSummary(gpxUri)
                media.copy(hasCompanionGpx = true, gpxSummary = summary)
            } else {
                media
            }
        }
    }

    /**
     * Downloads a file from the camera's HTTP server over Wi-Fi directly into Download/Open360/.
     */
    suspend fun downloadCameraFile(
        file: CameraMediaFile,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(file.fileUrl)
                .header("User-Agent", "Open360/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code} downloading ${file.name}"))
                }
                val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
                val totalBytes = if (body.contentLength() > 0) body.contentLength() else file.sizeBytes

                val destinationUri = createDestinationUri(file.name, file.isVideo)
                val out = context.contentResolver.openOutputStream(destinationUri)
                    ?: return@withContext Result.failure(Exception("Could not open output stream for ${file.name}"))

                out.use { outputStream ->
                    body.byteStream().use { inputStream ->
                        copyStreamWithProgress(inputStream, outputStream, totalBytes, onProgress)
                    }
                }

                finalizeDownloadUri(destinationUri)
                Result.success(destinationUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${file.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Copies a file from USB OTG (SAF DocumentFile) into local Download/Open360/.
     */
    suspend fun importFromUsb(
        sourceUri: Uri,
        name: String,
        sizeBytes: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val destinationUri = createDestinationUri(name, isVideoFile(name))
            val inStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext Result.failure(Exception("Could not open source stream"))
            val outStream = context.contentResolver.openOutputStream(destinationUri)
                ?: return@withContext Result.failure(Exception("Could not open destination stream"))

            inStream.use { input ->
                outStream.use { output ->
                    copyStreamWithProgress(input, output, sizeBytes, onProgress)
                }
            }

            finalizeDownloadUri(destinationUri)
            Result.success(destinationUri)
        } catch (e: Exception) {
            Log.e(TAG, "USB Import failed for $name", e)
            Result.failure(e)
        }
    }

    suspend fun deleteLocalFile(file: CameraMediaFile): Boolean = withContext(Dispatchers.IO) {
        try {
            if (file.contentUri != null) {
                val uri = Uri.parse(file.contentUri)
                if (uri.scheme == "content") {
                    return@withContext context.contentResolver.delete(uri, null, null) > 0
                }
            }
            if (file.localPath != null) {
                return@withContext File(file.localPath).delete()
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file ${file.name}", e)
            false
        }
    }

    private fun findDcimCameraFolder(root: DocumentFile): DocumentFile? {
        val dcim = root.findFile("DCIM") ?: root
        return dcim.findFile("Camera01") ?: dcim
    }

    private fun createDestinationUri(name: String, isVideo: Boolean): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore refused to create file $name")
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply {
                mkdirs()
            }
            val target = File(dir, name)
            return Uri.fromFile(target)
        }
    }

    private fun finalizeDownloadUri(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesReadTotal = 0L
        var read: Int
        var lastReported = 0L

        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            bytesReadTotal += read

            // Throttle progress updates to avoid flooding UI threads
            if (bytesReadTotal - lastReported >= 256 * 1024 || bytesReadTotal == totalBytes) {
                lastReported = bytesReadTotal
                onProgress(bytesReadTotal, totalBytes)
            }
        }
        output.flush()
    }

    private fun readGpxSummary(uri: Uri): GpxTrackSummary? {
        return try {
            val stream = if (uri.scheme == "file") {
                FileInputStream(File(uri.path ?: return null))
            } else {
                context.contentResolver.openInputStream(uri) ?: return null
            }
            stream.use { GpxParser.parse(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPX at $uri", e)
            null
        }
    }

    private fun findLocalGpxUri(baseName: String): Uri? {
        val gpxName = "$baseName.gpx"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(gpxName, "%$FOLDER%")
            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    }
                }
            } catch (_: Exception) {}
        }

        val directFile = File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER),
            gpxName
        )
        if (directFile.exists()) return Uri.fromFile(directFile)

        val appDirFile = File(File(context.getExternalFilesDir(null), "tracks"), gpxName)
        if (appDirFile.exists()) return Uri.fromFile(appDirFile)

        return null
    }

    private fun getCompanionGpxSummary(baseName: String): GpxTrackSummary? {
        val uri = findLocalGpxUri(baseName) ?: return null
        return readGpxSummary(uri)
    }

    private fun isMediaFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".insv") ||
                lower.endsWith(".lrv") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg")
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".insv") || lower.endsWith(".lrv")
    }
}
