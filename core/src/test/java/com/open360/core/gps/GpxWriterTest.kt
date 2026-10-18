package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class GpxWriterTest {

    @Test
    fun testTrackPointFormat() {
        // 2026-01-01 12:00:00.250 UTC
        val point = GpxWriter.trackPoint(6.6657512, -79.9369134, 12.34, 1767268800250L)
        assertEquals(
            "<trkpt lat=\"6.6657512\" lon=\"-79.9369134\"><ele>12.3</ele><time>2026-01-01T12:00:00.250Z</time></trkpt>\n",
            point
        )
    }

    @Test
    fun testHeaderCarriesTheVideoNameAndTimes() {
        val gpx = GpxWriter.header(
            trackName = "VID_20261012_163049_00_066.mp4",
            description = "GPS for VID_20261012_163049_00_066.mp4, 2026-10-12 16:30:51 to 2026-10-12 16:31:31 local time",
            startUtcMs = 1767268800000L
        ) + GpxWriter.FOOTER

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
        val metadata = doc.getElementsByTagName("metadata").item(0).textContent
        assertTrue(metadata.contains("VID_20261012_163049_00_066.mp4"))
        assertTrue(metadata.contains("16:30:51 to 2026-10-12 16:31:31"))
        assertTrue(metadata.contains("2026-01-01T12:00:00.000Z"))
        // The track itself is named after the video too
        assertEquals("VID_20261012_163049_00_066.mp4", doc.getElementsByTagName("name").item(1).textContent)
    }

    @Test
    fun testHeaderPointsAndFooterFormValidGpx() {
        val gpx = GpxWriter.header("Ride <1> & more") +
            GpxWriter.trackPoint(6.5, 79.9, 10.0, 1767268800000L) +
            GpxWriter.trackPoint(6.6, 79.8, 11.0, 1767268801000L) +
            GpxWriter.FOOTER

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
        assertEquals("gpx", doc.documentElement.tagName)
        assertEquals("1.1", doc.documentElement.getAttribute("version"))
        assertEquals("Ride <1> & more", doc.getElementsByTagName("name").item(0).textContent)

        val points = doc.getElementsByTagName("trkpt")
        assertEquals(2, points.length)
        assertEquals("6.6000000", points.item(1).attributes.getNamedItem("lat").nodeValue)
        assertTrue(points.item(1).textContent.contains("2026-01-01T12:00:01.000Z"))
    }
}
