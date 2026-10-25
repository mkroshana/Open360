package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSendPacerTest {

    // As GpsRemoteBleManager paces the records sent to the camera
    private fun pacer() = GpsSendPacer(perSecond = 10.0, maxBurst = 10)

    @Test
    fun testOnlyTheBurstGoesOutAtOnce() {
        val pacer = pacer()
        assertEquals(10, pacer.take(1000L, 40))
        assertEquals(0, pacer.take(1000L, 40))
        // One record becomes due every tenth of a second
        assertEquals(100L, pacer.waitMs(1000L))
        assertEquals(0, pacer.take(1099L, 40))
        assertEquals(1, pacer.take(1100L, 40))
    }

    @Test
    fun testTakesNoMoreThanWanted() {
        val pacer = pacer()
        assertEquals(4, pacer.take(0L, 4))
        assertEquals(0L, pacer.waitMs(0L))
        assertEquals(6, pacer.take(0L, 40))
    }

    @Test
    fun testIdleTimeDoesNotBuildUpABiggerBurst() {
        val pacer = pacer()
        pacer.take(0L, 10)
        assertEquals(10, pacer.take(60_000L, 40))
        assertEquals(0, pacer.take(60_000L, 40))
    }

    @Test
    fun testABacklogIsSentNoFasterThanTheCameraTakesRecords() {
        // 200 records wait, as after the link moved during a recording, while ten more are made
        // every second; a message holds four
        val pacer = pacer()
        var waiting = 200
        val sentAt = ArrayList<Long>()
        var now = 0L
        while (now <= 60_000L) {
            if (now > 0 && now % 100 == 0L) waiting++
            val sent = pacer.take(now, minOf(waiting, 4))
            waiting -= sent
            repeat(sent) { sentAt.add(now) }
            now += 10
        }

        // The camera took ten a second and dropped records at twelve, after 15 s
        for (start in 0L..58_000L step 100) {
            assertTrue(sentAt.count { it >= start && it < start + 2000 } <= 30)
        }
        assertTrue(sentAt.size <= 10 + 600)
        // So the backlog is not caught up: it goes out late, the same amount behind throughout
        assertTrue(waiting in 190..201)
    }

    @Test
    fun testTheNormalBatchOfTenAFixIsNotHeldBack() {
        // Ten records arrive together once a second and must all be gone before the next ten
        val pacer = pacer()
        var waiting = 0
        var longestWait = 0
        var now = 0L
        while (now < 20_000L) {
            if (now % 1000 == 0L) {
                longestWait = maxOf(longestWait, waiting)
                waiting += 10
            }
            waiting -= pacer.take(now, minOf(waiting, 4))
            now += 10
        }
        assertEquals(0, longestWait)
    }

    @Test
    fun testAClockThatStepsBackIsIgnored() {
        val pacer = pacer()
        pacer.take(5000L, 10)
        assertEquals(0, pacer.take(4000L, 4))
        assertEquals(1, pacer.take(5100L, 4))
    }
}
