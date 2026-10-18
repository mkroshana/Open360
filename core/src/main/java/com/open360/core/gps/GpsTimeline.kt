package com.open360.core.gps

import kotlin.math.abs

/**
 * One measured GPS fix. [elapsedMs] is when it was taken on the phone's monotonic clock
 * (SystemClock.elapsedRealtime) and [utcMs] is the same moment in GPS time.
 */
data class GpsFix(
    val elapsedMs: Long,
    val utcMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double
)

/**
 * The position at one moment. [valid] is false when no fixes surround that moment closely enough
 * to say where the phone was.
 */
data class GpsSample(
    val utcMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
    val valid: Boolean
)

/**
 * Keeps the recent fixes and answers "where was the phone at this moment" by interpolating
 * between the fix before it and the fix after it. Nothing is predicted: a moment later than the
 * newest fix has no answer yet.
 */
class GpsTimeline(
    private val maxGapMs: Long = 2500L,
    private val keepMs: Long = 20_000L
) {
    private val fixes = ArrayDeque<GpsFix>()

    @Synchronized
    fun add(fix: GpsFix) {
        val newest = fixes.lastOrNull()
        if (newest != null && fix.elapsedMs <= newest.elapsedMs) return
        fixes.addLast(fix)
        while (fixes.size > 2 && fix.elapsedMs - fixes.first().elapsedMs > keepMs) {
            fixes.removeFirst()
        }
    }

    /** When the newest fix was taken, or null when there are none. */
    @Synchronized
    fun newestFixMs(): Long? = fixes.lastOrNull()?.elapsedMs

    /**
     * The GPS time of [elapsedMs], counted from the nearest fix. Null when there is no fix within
     * [maxDistanceMs] of it, as the phone's clock and GPS time drift apart.
     */
    @Synchronized
    fun utcAt(elapsedMs: Long, maxDistanceMs: Long = Long.MAX_VALUE): Long? {
        val nearest = fixes.minByOrNull { abs(it.elapsedMs - elapsedMs) } ?: return null
        if (abs(nearest.elapsedMs - elapsedMs) > maxDistanceMs) return null
        return nearest.utcMs + (elapsedMs - nearest.elapsedMs)
    }

    /** The remembered fixes taken at or after [elapsedMs], oldest first. */
    @Synchronized
    fun fixesFrom(elapsedMs: Long): List<GpsFix> = fixes.filter { it.elapsedMs >= elapsedMs }

    /**
     * The position at [elapsedMs]. It is valid only when a fix on each side is known and they
     * are no more than [maxGapMs] apart. Otherwise the nearest fix's position is returned marked
     * invalid, with the time still worked out for the requested moment.
     * Returns null when there are no fixes at all.
     */
    @Synchronized
    fun sampleAt(elapsedMs: Long): GpsSample? {
        if (fixes.isEmpty()) return null
        val after = fixes.firstOrNull { it.elapsedMs >= elapsedMs }
        val before = fixes.lastOrNull { it.elapsedMs <= elapsedMs }

        if (before != null && after != null && after.elapsedMs - before.elapsedMs <= maxGapMs) {
            val span = after.elapsedMs - before.elapsedMs
            val f = if (span == 0L) 0.0 else (elapsedMs - before.elapsedMs).toDouble() / span
            return GpsSample(
                utcMs = before.utcMs + Math.round(f * (after.utcMs - before.utcMs)),
                latitude = lerp(before.latitude, after.latitude, f),
                longitude = lerp(before.longitude, after.longitude, f),
                altitudeMeters = lerp(before.altitudeMeters, after.altitudeMeters, f),
                speedMps = lerp(before.speedMps, after.speedMps, f),
                bearingDegrees = lerpBearing(before.bearingDegrees, after.bearingDegrees, f),
                valid = true
            )
        }

        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.elapsedMs - elapsedMs) } ?: return null
        return GpsSample(
            utcMs = nearest.utcMs + (elapsedMs - nearest.elapsedMs),
            latitude = nearest.latitude,
            longitude = nearest.longitude,
            altitudeMeters = nearest.altitudeMeters,
            speedMps = nearest.speedMps,
            bearingDegrees = nearest.bearingDegrees,
            valid = false
        )
    }

    private fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

    /** Interpolates a compass bearing the short way round, so 350 to 10 passes through 0. */
    private fun lerpBearing(a: Double, b: Double, f: Double): Double {
        val delta = ((b - a + 540.0) % 360.0) - 180.0
        return (a + delta * f + 360.0) % 360.0
    }
}
