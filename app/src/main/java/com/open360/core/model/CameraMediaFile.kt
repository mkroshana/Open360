package com.open360.core.model

import com.open360.core.gps.GpxTrackSummary
import java.util.Locale

data class CameraMediaFile(
    val name: String,
    val fileUrl: String,
    val localPath: String? = null,
    val contentUri: String? = null,
    val sizeBytes: Long = 0L,
    val dateTime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val isVideo: Boolean = true,
    val thumbnailUrl: String? = null,
    val isImported: Boolean = false,
    val hasCompanionGpx: Boolean = false,
    val gpxSummary: GpxTrackSummary? = null,
    // A camera video's low-resolution copy (.lrv) on the camera, for previews
    val previewUrl: String? = null
) {
    val formattedSize: String
        get() = when {
            sizeBytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", sizeBytes / (1024.0 * 1024 * 1024))
            sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024))
            sizeBytes >= 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }

    val baseNameWithoutExtension: String
        get() = name.substringBeforeLast(".")

    val playableUri: String
        get() = contentUri ?: localPath ?: fileUrl
}
