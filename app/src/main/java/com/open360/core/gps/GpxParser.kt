package com.open360.core.gps

import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double,
    val timestampMs: Long
)

data class GpxTrackSummary(
    val pointCount: Int,
    val totalDistanceMeters: Double,
    val durationMs: Long,
    val maxSpeedKmh: Double,
    val elevationGainMeters: Double
) {
    val formattedDistance: String
        get() = formattedDistance(inMph = false)

    fun formattedDistance(inMph: Boolean): String = if (inMph) {
        val miles = totalDistanceMeters / 1609.344
        if (miles >= 0.1) String.format(Locale.US, "%.2f mi", miles)
        else String.format(Locale.US, "%.0f ft", totalDistanceMeters * 3.28084)
    } else {
        when {
            totalDistanceMeters >= 1000.0 -> String.format(Locale.US, "%.2f km", totalDistanceMeters / 1000.0)
            else -> String.format(Locale.US, "%.0f m", totalDistanceMeters)
        }
    }

    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

    val formattedMaxSpeed: String
        get() = formattedMaxSpeed(inMph = false)

    fun formattedMaxSpeed(inMph: Boolean): String = if (inMph) {
        String.format(Locale.US, "%.1f mph", maxSpeedKmh * 0.621371)
    } else {
        String.format(Locale.US, "%.1f km/h", maxSpeedKmh)
    }
}

object GpxParser {

    private fun parseUtcTime(isoTime: String): Long {
        if (isoTime.isBlank()) return 0L
        return try {
            Instant.parse(isoTime).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(isoTime).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    format.timeZone = TimeZone.getTimeZone("UTC")
                    format.parse(isoTime.substringBefore("."))?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    fun parse(inputStream: InputStream): GpxTrackSummary {
        val points = mutableListOf<GpxPoint>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(inputStream)
            val trkptList = doc.getElementsByTagName("trkpt")

            for (i in 0 until trkptList.length) {
                val node = trkptList.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    val element = node as org.w3c.dom.Element
                    val lat = element.getAttribute("lat").toDoubleOrNull() ?: continue
                    val lon = element.getAttribute("lon").toDoubleOrNull() ?: continue

                    var ele = 0.0
                    val eleNodes = element.getElementsByTagName("ele")
                    if (eleNodes.length > 0) {
                        ele = eleNodes.item(0).textContent?.trim()?.toDoubleOrNull() ?: 0.0
                    }

                    var timeMs = 0L
                    val timeNodes = element.getElementsByTagName("time")
                    if (timeNodes.length > 0) {
                        val timeStr = timeNodes.item(0).textContent?.trim() ?: ""
                        timeMs = parseUtcTime(timeStr)
                    }

                    points.add(GpxPoint(latitude = lat, longitude = lon, elevationMeters = ele, timestampMs = timeMs))
                }
            }
        } catch (_: Exception) {
            return GpxTrackSummary(0, 0.0, 0L, 0.0, 0.0)
        }

        if (points.isEmpty()) {
            return GpxTrackSummary(0, 0.0, 0L, 0.0, 0.0)
        }

        var totalDist = 0.0
        var maxSpeedKmh = 0.0
        var elevationGain = 0.0

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val d = haversineDistanceMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            totalDist += d

            val dtMs = curr.timestampMs - prev.timestampMs
            if (dtMs in 100..10_000 && d > 0.1) {
                val speedKmh = (d / (dtMs / 1000.0)) * 3.6
                if (speedKmh < 400.0 && speedKmh > maxSpeedKmh) {
                    maxSpeedKmh = speedKmh
                }
            }

            val dEle = curr.elevationMeters - prev.elevationMeters
            if (dEle > 0.0) {
                elevationGain += dEle
            }
        }

        val duration = (points.last().timestampMs - points.first().timestampMs).coerceAtLeast(0L)

        return GpxTrackSummary(
            pointCount = points.size,
            totalDistanceMeters = totalDist,
            durationMs = duration,
            maxSpeedKmh = maxSpeedKmh,
            elevationGainMeters = elevationGain
        )
    }

    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
