package com.open360.core.gps

import kotlin.math.ceil

/**
 * Limits how fast GPS records are sent to the camera.
 *
 * The camera takes about ten records a second, the rate they are made at, with room for about
 * thirty more, and drops whole messages (UploadGps) beyond that without answering them. A GO 3S
 * catching up on a backlog after the link moved during a recording (2026-10-25) lost about two
 * records a second, from 15 s in, when sent twelve a second (clip 195), and about ten a second,
 * from 3 s in, when sent twenty a second, four to a message (clip 196); sent ten a second it lost
 * none, in messages of one record or four. Insta360 Studio places the records on the video by
 * counting them, so one lost record shifts everything after it. Records therefore go out at no
 * more than [perSecond], with never more than [maxBurst] together. A backlog is not caught up:
 * it is sent late, and the end of the clip goes without as much GPS.
 */
class GpsSendPacer(private val perSecond: Double, private val maxBurst: Int) {

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
        return ceil((1.0 - allowance) * 1000.0 / perSecond).toLong()
    }

    private fun refill(nowMs: Long) {
        val last = allowanceAtMs
        if (last == null) {
            allowanceAtMs = nowMs
        } else if (nowMs > last) {
            allowance = minOf(maxBurst.toDouble(), allowance + (nowMs - last) * perSecond / 1000.0)
            allowanceAtMs = nowMs
        }
    }
}
