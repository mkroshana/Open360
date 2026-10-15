package com.open360.core.model

import com.open360.core.gps.GpxTrackSummary
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
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

    /**
     * What to play: the file on the phone when there is one, otherwise a camera video's
     * low-resolution copy, which streams over Wi-Fi far more easily than the full clip.
     */
    val streamUri: String
        get() = contentUri ?: localPath ?: previewUrl ?: fileUrl

    /** The date and time the camera put in the file name, e.g. "15 Oct 2026, 18:15", or null. */
    val displayDateTime: String?
        get() = try {
            LocalDateTime.parse(dateTime, CAMERA_DATE_TIME).format(DISPLAY_DATE_TIME)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        val CAMERA_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        val DISPLAY_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US)
    }
}
