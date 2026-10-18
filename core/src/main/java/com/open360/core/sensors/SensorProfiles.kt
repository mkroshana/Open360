package com.open360.core.sensors

import java.util.Locale
import java.util.UUID

/**
 * The standard Bluetooth profiles for sports sensors: Heart Rate, Cycling Speed and Cadence, and
 * Cycling Power, as the Bluetooth SIG publishes them. A Pixel Watch shares heart rate with the
 * Heart Rate profile when Connected Fitness is turned on in its quick settings, and chest straps
 * and bike sensors use the same profiles.
 */
object SensorProfiles {

    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val CSC_SERVICE: UUID = uuid16(0x1816)
    val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)
    val CYCLING_POWER_SERVICE: UUID = uuid16(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2A63)
    val CLIENT_CONFIG: UUID = uuid16(0x2902)

    /** The measurement each service sends, by service. */
    val MEASUREMENTS: Map<UUID, UUID> = mapOf(
        HEART_RATE_SERVICE to HEART_RATE_MEASUREMENT,
        CSC_SERVICE to CSC_MEASUREMENT,
        CYCLING_POWER_SERVICE to CYCLING_POWER_MEASUREMENT
    )

    fun uuid16(short: Int): UUID = UUID.fromString(String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", short))

    /**
     * Heart Rate Measurement (0x2A37): a flags byte, then the heart rate as one byte, or two when
     * flag bit 0 is set. Energy and RR intervals may follow and are not used.
     */
    fun parseHeartRate(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val wide = value[0].toInt() and 0x01 != 0
        return if (wide) {
            if (value.size < 3) null else u16(value, 1)
        } else {
            if (value.size < 2) null else value[1].toInt() and 0xFF
        }
    }

    /** A crank's cumulative revolutions and the time of the last one, in 1/1024 s; both wrap at 65536. */
    data class CrankData(val revolutions: Int, val eventTime1024: Int)

    /**
     * Crank data from a CSC Measurement (0x2A5B): a flags byte, then wheel data (4-byte
     * revolutions, 2-byte time) when bit 0 is set, then crank data (2-byte revolutions, 2-byte
     * time) when bit 1 is set. Null when the sensor sends no crank data.
     */
    fun parseCscCrank(value: ByteArray): CrankData? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        var pos = 1
        if (flags and 0x01 != 0) pos += 6
        if (flags and 0x02 == 0 || value.size < pos + 4) return null
        return CrankData(u16(value, pos), u16(value, pos + 2))
    }

    /** What a Cycling Power Measurement carries that is used: the power, and crank data when present. */
    data class PowerMeasurement(val watts: Int, val crank: CrankData?)

    /**
     * Cycling Power Measurement (0x2A63): 2-byte flags, the instantaneous power (signed 2 bytes),
     * then optional fields in order as the flags say: pedal power balance (1 byte, bit 0),
     * accumulated torque (2, bit 2), wheel revolutions and time (6, bit 4), crank revolutions and
     * time (4, bit 5). Later fields are not used.
     */
    fun parseCyclingPower(value: ByteArray): PowerMeasurement? {
        if (value.size < 4) return null
        val flags = u16(value, 0)
        val watts = (u16(value, 2).toShort()).toInt()
        var pos = 4
        if (flags and 0x01 != 0) pos += 1
        if (flags and 0x04 != 0) pos += 2
        if (flags and 0x10 != 0) pos += 6
        val crank = if (flags and 0x20 != 0 && value.size >= pos + 4) CrankData(u16(value, pos), u16(value, pos + 2)) else null
        return PowerMeasurement(watts.coerceAtLeast(0), crank)
    }

    private fun u16(value: ByteArray, pos: Int): Int = (value[pos].toInt() and 0xFF) or ((value[pos + 1].toInt() and 0xFF) shl 8)
}

/**
 * Works out cadence from successive crank readings, which give revolutions so far and the time of
 * the last one. A reading with no new revolution says nothing new until the crank has been still
 * for [stoppedAfterMs], when the cadence is 0.
 */
class CadenceCalculator(private val stoppedAfterMs: Long = 3_000L) {
    private var previous: SensorProfiles.CrankData? = null
    private var lastRevolutionAtMs = 0L

    /** The cadence in revolutions a minute after [crank], read at [elapsedMs], or null when there is nothing new. */
    fun update(crank: SensorProfiles.CrankData, elapsedMs: Long): Int? {
        val before = previous
        previous = crank
        if (before == null) {
            lastRevolutionAtMs = elapsedMs
            return null
        }
        val revolutions = (crank.revolutions - before.revolutions) and 0xFFFF
        val ticks = (crank.eventTime1024 - before.eventTime1024) and 0xFFFF
        if (revolutions == 0 || ticks == 0) {
            return if (elapsedMs - lastRevolutionAtMs >= stoppedAfterMs) 0 else null
        }
        lastRevolutionAtMs = elapsedMs
        val rpm = (revolutions * 60.0 * 1024.0 / ticks).toInt()
        // More than this is a glitch, such as a sensor that has just been woken
        return rpm.takeIf { it <= MAX_RPM }
    }

    private companion object {
        const val MAX_RPM = 250
    }
}
