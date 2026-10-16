package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsStreamScheduleTest {

    private fun schedule() = GpsStreamSchedule(periodMs = 100, maxWaitMs = 4000)

    @Test
    fun nothingIsDueWhileIdle() {
        assertTrue(schedule().due(nowMs = 50_000, newestFixMs = 49_500).isEmpty())
    }

    @Test
    fun samplesWaitForAFixTakenAtOrAfterTheirMoment() {
        val s = schedule()
        s.setStart(10_000)

        // The newest fix is from before the start: nothing can be interpolated yet
        assertTrue(s.due(nowMs = 10_600, newestFixMs = 9_700).isEmpty())

        // A fix taken at 10.7 s arrives: every moment up to it is ready, from the start
        assertEquals(
            listOf(10_000L, 10_100L, 10_200L, 10_300L, 10_400L, 10_500L, 10_600L, 10_700L),
            s.due(nowMs = 11_500, newestFixMs = 10_700)
        )

        // Nothing more until the next fix
        assertTrue(s.due(nowMs = 12_000, newestFixMs = 10_700).isEmpty())
        assertEquals(10, s.due(nowMs = 12_500, newestFixMs = 11_700).size)
    }

    @Test
    fun aRecordingJoinedLateIsFilledInFromItsFirstFrame() {
        // The app connects 12 s into a recording: its stream starts at 88 s, it is now 100 s
        val s = schedule()
        s.setStart(88_000)

        // Only as many as there is room to send are handed out at a time, oldest first
        val first = s.due(nowMs = 100_000, newestFixMs = 99_900, max = 30)
        assertEquals(30, first.size)
        assertEquals(88_000L, first.first())
        assertEquals(90_900L, first.last())

        // The rest follow in order with none skipped, until the stream has caught up
        val all = ArrayList(first)
        var now = 100_100L
        while (now <= 129_100L) {
            all.addAll(s.due(nowMs = now, newestFixMs = now - 100, max = 20))
            now += 1000
        }
        for (i in all.indices) assertEquals(88_000L + 100L * i, all[i])
        // By then every moment up to the newest fix, taken at 129 s, has gone out
        assertEquals(129_000L, all.last())
    }

    @Test
    fun everySampleIsHandedOutOnceAndInOrderOverALongRecording() {
        val s = schedule()
        s.setStart(10_000)

        val all = ArrayList<Long>()
        var now = 9_937L // ticks do not line up with the start
        while (now <= 80_000) {
            // One fix a second, taken on the whole second and delivered 800 ms later
            val newestFix = Math.floorDiv(now - 800, 1000L) * 1000
            all += s.due(now, newestFix)
            now += 100
        }
        val lastFix = Math.floorDiv(80_000L - 800, 1000L) * 1000
        assertEquals(10_000L, all.first())
        assertEquals(lastFix, all.last())
        assertEquals(((lastFix - 10_000) / 100 + 1).toInt(), all.size)
        assertTrue(all.zipWithNext().all { (a, b) -> b - a == 100L })
    }

    @Test
    fun aSampleNoFixCameForIsReleasedAfterTheWaitSoTheStreamKeepsMoving() {
        val s = schedule()
        s.setStart(10_000)

        // No fix at all
        assertTrue(s.due(nowMs = 13_900, newestFixMs = null).isEmpty())
        assertEquals(listOf(10_000L), s.due(nowMs = 14_000, newestFixMs = null))
        assertEquals(listOf(10_100L, 10_200L), s.due(nowMs = 14_200, newestFixMs = null))
    }

    @Test
    fun neverHandsOutAMomentThatHasNotHappenedYet() {
        val s = schedule()
        s.setStart(10_000)
        // A fix time ahead of the clock must not release future samples
        assertEquals(listOf(10_000L, 10_100L), s.due(nowMs = 10_150, newestFixMs = 12_000))
    }

    @Test
    fun aCorrectionToTheFirstFrameIsTakenUntilTheFirstSampleHasGoneOut() {
        val s = schedule()
        s.setStart(10_000)
        s.setStart(10_040)
        assertEquals(listOf(10_040L), s.due(nowMs = 11_000, newestFixMs = 10_100))

        s.setStart(10_090) // too late, samples are already out
        assertEquals(listOf(10_140L), s.due(nowMs = 11_000, newestFixMs = 10_200))
    }

    @Test
    fun aNewRecordingStartsTheCountAgain() {
        val s = schedule()
        s.setStart(10_000)
        s.due(nowMs = 12_500, newestFixMs = 11_000)

        s.setStart(null)
        assertTrue(s.due(nowMs = 13_000, newestFixMs = 12_500).isEmpty())

        s.setStart(40_000)
        assertEquals(listOf(40_000L), s.due(nowMs = 41_000, newestFixMs = 40_050))

        // A new recording seen without the idle state in between
        s.setStart(70_000)
        assertEquals(listOf(70_000L), s.due(nowMs = 71_000, newestFixMs = 70_050))
    }
}
