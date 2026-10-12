package com.open360.core.gps

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the pieces of a GPX 1.1 track file.
 *
 * A file is [header] + any number of [trackPoint] lines + [FOOTER].
 */
object GpxWriter {

    const val FOOTER = "</trkseg></trk></gpx>\n"

    fun header(trackName: String): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Open360\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            "<trk><name>${escape(trackName)}</name><trkseg>\n"
    }

    /**
     * One track point with elevation in metres and time in UTC.
     */
    fun trackPoint(latitude: Double, longitude: Double, altitudeMeters: Double, timestampMs: Long): String {
        return String.format(
            Locale.US,
            "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",
            latitude,
            longitude,
            altitudeMeters,
            utcTime(timestampMs)
        )
    }

    private fun utcTime(timestampMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestampMs))
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
