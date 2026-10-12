package com.open360.core.gps

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FixedRateLoopTest {

    @Test
    fun ticksExactlyTenTimesASecondEvenWhenEachTickIsSlow() = runTest {
        var ticks = 0
        val job = launch {
            runAtFixedRate(periodMs = 100, maxCatchUpMs = 2000, now = { testScheduler.currentTime }) {
                ticks++
                delay(37) // a plain delay(100) loop would run every 137 ms and manage 438
            }
        }
        advanceTimeBy(60_000)
        job.cancel()
        assertEquals(600, ticks)
    }

    @Test
    fun makesUpMissedTicksAfterAShortStall() = runTest {
        var ticks = 0
        val job = launch {
            runAtFixedRate(periodMs = 100, maxCatchUpMs = 2000, now = { testScheduler.currentTime }) {
                ticks++
                if (ticks == 5) delay(450)
            }
        }
        advanceTimeBy(10_000)
        job.cancel()
        assertEquals(100, ticks)
    }

    @Test
    fun startsOverInsteadOfBurstingAfterALongStall() = runTest {
        var ticks = 0
        val job = launch {
            runAtFixedRate(periodMs = 100, maxCatchUpMs = 2000, now = { testScheduler.currentTime }) {
                ticks++
                if (ticks == 11) delay(5000) // the tick at 1.0 s blocks until 6.0 s
            }
        }
        advanceTimeBy(10_000)
        job.cancel()
        // 11 ticks from 0.0 to 1.0 s, then 40 from 6.0 to 9.9 s
        assertEquals(51, ticks)
    }
}
