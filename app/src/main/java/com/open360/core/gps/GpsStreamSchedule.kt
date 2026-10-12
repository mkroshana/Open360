package com.open360.core.gps

import kotlin.math.abs
import kotlin.math.max

/**
 * Works out which GPS samples of a recording are ready to be sent to the camera.
 *
 * Sample k describes the moment `firstFrame + k * periodMs`. It is ready once a fix taken at or
 * after that moment has arrived, so the position can be interpolated instead of guessed. Every
 * sample is handed out exactly once and none is skipped, because the camera's software places
 * them on the video by counting from the first one. They do not have to be sent evenly: the
 * camera keeps them in the order they arrive.
 *
 * A sample that is still not ready [maxWaitMs] after its moment is handed out anyway, to be sent
 * as void, so a loss of GPS cannot hold the stream up for good.
 *
 * The price of waiting for the next fix is the end of the clip: the moments after the last fix
 * that arrived before the stop get no samples.
 */
class GpsStreamSchedule(private val periodMs: Long, private val maxWaitMs: Long) {

    private var firstFrameMs: Long? = null
    private var nextIndex = 0L

    /**
     * Sets when the recording's first frame was captured, or null when not recording.
     *
     * A small correction to the time is accepted until the first sample has gone out. A time
     * that differs by more than [NEW_RECORDING_MS] is a new recording and starts the count again.
     */
    fun setFirstFrame(firstFrameMs: Long?) {
        val current = this.firstFrameMs
        when {
            firstFrameMs == null -> {
                this.firstFrameMs = null
                nextIndex = 0
            }
            current == null || abs(firstFrameMs - current) > NEW_RECORDING_MS -> {
                this.firstFrameMs = firstFrameMs
                nextIndex = 0
            }
            nextIndex == 0L -> this.firstFrameMs = firstFrameMs
        }
    }

    /**
     * The moments whose samples can go out at [nowMs], oldest first, given that the newest fix
     * was taken at [newestFixMs] (null when there is none). Returns up to [max] at a time.
     */
    fun due(nowMs: Long, newestFixMs: Long?, max: Int = MAX_BURST): List<Long> {
        val start = firstFrameMs ?: return emptyList()
        val coveredByFix = if (newestFixMs != null) Math.floorDiv(newestFixMs - start, periodMs) else -1L
        val waitedOut = Math.floorDiv(nowMs - maxWaitMs - start, periodMs)
        // Never ahead of the present, whatever the fix times say
        val notFuture = Math.floorDiv(nowMs - start, periodMs)
        val lastDue = minOf(max(coveredByFix, waitedOut), notFuture)

        val moments = ArrayList<Long>()
        while (nextIndex <= lastDue && moments.size < max) {
            moments.add(start + nextIndex * periodMs)
            nextIndex++
        }
        return moments
    }

    companion object {
        private const val NEW_RECORDING_MS = 500L
        private const val MAX_BURST = 50
    }
}
