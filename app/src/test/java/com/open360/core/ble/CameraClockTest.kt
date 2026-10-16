package com.open360.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraClockTest {

    private val base = 1_792_180_000_000L

    /** Option 12 as a camera whose clip clock is [offsetMs] ahead would answer a request handled at [utcMs]. */
    private fun reading(askedUtcMs: Long, answeredUtcMs: Long, offsetMs: Long): CameraClock.Reading {
        val handled = (askedUtcMs + answeredUtcMs) / 2
        val seconds = Math.floorDiv(handled + offsetMs - CameraClock.READING_BEHIND_CLIP_CLOCK_MS, 1000L)
        return CameraClock.Reading(askedUtcMs, answeredUtcMs, seconds)
    }

    @Test
    fun readingsAcrossTicksPinTheOffset() {
        // As polled: one request every 150 ms for 3.5 s, each answered 100 ms later
        for (offset in listOf(3110L, -9890L, 110L, 6080L)) {
            val readings = (0 until 24).map { reading(base + it * 150L, base + it * 150L + 100, offset) }
            val measured = CameraClock.offset(readings)!!
            assertTrue("$offset in ${measured.lowMs}..${measured.highMs}", offset >= measured.lowMs && offset < measured.highMs)
            assertTrue(measured.uncertaintyMs <= 75)
        }
    }

    @Test
    fun oneReadingOnlyBoundsTheOffsetToASecond() {
        val measured = CameraClock.offset(listOf(reading(base, base + 100, 3110L)))!!
        assertTrue(measured.highMs - measured.lowMs in 900..1100)
    }

    @Test
    fun noReadingsOrDisagreeingOnesGiveNothing() {
        assertNull(CameraClock.offset(emptyList()))
        val a = reading(base, base + 100, 3110L)
        val b = reading(base + 150, base + 250, 7110L)
        assertNull(CameraClock.offset(listOf(a, b)))
    }

    @Test
    fun aSetLandsWithinHalfASecondOfGpsTime() {
        // A clock 3.11 s fast ticks at x.890 s of GPS time. It is set halfway between ticks,
        // with the second whose start is nearest its last tick, and so lands 0.11 s fast.
        val set = CameraClock.planSet(base + 123, 3110L, 300L)
        assertEquals(base + 1390, set.handleAtUtcMs)
        assertEquals(1_792_180_001L, set.utcSeconds)
        assertEquals(110L, set.expectedOffsetMs)

        // Whatever the offset and the moment, the plan is halfway between ticks, at least the
        // lead away, and lands within half a second
        for (offset in listOf(-9890L, -500L, 0L, 499L, 4110L, 6080L)) {
            for (now in listOf(base, base + 1, base + 499, base + 999)) {
                val plan = CameraClock.planSet(now, offset, 300L)
                assertTrue(plan.handleAtUtcMs >= now + 300 && plan.handleAtUtcMs < now + 1300)
                assertEquals(500L, Math.floorMod(plan.handleAtUtcMs + offset, 1000L))
                assertTrue(plan.expectedOffsetMs in -500L..500L)
                assertEquals(plan.expectedOffsetMs, plan.utcSeconds * 1000 - (plan.handleAtUtcMs - 500))
            }
        }
    }

    @Test
    fun stampsAllowForWhenTheClockIsRead() {
        // Video reads the clock about 1 s before the first frame, PRO Video and the rest 0.2 s
        assertEquals(-890L, CameraClock.stampOffsetMs(110L, captureState = 1))
        assertEquals(-90L, CameraClock.stampOffsetMs(110L, captureState = 17))
        assertEquals(2910L, CameraClock.stampOffsetMs(3110L, captureState = null))
    }
}
