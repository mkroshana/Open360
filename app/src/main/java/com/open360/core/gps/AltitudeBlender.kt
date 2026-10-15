package com.open360.core.gps

import kotlin.math.pow

/**
 * Blends the barometer's altitude with GPS altitude.
 *
 * GPS altitude wanders by several metres from one fix to the next, which shows up in Insta360
 * Studio as altitude and gradient that jump about. Air pressure follows height closely from one
 * second to the next but drifts with the weather and has no fixed zero. So the altitude given out
 * is the pressure altitude plus an offset, and the offset follows the difference between GPS and
 * pressure altitude slowly, over about [timeConstantMs]: the short-term shape comes from the
 * barometer and the level from GPS.
 *
 * Wind against the phone at speed changes the pressure it reads too. That also reaches the
 * altitude, and fades over the same time as the offset settles.
 */
class AltitudeBlender(
    private val timeConstantMs: Long = 100_000L,
    // A barometer reading older than this is not used
    private val maxPressureAgeMs: Long = 2_000L,
    // Pressure readings are averaged over about this long
    private val pressureSmoothingMs: Long = 500L,
    // GPS altitude this uncertain is not used to correct the level
    private val maxVerticalAccuracyM: Float = 15f
) {
    private var pressureHpa: Double? = null
    private var pressureAtMs = 0L
    private var offsetM: Double? = null
    private var offsetAtMs = 0L

    /** Whether the last altitude given out came from the barometer. */
    var usingBarometer = false
        private set

    /** A barometer reading in hPa, taken at [elapsedMs] on the monotonic clock. */
    fun addPressure(hPa: Float, elapsedMs: Long) {
        val previous = pressureHpa
        if (previous == null || elapsedMs - pressureAtMs > maxPressureAgeMs) {
            pressureHpa = hPa.toDouble()
        } else {
            val dt = (elapsedMs - pressureAtMs).coerceAtLeast(0L).toDouble()
            pressureHpa = previous + (hPa - previous) * dt / (pressureSmoothingMs + dt)
        }
        pressureAtMs = elapsedMs
    }

    /**
     * The altitude to use for a fix taken at [elapsedMs] whose GPS altitude is [gpsAltitudeM].
     * Without a recent barometer reading this is the GPS altitude, and the level is worked out
     * afresh when readings come back.
     */
    fun blend(gpsAltitudeM: Double, elapsedMs: Long, verticalAccuracyM: Float? = null): Double {
        val pressure = pressureHpa
        if (pressure == null || elapsedMs - pressureAtMs > maxPressureAgeMs) {
            offsetM = null
            usingBarometer = false
            return gpsAltitudeM
        }
        val pressureAltitude = pressureAltitudeM(pressure)
        val difference = gpsAltitudeM - pressureAltitude
        val offset = offsetM
        if (offset == null) {
            offsetM = difference
            offsetAtMs = elapsedMs
        } else if (verticalAccuracyM == null || verticalAccuracyM <= maxVerticalAccuracyM) {
            val dt = (elapsedMs - offsetAtMs).coerceAtLeast(0L).toDouble()
            offsetM = offset + (difference - offset) * dt / (timeConstantMs + dt)
            offsetAtMs = elapsedMs
        }
        usingBarometer = true
        return pressureAltitude + offsetM!!
    }

    companion object {
        /** Height in the standard atmosphere for [hPa], the formula Android's SensorManager uses. */
        fun pressureAltitudeM(hPa: Double): Double = 44330.0 * (1.0 - (hPa / 1013.25).pow(1.0 / 5.255))
    }
}
