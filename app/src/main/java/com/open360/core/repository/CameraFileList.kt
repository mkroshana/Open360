package com.open360.core.repository

import com.open360.core.model.CameraMediaFile

/**
 * Turns the paths a camera lists into the files the footage screen shows. A GO 3S keeps each
 * video as its full clip and a low-resolution copy made at the same time, named alike:
 * PRO_VID_20261015_181505_00_095.mp4 and PRO_LRV_20261015_181505_01_095.lrv. The copy is not
 * shown on its own but attached to its clip as the preview.
 */
object CameraFileList {

    // Optional PRO_ prefix, kind, date, time, a stream number and the file number
    private val NAME = Regex("""^(PRO_)?(VID|LRV|IMG)_(\d{8})_(\d{6})_\d{2}_(\d+)\.\w+$""", RegexOption.IGNORE_CASE)

    private val VIDEO_EXTENSIONS = setOf("mp4", "insv", "lrv")

    /** Files to show for [paths], in the camera's order (newest first), each at [baseUrl] + its path. */
    fun toMediaFiles(paths: List<String>, baseUrl: String): List<CameraMediaFile> {
        val previews = HashMap<String, String>()
        val clips = HashSet<String>()
        for (path in paths) {
            val match = NAME.matchEntire(fileName(path)) ?: continue
            when (match.kind()) {
                "LRV" -> previews[match.clipKey()] = path
                "VID" -> clips += match.clipKey()
            }
        }

        return paths.mapNotNull { path ->
            val name = fileName(path)
            val match = NAME.matchEntire(name)
            // A low-resolution copy goes with its clip
            if (match != null && match.kind() == "LRV" && match.clipKey() in clips) return@mapNotNull null
            CameraMediaFile(
                name = name,
                fileUrl = url(baseUrl, path),
                dateTime = match?.let { formatDateTime(it.groupValues[3], it.groupValues[4]) } ?: "",
                isVideo = name.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS,
                previewUrl = match?.takeIf { it.kind() == "VID" }?.let { previews[it.clipKey()] }?.let { url(baseUrl, it) }
            )
        }
    }

    /** Capture date/time parsed from a camera file name such as VID_20261015_181505_00_095.mp4, or null. */
    fun parseDateTimeFromName(name: String): String? {
        val match = NAME.matchEntire(fileName(name)) ?: return null
        return formatDateTime(match.groupValues[3], match.groupValues[4])
    }

    private fun fileName(path: String) = path.substringAfterLast('/')

    private fun url(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun MatchResult.kind() = groupValues[2].uppercase()

    // PRO_ or not, date, time and file number: what a clip and its copy share
    private fun MatchResult.clipKey() =
        "${groupValues[1].uppercase()}${groupValues[3]}_${groupValues[4]}_${groupValues[5]}"

    // "20261015" and "181505" as "2026-10-15 18:15:05"
    private fun formatDateTime(date: String, time: String) =
        "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)} " +
            "${time.substring(0, 2)}:${time.substring(2, 4)}:${time.substring(4, 6)}"
}
