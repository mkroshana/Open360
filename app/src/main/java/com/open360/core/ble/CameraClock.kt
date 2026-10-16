package com.open360.core.ble

/**
 * The GO 3S's own clock against GPS time, and what Insta360 Studio does with it.
 *
 * A clip's `first_gps_timestamp` is the later of its first GPS record's time and the camera's
 * clock reading taken a little before the first frame, and Studio shows at each video moment the
 * record whose time is that plus the video time. So once the stream starts before the first
 * frame, the camera's clock decides where Studio puts the GPS, and each record is stamped with
 * its moment moved onto that clock (see [stampOffsetMs]).
 *
 * The clock is read with option 12, in whole seconds, 10 s below the clock clips are stamped
 * with. A set only replaces its count of seconds: it keeps its own fraction of a second, so it
 * lands at the value sent less the last tick before the set was handled.
 */
object CameraClock {

    /** Option 12 reads this much below the clock the camera stamps clips with. */
    const val READING_BEHIND_CLIP_CLOCK_MS = 10_000L

    // How long before the first frame the camera reads its clock for first_gps_timestamp, by
    // recording. PRO Video: 0.15, 0.25 and 0.15 s on three clips. Video: 0.64, 0.82, 1.46 and
    // 0.98 s on four, following nothing found, so a Video clip can be about 0.4 s out either way.
    // Other modes have not been measured and are taken as PRO Video.
    private const val READ_BEFORE_FIRST_FRAME_VIDEO_MS = 1000L
    private const val READ_BEFORE_FIRST_FRAME_OTHER_MS = 200L

    /** CameraCaptureState of a normal Video recording. */
    const val CAPTURE_STATE_VIDEO = 1

    /**
     * One reading of option 12: the GPS times at which it was asked for and answered, and the
     * whole UTC second it gave.
     */
    data class Reading(val askedUtcMs: Long, val answeredUtcMs: Long, val utcSeconds: Long)

    /** The clip clock is ahead of GPS time by somewhere from [lowMs] to just under [highMs]. */
    data class Offset(val lowMs: Long, val highMs: Long) {
        val ms: Long get() = (lowMs + highMs) / 2
        val uncertaintyMs: Long get() = (highMs - lowMs) / 2
    }

    /**
     * How far the clip clock is ahead of GPS time. Each reading shows the clock had reached its
     * second, and not the next, somewhere between asking and the answer; readings taken across
     * the clock's ticks narrow that down. Null when there are none or they disagree.
     */
    fun offset(readings: List<Reading>): Offset? {
        if (readings.isEmpty()) return null
        var low = Long.MIN_VALUE
        var high = Long.MAX_VALUE
        for (r in readings) {
            val clipMs = r.utcSeconds * 1000 + READING_BEHIND_CLIP_CLOCK_MS
            low = maxOf(low, clipMs - r.answeredUtcMs)
            high = minOf(high, clipMs + 1000 - r.askedUtcMs)
        }
        return if (low < high) Offset(low, high) else null
    }

    /**
     * A set of the clock: the UTC second to send, the GPS time at which the camera should handle
     * it, and how far ahead of GPS time the clock should then be.
     */
    data class Set(val utcSeconds: Long, val handleAtUtcMs: Long, val expectedOffsetMs: Long)

    /**
     * Plans putting the clock as near GPS time as its own fraction of a second allows, within
     * half a second. The set is timed for halfway between two of the clock's ticks, so it lands
     * the same with up to 0.4 s of delay either way. [nowUtcMs] is GPS time now, [offsetMs] the
     * clock's measured offset and [leadMs] the soonest the set could be handled.
     */
    fun planSet(nowUtcMs: Long, offsetMs: Long, leadMs: Long): Set {
        // The clock reaches a whole second at GPS times t where t + offset is one
        val tickPhase = Math.floorMod(-offsetMs, 1000L)
        val earliest = nowUtcMs + leadMs
        val handleAt = earliest + Math.floorMod(tickPhase + 500 - earliest, 1000L)
        val lastTick = handleAt - 500
        val seconds = Math.floorDiv(lastTick + 500, 1000L)
        return Set(seconds, handleAt, seconds * 1000 - lastTick)
    }

    /**
     * How much later than its moment a GPS record is stamped, so that Studio shows it at that
     * moment: the clock's offset, less how long before the first frame the camera read it for a
     * recording in [captureState].
     */
    fun stampOffsetMs(clockOffsetMs: Long, captureState: Int?): Long =
        clockOffsetMs - if (captureState == CAPTURE_STATE_VIDEO) READ_BEFORE_FIRST_FRAME_VIDEO_MS else READ_BEFORE_FIRST_FRAME_OTHER_MS
}
