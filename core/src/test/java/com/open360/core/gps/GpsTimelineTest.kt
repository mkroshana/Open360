package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTimelineTest {

    @Test
    fun fixesFromReturnsTheRememberedFixesSinceAMoment() {
        val timeline = GpsTimeline(keepMs = 60_000L)
        for (second in 0..9) {
            timeline.add(GpsFix(1000L * second, 5_000_000L + 1000L * second, 6.0, 79.0, 10.0, 5.0, 90.0))
        }
        assertEquals(listOf(7000L, 8000L, 9000L), timeline.fixesFrom(6500L).map { it.elapsedMs })
        assertEquals(10, timeline.fixesFrom(-5000L).size)
        assertTrue(timeline.fixesFrom(9001L).isEmpty())
    }

    private fun fix(elapsedMs: Long, lat: Double, lon: Double, speed: Double = 0.0, bearing: Double = 0.0, alt: Double = 0.0) =
        GpsFix(elapsedMs, utcMs = 1_767_268_800_000L + elapsedMs, lat, lon, alt, speed, bearing)

    @Test
    fun emptyTimelineHasNoAnswer() {
        assertNull(GpsTimeline().sampleAt(1000))
        assertNull(GpsTimeline().utcAt(1000))
    }

    @Test
    fun gpsTimeOfAMomentCountsFromTheNearestFix() {
        val timeline = GpsTimeline()
        // The phone's clock and GPS time drift apart: the second fix is 3 ms later than the first suggests
        timeline.add(GpsFix(10_000, 5_000_000L, 6.0, 79.0, 0.0, 0.0, 0.0))
        timeline.add(GpsFix(11_000, 5_001_003L, 6.0, 79.0, 0.0, 0.0, 0.0))
        assertEquals(5_000_200L, timeline.utcAt(10_200))
        assertEquals(5_000_803L, timeline.utcAt(10_800))
        assertEquals(5_031_003L, timeline.utcAt(41_000))
        assertEquals(4_990_000L, timeline.utcAt(0))
        // Too far from any fix
        assertNull(timeline.utcAt(72_000, maxDistanceMs = 60_000))
        assertEquals(5_060_003L, timeline.utcAt(70_000, maxDistanceMs = 60_000))
    }

    @Test
    fun aGpsTimeIsPlacedOnTheDevicesClockFromTheNearestFix() {
        val timeline = GpsTimeline()
        assertNull(timeline.elapsedAt(5_000_000L))
        timeline.add(GpsFix(10_000, 5_000_000L, 6.0, 79.0, 0.0, 0.0, 0.0))
        timeline.add(GpsFix(11_000, 5_001_003L, 6.0, 79.0, 0.0, 0.0, 0.0))
        // A fix the other device took, between and after this one's
        assertEquals(10_200L, timeline.elapsedAt(5_000_200L))
        assertEquals(41_000L, timeline.elapsedAt(5_031_003L))
        // Too far from any fix
        assertNull(timeline.elapsedAt(5_072_000L, maxDistanceMs = 60_000))
    }

    @Test
    fun interpolatesBetweenTheFixBeforeAndAfter() {
        val timeline = GpsTimeline()
        timeline.add(fix(10_000, lat = 6.0000, lon = 79.0000, speed = 2.0, alt = 10.0))
        timeline.add(fix(11_000, lat = 6.0010, lon = 79.0020, speed = 4.0, alt = 20.0))

        val s = timeline.sampleAt(10_300)!!
        assertTrue(s.valid)
        assertEquals(6.0003, s.latitude, 1e-9)
        assertEquals(79.0006, s.longitude, 1e-9)
        assertEquals(2.6, s.speedMps, 1e-9)
        assertEquals(13.0, s.altitudeMeters, 1e-9)
        // The sample's time is the requested moment, to the millisecond
        assertEquals(1_767_268_800_000L + 10_300, s.utcMs)
    }

    @Test
    fun aMomentExactlyOnAFixReturnsThatFix() {
        val timeline = GpsTimeline()
        timeline.add(fix(10_000, 6.0, 79.0))
        timeline.add(fix(11_000, 6.1, 79.1))
        val s = timeline.sampleAt(11_000)!!
        assertTrue(s.valid)
        assertEquals(6.1, s.latitude, 1e-12)
    }

    @Test
    fun doesNotPredictPastTheNewestFix() {
        val timeline = GpsTimeline()
        timeline.add(fix(10_000, 6.0, 79.0))
        timeline.add(fix(11_000, 6.1, 79.1))

        val s = timeline.sampleAt(11_400)!!
        assertFalse(s.valid)
        assertEquals(1_767_268_800_000L + 11_400, s.utcMs)
    }

    @Test
    fun aGapBetweenFixesIsNotBridged() {
        val timeline = GpsTimeline(maxGapMs = 2500)
        timeline.add(fix(10_000, 6.0, 79.0))
        timeline.add(fix(14_000, 6.4, 79.4)) // 4 s without a fix

        assertFalse(timeline.sampleAt(12_000)!!.valid)
    }

    @Test
    fun bearingTurnsTheShortWayThroughNorth() {
        val timeline = GpsTimeline()
        timeline.add(fix(10_000, 6.0, 79.0, bearing = 350.0))
        timeline.add(fix(11_000, 6.0, 79.0, bearing = 10.0))

        assertEquals(0.0, timeline.sampleAt(10_500)!!.bearingDegrees, 1e-9)
        assertEquals(355.0, timeline.sampleAt(10_250)!!.bearingDegrees, 1e-9)
    }

    @Test
    fun ignoresAFixThatIsNotNewerThanTheLast() {
        val timeline = GpsTimeline()
        timeline.add(fix(10_000, 6.0, 79.0))
        timeline.add(fix(11_000, 6.1, 79.1))
        timeline.add(fix(11_000, 9.9, 99.9)) // same moment again
        assertEquals(6.1, timeline.sampleAt(11_000)!!.latitude, 1e-12)
    }
}
