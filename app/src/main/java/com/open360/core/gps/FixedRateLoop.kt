package com.open360.core.gps

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Calls [onTick] once per [periodMs] without drift: each tick is scheduled from the start time,
 * not from when the previous one finished.
 *
 * After a stall the missed ticks run back to back, so the count stays right. If the loop is more
 * than [maxCatchUpMs] behind it gives up on the missed ticks and starts over from now.
 *
 * [now] must be a monotonic clock in milliseconds.
 */
suspend fun runAtFixedRate(
    periodMs: Long,
    maxCatchUpMs: Long,
    now: () -> Long,
    onTick: suspend () -> Unit
) {
    var nextTick = now()
    while (currentCoroutineContext().isActive) {
        onTick()
        nextTick += periodMs
        val wait = nextTick - now()
        if (wait > 0) {
            delay(wait)
        } else if (-wait > maxCatchUpMs) {
            nextTick = now()
        }
    }
}
