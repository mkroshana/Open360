package com.open360.core.sensors

/** One reading from a Bluetooth sports sensor, taken at [elapsedMs] on the phone's monotonic clock. */
data class SensorReading(val elapsedMs: Long, val value: Int)

/**
 * What the sports sensors measured during a recording, each list in time order: heart rate in
 * beats a minute, cadence in revolutions a minute, power in watts.
 */
data class SensorTrack(
    val heartRateBpm: List<SensorReading> = emptyList(),
    val cadenceRpm: List<SensorReading> = emptyList(),
    val powerWatts: List<SensorReading> = emptyList()
) {
    val isEmpty: Boolean get() = heartRateBpm.isEmpty() && cadenceRpm.isEmpty() && powerWatts.isEmpty()

    companion object {
        /**
         * The reading in [readings] (in time order) nearest to [elapsedMs], or null when none is
         * within [maxGapMs]. A sensor that has stopped sending gives no value rather than an old one.
         */
        fun nearest(readings: List<SensorReading>, elapsedMs: Long, maxGapMs: Long): Int? {
            if (readings.isEmpty()) return null
            var lo = 0
            var hi = readings.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (readings[mid].elapsedMs < elapsedMs) lo = mid + 1 else hi = mid
            }
            val best = listOfNotNull(readings.getOrNull(lo - 1), readings[lo])
                .minBy { kotlin.math.abs(it.elapsedMs - elapsedMs) }
            return best.value.takeIf { kotlin.math.abs(best.elapsedMs - elapsedMs) <= maxGapMs }
        }
    }
}
