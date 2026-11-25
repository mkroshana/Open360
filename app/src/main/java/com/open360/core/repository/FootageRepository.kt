package com.open360.core.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.gps.GpxParser
import com.open360.core.gps.GpxTrackSummary
import com.open360.core.model.CameraMediaFile
import com.open360.core.network.CameraWifiConnector
import com.open360.core.socket.CameraSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

        // Thumbnails: longest side in pixels, how many are kept, how many are fetched at once,
        // and how much of a photo is read for the small picture in its EXIF data
        private const val THUMBNAIL_SIZE = 256
        private const val THUMBNAILS_KEPT = 100
        private const val THUMBNAIL_FETCHES = 2
        private const val PHOTO_HEADER_BYTES = 256 * 1024
    }

    private val thumbnails = LruCache<String, Bitmap>(THUMBNAILS_KEPT)
    private val thumbnailFetches = Semaphore(THUMBNAIL_FETCHES)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Lists camera files available over Wi-Fi, enriched with whether each file is already
     * downloaded locally and if a companion GPX track exists.
     */
    suspend fun fetchCameraFiles(
        hostIp: String = CameraWifiConnector.DEFAULT_CAMERA_IP,
        network: Network? = null
    ): Result<List<CameraMediaFile>> = withContext(Dispatchers.IO) {
        val remoteList = try {
            CameraFileList.toMediaFiles(listCameraPaths(hostIp, network), "http://$hostIp")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Could not list the camera's files", e)
            return@withContext Result.failure(e)
        }
        val localFiles = getLocalFiles()
        val localMap = localFiles.associateBy { it.baseNameWithoutExtension }

        val enriched = remoteList.map { remote ->
            val base = remote.baseNameWithoutExtension
            val match = localMap[base]
            if (match != null) {
                remote.copy(
                    isImported = true,
                    localPath = match.localPath,
                    contentUri = match.contentUri,
                    hasCompanionGpx = match.hasCompanionGpx,
                    gpxSummary = match.gpxSummary,
                    companionGpxUri = match.companionGpxUri,
                    companionFitUri = match.companionFitUri
                )
            } else {
                val gpxUri = findLocalCompanionUri(base, ".gpx")
                val fitUri = findLocalCompanionUri(base, ".fit")
                val gpx = gpxUri?.let { readGpxSummary(it) }
                remote.copy(
                    hasCompanionGpx = gpxUri != null,
                    gpxSummary = gpx,
                    companionGpxUri = gpxUri?.toString(),
                    companionFitUri = fitUri?.toString()
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
    private suspend fun listCameraPaths(
        hostIp: String = CameraWifiConnector.DEFAULT_CAMERA_IP,
        network: Network? = null
    ): List<String> = coroutineScope {
        val client = CameraSocketClient(host = hostIp, network = network)
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
     * A small picture of a file on the camera: the first frame of a video's low-resolution
     * copy, or the thumbnail in a photo's EXIF data. The camera has no thumbnail command of its
     * own (a GO 3S answers GetMiniThumbnail with "unknown msg code"). Null when there is none.
     */
    suspend fun loadCameraThumbnail(file: CameraMediaFile): Bitmap? {
        thumbnails.get(file.fileUrl)?.let { return it }
        return thumbnailFetches.withPermit {
            withContext(Dispatchers.IO) {
                thumbnails.get(file.fileUrl) ?: try {
                    val bitmap = if (file.isVideo) videoFrame(file.previewUrl ?: file.fileUrl) else photoThumbnail(file.fileUrl)
                    bitmap?.also { thumbnails.put(file.fileUrl, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "No thumbnail for ${file.name}", e)
                    null
                }
            }
        }
    }

    private fun videoFrame(url: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, emptyMap())
            return extractFrame(retriever)
        } finally {
            retriever.release()
        }
    }

    /** Loads and caches a thumbnail for a downloaded or USB/SAF clip or photo. */
    suspend fun loadLocalThumbnail(file: CameraMediaFile): Bitmap? {
        val key = file.playableUri
        thumbnails.get(key)?.let { return it }
        return thumbnailFetches.withPermit {
            withContext(Dispatchers.IO) {
                thumbnails.get(key) ?: try {
                    val bitmap = if (file.isVideo) localVideoThumbnail(file) else localPhotoThumbnail(file)
                    bitmap?.also { thumbnails.put(key, it) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "No local thumbnail for ${file.name}", e)
                    null
                }
            }
        }
    }

    private fun extractFrame(retriever: MediaMetadataRetriever): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
        }
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = THUMBNAIL_SIZE.toFloat() / maxOf(frame.width, frame.height)
        return frame.scale(
            (frame.width * scale).toInt().coerceAtLeast(1),
            (frame.height * scale).toInt().coerceAtLeast(1)
        )
    }

    private fun localVideoThumbnail(file: CameraMediaFile): Bitmap? {
        val contentUri = file.contentUri?.toUri()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && contentUri?.scheme == "content") {
            try {
                return context.contentResolver.loadThumbnail(
                    contentUri,
                    Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
                    null
                )
            } catch (_: Exception) {
                // Fall back to MediaMetadataRetriever (e.g. SAF document URIs)
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            when {
                file.localPath != null -> retriever.setDataSource(file.localPath)
                contentUri != null -> retriever.setDataSource(context, contentUri)
                else -> return null
            }
            return extractFrame(retriever)
        } finally {
            retriever.release()
        }
    }

    private fun localPhotoThumbnail(file: CameraMediaFile): Bitmap? {
        val contentUri = file.contentUri?.toUri()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && contentUri?.scheme == "content") {
            try {
                return context.contentResolver.loadThumbnail(
                    contentUri,
                    Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
                    null
                )
            } catch (_: Exception) {
                // Fall back to BitmapFactory
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openLocalStream(file)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > THUMBNAIL_SIZE * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return openLocalStream(file)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun openLocalStream(file: CameraMediaFile): InputStream? {
        file.localPath?.let { path ->
            val f = File(path)
            if (f.exists()) return FileInputStream(f)
        }
        val uri = file.contentUri?.toUri() ?: return null
        return if (uri.scheme == "file") {
            uri.path?.let { FileInputStream(File(it)) }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun photoThumbnail(url: String): Bitmap? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${PHOTO_HEADER_BYTES - 1}")
            .header("User-Agent", "Open360/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val header = response.body?.byteStream()?.use { it.readNBytesCompat(PHOTO_HEADER_BYTES) } ?: return null
            return ExifInterface(ByteArrayInputStream(header)).thumbnailBitmap
        }
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read == -1) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Scans local storage (Download/Open360 on Android 10+ and app-specific downloads/tracks
     * directories) for downloaded videos, photos, and their companion GPX and FIT files.
     */
    suspend fun getLocalFiles(): List<CameraMediaFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<CameraMediaFile>()
        val gpxMap = mutableMapOf<String, Uri>()
        val fitMap = mutableMapOf<String, Uri>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
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
                        } else if (name.endsWith(".fit", ignoreCase = true)) {
                            fitMap[name.substringBeforeLast(".")] = uri
                        } else if (isMediaFile(name)) {
                            files.add(
                                CameraMediaFile(
                                    name = name,
                                    fileUrl = uri.toString(),
                                    contentUri = uri.toString(),
                                    sizeBytes = size,
                                    dateTime = CameraFileList.parseDateTimeFromName(name) ?: dateSec.toString(),
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
        }

        // Check app-specific downloads (used on API 26-28) and tracks directories without storage permissions
        val dirsToCheck = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, FOLDER) },
            context.getExternalFilesDir(null)?.let { File(it, "tracks") }
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
                    } else if (name.endsWith(".fit", ignoreCase = true)) {
                        val base = name.substringBeforeLast(".")
                        if (!fitMap.containsKey(base)) {
                            fitMap[base] = Uri.fromFile(file)
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
                                    dateTime = CameraFileList.parseDateTimeFromName(name) ?: (file.lastModified() / 1000).toString(),
                                    isVideo = isVideoFile(name),
                                    isImported = true
                                )
                            )
                        }
                    }
                }
            }
        }

        // Associate GPX summaries and companion URIs
        files.map { media ->
            val base = media.baseNameWithoutExtension
            val gpxUri = gpxMap[base]
            val fitUri = fitMap[base]
            val summary = gpxUri?.let { readGpxSummary(it) }
            media.copy(
                hasCompanionGpx = gpxUri != null,
                gpxSummary = summary,
                companionGpxUri = gpxUri?.toString(),
                companionFitUri = fitUri?.toString()
            )
        }
    }

    /**
     * Scans an external directory or USB OTG drive granted via Storage Access Framework (SAF).
     */
    suspend fun getUsbFiles(treeUri: Uri): List<CameraMediaFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val mediaDocs = mutableListOf<DocumentFile>()
        val gpxMap = mutableMapOf<String, Uri>()
        val fitMap = mutableMapOf<String, Uri>()

        // Look in Camera01 subfolder if present, or search root
        val targetFolder = findDcimCameraFolder(root) ?: root

        for (doc in targetFolder.listFiles()) {
            val name = doc.name ?: continue
            when {
                name.endsWith(".gpx", ignoreCase = true) -> gpxMap[name.substringBeforeLast(".")] = doc.uri
                name.endsWith(".fit", ignoreCase = true) -> fitMap[name.substringBeforeLast(".")] = doc.uri
                isMediaFile(name) -> mediaDocs.add(doc)
            }
        }

        // Pair .lrv previews with their full clips via CameraFileList, keeping USB URIs
        val usbByName = mediaDocs.associateBy { it.name!! }
        val paired = CameraFileList.toMediaFiles(usbByName.keys.toList(), "usb://")
        val localFiles = getLocalFiles().associateBy { it.baseNameWithoutExtension }

        paired.mapNotNull { entry ->
            val doc = usbByName[entry.name] ?: return@mapNotNull null
            val previewName = entry.previewUrl?.substringAfterLast('/')
            val previewDoc = previewName?.let { usbByName[it] }
            val base = entry.baseNameWithoutExtension
            val localMatch = localFiles[base]
            val gpxUri = gpxMap[base] ?: localMatch?.companionGpxUri?.toUri() ?: findLocalCompanionUri(base, ".gpx")
            val fitUri = fitMap[base] ?: localMatch?.companionFitUri?.toUri() ?: findLocalCompanionUri(base, ".fit")
            val summary = localMatch?.gpxSummary ?: gpxUri?.let { readGpxSummary(it) }
            val fallbackTime = (doc.lastModified() / 1000).takeIf { it > 0 }?.toString().orEmpty()
            CameraMediaFile(
                name = entry.name,
                fileUrl = doc.uri.toString(),
                contentUri = doc.uri.toString(),
                sizeBytes = doc.length(),
                dateTime = entry.dateTime.ifEmpty { fallbackTime },
                isVideo = entry.isVideo,
                isImported = localMatch != null,
                hasCompanionGpx = gpxUri != null,
                gpxSummary = summary,
                companionGpxUri = gpxUri?.toString(),
                companionFitUri = fitUri?.toString(),
                previewUrl = previewDoc?.uri?.toString()
            )
        }
    }

    /**
     * Downloads a file from the camera's HTTP server over Wi-Fi directly into Download/Open360/.
     */
    suspend fun downloadCameraFile(
        file: CameraMediaFile,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        var destinationUri: Uri? = null
        var finalized = false
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

                val dest = createDestinationUri(file.name, file.isVideo)
                destinationUri = dest
                val out = context.contentResolver.openOutputStream(dest)
                    ?: throw IOException("Could not open output stream for ${file.name}")

                out.use { outputStream ->
                    body.byteStream().use { inputStream ->
                        copyStreamWithProgress(inputStream, outputStream, totalBytes, onProgress)
                    }
                }

                finalizeDownloadUri(dest)
                finalized = true
                Result.success(dest)
            }
        } catch (e: Exception) {
            if (!finalized) deleteUnfinalizedUri(destinationUri)
            if (e is CancellationException) throw e
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
        var destinationUri: Uri? = null
        var finalized = false
        try {
            val dest = createDestinationUri(name, isVideoFile(name))
            destinationUri = dest
            val inStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Could not open source stream")
            val outStream = context.contentResolver.openOutputStream(dest)
                ?: throw IOException("Could not open destination stream")

            inStream.use { input ->
                outStream.use { output ->
                    copyStreamWithProgress(input, output, sizeBytes, onProgress)
                }
            }

            finalizeDownloadUri(dest)
            finalized = true
            Result.success(dest)
        } catch (e: Exception) {
            if (!finalized) deleteUnfinalizedUri(destinationUri)
            if (e is CancellationException) throw e
            Log.e(TAG, "USB Import failed for $name", e)
            Result.failure(e)
        }
    }

    private fun deleteUnfinalizedUri(uri: Uri?) {
        if (uri == null) return
        try {
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteLocalFile(file: CameraMediaFile): Boolean = withContext(Dispatchers.IO) {
        try {
            if (file.contentUri != null) {
                val uri = file.contentUri.toUri()
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
            val downloadsRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloadsRoot, FOLDER).apply {
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

    private suspend fun copyStreamWithProgress(
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
            currentCoroutineContext().ensureActive()
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

    private fun findLocalCompanionUri(baseName: String, extension: String): Uri? {
        val targetName = "$baseName$extension"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
            val selectionArgs = arrayOf(targetName, "%$FOLDER%")
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

        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { downloadsDir ->
            val appDownloadsFile = File(File(downloadsDir, FOLDER), targetName)
            if (appDownloadsFile.exists()) return Uri.fromFile(appDownloadsFile)
        }

        context.getExternalFilesDir(null)?.let { filesRoot ->
            val appDirFile = File(File(filesRoot, "tracks"), targetName)
            if (appDirFile.exists()) return Uri.fromFile(appDirFile)
        }

        return null
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
