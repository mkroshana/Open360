package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxParserTest {

    @Test
    fun testParseValidGpx() {
        val gpxContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Open360" xmlns="http://www.topografix.com/GPX/1/1">
            <metadata><name>VID_20261012_163049_00_066.mp4</name><time>2026-10-12T16:30:49.000Z</time></metadata>
            <trk><name>VID_20261012_163049_00_066.mp4</name><trkseg>
            <trkpt lat="37.774900" lon="-122.419400"><ele>10.0</ele><time>2026-10-12T16:30:50.000Z</time></trkpt>
            <trkpt lat="37.775000" lon="-122.419400"><ele>12.5</ele><time>2026-10-12T16:30:52.000Z</time></trkpt>
            <trkpt lat="37.775100" lon="-122.419400"><ele>15.0</ele><time>2026-10-12T16:30:54.000Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val summary = GpxParser.parse(ByteArrayInputStream(gpxContent.toByteArray()))
        assertEquals(3, summary.pointCount)
        assertTrue("Distance should be > 20 meters", summary.totalDistanceMeters > 20.0)
        assertEquals(4000L, summary.durationMs)
        assertEquals("00:04", summary.formattedDuration)
        assertEquals(5.0, summary.elevationGainMeters, 0.01)
        assertTrue(summary.maxSpeedKmh > 0.0)
    }

    @Test
    fun testEmptyGpx() {
        val gpxContent = "<gpx></gpx>"
        val summary = GpxParser.parse(ByteArrayInputStream(gpxContent.toByteArray()))
        assertEquals(0, summary.pointCount)
        assertEquals(0.0, summary.totalDistanceMeters, 0.0)
    }
}
