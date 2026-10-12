package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSendPacerTest {

    @Test
    fun testOnlyTheBurstGoesOutAtOnce() {
        val pacer = GpsSendPacer(recordsPerSecond = 12.0, maxBurst = 4)
        assertEquals(4, pacer.take(1000L, 40))
        assertEquals(0, pacer.take(1000L, 40))
        // One record becomes due every twelfth of a second
        assertEquals(84L, pacer.waitMs(1000L))
        assertEquals(0, pacer.take(1083L, 40))
        assertEquals(1, pacer.take(1084L, 40))
    }

    @Test
    fun testTakesNoMoreThanWanted() {
        val pacer = GpsSendPacer(recordsPerSecond = 12.0, maxBurst = 4)
        assertEquals(1, pacer.take(0L, 1))
        assertEquals(0L, pacer.waitMs(0L))
        assertEquals(3, pacer.take(0L, 10))
    }

    @Test
    fun testIdleTimeDoesNotBuildUpABiggerBurst() {
        val pacer = GpsSendPacer(recordsPerSecond = 12.0, maxBurst = 4)
        pacer.take(0L, 4)
        assertEquals(4, pacer.take(60_000L, 40))
        assertEquals(0, pacer.take(60_000L, 40))
    }

    @Test
    fun testABacklogIsWorkedOffAtTheSetRate() {
        // 39 records wait, as after a GPS outage, while ten more are made every second
        val pacer = GpsSendPacer(recordsPerSecond = 12.0, maxBurst = 4)
        var waiting = 39
        val sentAt = ArrayList<Long>()
        var now = 0L
        while (now <= 30_000L) {
            if (now > 0 && now % 100 == 0L) waiting++
            val sent = pacer.take(now, minOf(waiting, 4))
            waiting -= sent
            repeat(sent) { sentAt.add(now) }
            now += 10
        }

        // The camera took 25 records in a second and 34 in two before it dropped any
        for (start in 0L..28_000L step 100) {
            assertTrue(sentAt.count { it >= start && it < start + 1000 } <= 16)
            assertTrue(sentAt.count { it >= start && it < start + 2000 } <= 28)
        }
        // The backlog clears, at two records a second more than are made
        assertEquals(0, waiting)
        assertTrue(sentAt.count { it < 20_000L } >= 39 + 190)
    }

    @Test
    fun testTheNormalBatchOfTenAFixIsNotHeldBack() {
        // Ten records arrive together once a second and must all be gone before the next ten
        val pacer = GpsSendPacer(recordsPerSecond = 12.0, maxBurst = 4)
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
        val pacer = GpsSendPacer(recordsPerSecond = 12.0, maxBurst = 4)
        pacer.take(5000L, 4)
        assertEquals(0, pacer.take(4000L, 4))
        assertEquals(1, pacer.take(5084L, 4))
    }
}
