package com.open360.core.gps

import kotlin.math.ceil

/**
 * Limits how fast GPS records are sent to the camera.
 *
 * The camera loses records that arrive much faster than the ten a second they are made at. On a
 * GO 3S, after 20 records in 2 s it took 20 more in half a second and then dropped the next 33.
 * Insta360 Studio places the records on the video by counting them, so one lost record shifts
 * everything after it. A backlog is therefore worked off at [recordsPerSecond], only a little
 * above ten, with never more than [maxBurst] going out together.
 */
class GpsSendPacer(private val recordsPerSecond: Double, private val maxBurst: Int) {

    private var allowance = maxBurst.toDouble()
    private var allowanceAtMs: Long? = null

    /**
     * How many records, up to [wanted], may be sent at [nowMs]. They count as sent.
     */
    fun take(nowMs: Long, wanted: Int): Int {
        refill(nowMs)
        val count = minOf(wanted, allowance.toInt()).coerceAtLeast(0)
        allowance -= count
        return count
    }

    /**
     * How long after [nowMs] the next record may be sent. Zero when one may go now.
     */
    fun waitMs(nowMs: Long): Long {
        refill(nowMs)
        if (allowance >= 1.0) return 0L
        return ceil((1.0 - allowance) * 1000.0 / recordsPerSecond).toLong()
    }

    private fun refill(nowMs: Long) {
        val last = allowanceAtMs
        if (last == null) {
            allowanceAtMs = nowMs
        } else if (nowMs > last) {
            allowance = minOf(maxBurst.toDouble(), allowance + (nowMs - last) * recordsPerSecond / 1000.0)
            allowanceAtMs = nowMs
        }
    }
}
