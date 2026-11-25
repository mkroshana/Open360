package com.open360.core.gps

import com.open360.core.sensors.SensorTrack
import java.io.ByteArrayOutputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Writes a track as an activity file in Garmin's FIT format. Insta360 Studio (5.7.6 and later)
 * imports FIT files as a dashboard data source and reads more from them than from GPX, where it
 * takes only the position, elevation and time.
 *
 * The file holds a file_id, a timer start, one record per second with position, altitude,
 * speed and distance, and heart rate, cadence and power when sensors gave them, a timer stop,
 * and the lap, session and activity summaries. FIT keeps time in whole seconds, so a second fix
 * within the same second is left out.
 */
object FitWriter {

    // FIT counts time in seconds from 1989-12-31 00:00:00 UTC
    private const val FIT_EPOCH_UNIX_S = 631_065_600L

    private const val HEADER_SIZE = 14
    private const val PROTOCOL_VERSION = 0x10
    private const val PROFILE_VERSION = 2100

    // Global message numbers
    private const val MESG_FILE_ID = 0
    private const val MESG_SESSION = 18
    private const val MESG_LAP = 19
    private const val MESG_RECORD = 20
    private const val MESG_EVENT = 21
    private const val MESG_ACTIVITY = 34

    // Base types
    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val SINT32 = 0x85
    private const val UINT32 = 0x86
    private const val STRING = 0x07

    private const val FIELD_TIMESTAMP = 253

    // Values from the FIT profile
    private const val FILE_TYPE_ACTIVITY = 4
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val EVENT_TIMER = 0
    private const val EVENT_SESSION = 8
    private const val EVENT_LAP = 9
    private const val EVENT_ACTIVITY = 26
    private const val EVENT_TYPE_START = 0
    private const val EVENT_TYPE_STOP = 1
    private const val EVENT_TYPE_STOP_ALL = 4
    private const val SPORT_GENERIC = 0
    private const val SPORT_CYCLING = 2
    private const val ACTIVITY_TYPE_MANUAL = 0

    private const val EARTH_RADIUS_M = 6_371_000.0

    // A sensor reading further than this from a record's time is not used for it
    private const val MAX_READING_GAP_MS = 3_000L

    // FIT's values for a field that holds nothing
    private const val INVALID_UINT8 = 0xFF
    private const val INVALID_UINT16 = 0xFFFF

    /**
     * The FIT file for [fixes], which must be in time order. [utcOffsetMs] is the local time
     * zone's offset at the start, for the activity's local timestamp. Each record also takes the
     * [sensors] reading nearest its fix, within 3 s; a kind of reading that is missing altogether
     * is left out of the file.
     */
    fun activity(fixes: List<GpsFix>, utcOffsetMs: Int, sensors: SensorTrack = SensorTrack()): ByteArray {
        require(fixes.isNotEmpty()) { "A FIT activity needs at least one fix" }
        val data = Data()

        val start = fitTime(fixes.first().utcMs)
        data.define(0, MESG_FILE_ID, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16), Field(4, 4, UINT32), Field(8, 8, STRING)))
        data.message(0) { enum(FILE_TYPE_ACTIVITY); u16(MANUFACTURER_DEVELOPMENT); u16(0); u32(start); string("Open360", 8) }

        data.define(1, MESG_EVENT, listOf(Field(FIELD_TIMESTAMP, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)))
        data.message(1) { u32(start); enum(EVENT_TIMER); enum(EVENT_TYPE_START) }

        val hasHeartRate = sensors.heartRateBpm.isNotEmpty()
        val hasCadence = sensors.cadenceRpm.isNotEmpty()
        val hasPower = sensors.powerWatts.isNotEmpty()
        data.define(
            2, MESG_RECORD,
            listOf(
                Field(FIELD_TIMESTAMP, 4, UINT32), Field(0, 4, SINT32), Field(1, 4, SINT32), Field(5, 4, UINT32),
                Field(2, 2, UINT16), Field(6, 2, UINT16), Field(78, 4, UINT32), Field(73, 4, UINT32)
            ) + listOfNotNull(
                Field(3, 1, UINT8).takeIf { hasHeartRate },
                Field(4, 1, UINT8).takeIf { hasCadence },
                Field(7, 2, UINT16).takeIf { hasPower }
            )
        )
        val heartRates = ArrayList<Int>()
        val cadences = ArrayList<Int>()
        val powers = ArrayList<Int>()
        var distanceM = 0.0
        var previous: GpsFix? = null
        var lastTime = -1L
        for (fix in fixes) {
            if (fix.latitude.isFinite() && fix.longitude.isFinite()) {
                previous?.let { distanceM += distanceMeters(it, fix) }
                previous = fix
            }
            val time = fitTime(fix.utcMs)
            if (time <= lastTime) continue
            lastTime = time
            val lat = fix.latitude.takeIf { it.isFinite() } ?: 0.0
            val lon = fix.longitude.takeIf { it.isFinite() } ?: 0.0
            val alt = fix.altitudeMeters.takeIf { it.isFinite() } ?: 0.0
            val spd = fix.speedMps.takeIf { it.isFinite() } ?: 0.0
            data.message(2) {
                u32(time)
                s32(semicircles(lat))
                s32(semicircles(lon))
                u32((distanceM * 100).takeIf { it.isFinite() }?.roundToLong() ?: 0L)
                // altitude and speed in their 16-bit and their enhanced 32-bit fields
                u16(((alt + 500) * 5).roundToLong().coerceIn(0, 0xFFFE))
                u16((spd * 1000).roundToLong().coerceIn(0, 0xFFFE))
                u32(((alt + 500) * 5).roundToLong().coerceAtLeast(0))
                u32((spd * 1000).roundToLong().coerceAtLeast(0))
                if (hasHeartRate) {
                    val bpm = SensorTrack.nearest(sensors.heartRateBpm, fix.elapsedMs, MAX_READING_GAP_MS)?.coerceIn(0, 254)
                    bpm?.let { heartRates += it }
                    u8(bpm ?: INVALID_UINT8)
                }
                if (hasCadence) {
                    val rpm = SensorTrack.nearest(sensors.cadenceRpm, fix.elapsedMs, MAX_READING_GAP_MS)?.coerceIn(0, 254)
                    rpm?.let { cadences += it }
                    u8(rpm ?: INVALID_UINT8)
                }
                if (hasPower) {
                    val watts = SensorTrack.nearest(sensors.powerWatts, fix.elapsedMs, MAX_READING_GAP_MS)?.coerceIn(0, 65534)
                    watts?.let { powers += it }
                    u16(watts ?: INVALID_UINT16)
                }
            }
        }

        val end = fitTime(fixes.last().utcMs)
        val elapsedMs = (fixes.last().utcMs - fixes.first().utcMs).coerceAtLeast(0)
        val distanceCm = (distanceM * 100).roundToLong()
        data.message(1) { u32(end); enum(EVENT_TIMER); enum(EVENT_TYPE_STOP_ALL) }

        val summary = listOf(
            Field(FIELD_TIMESTAMP, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32), Field(8, 4, UINT32),
            Field(9, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)
        )
        data.define(3, MESG_LAP, summary)
        data.message(3) { u32(end); u32(start); u32(elapsedMs); u32(elapsedMs); u32(distanceCm); enum(EVENT_LAP); enum(EVENT_TYPE_STOP) }

        // Averages and maximums of what the records hold: heart rate (16, 17), cadence (18, 19), power (20, 21)
        val sensorSummary = listOfNotNull(
            heartRates.takeIf { it.isNotEmpty() }?.let { Triple(Field(16, 1, UINT8), Field(17, 1, UINT8), it) },
            cadences.takeIf { it.isNotEmpty() }?.let { Triple(Field(18, 1, UINT8), Field(19, 1, UINT8), it) },
            powers.takeIf { it.isNotEmpty() }?.let { Triple(Field(20, 2, UINT16), Field(21, 2, UINT16), it) }
        )
        data.define(
            4, MESG_SESSION,
            summary + listOf(Field(5, 1, ENUM), Field(6, 1, ENUM), Field(25, 2, UINT16), Field(26, 2, UINT16)) +
                sensorSummary.flatMap { listOf(it.first, it.second) }
        )
        data.message(4) {
            u32(end); u32(start); u32(elapsedMs); u32(elapsedMs); u32(distanceCm); enum(EVENT_SESSION); enum(EVENT_TYPE_STOP)
            // Studio offers cadence and power only for a cycling activity
            enum(if (hasCadence || hasPower) SPORT_CYCLING else SPORT_GENERIC); enum(0); u16(0); u16(1)
            for ((averageField, _, values) in sensorSummary) {
                val average = (values.sumOf { it.toLong() }.toDouble() / values.size).roundToLong().toInt()
                if (averageField.size == 1) { u8(average); u8(values.max()) } else { u16(average); u16(values.max()) }
            }
        }

        data.define(
            5, MESG_ACTIVITY,
            listOf(
                Field(FIELD_TIMESTAMP, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16), Field(2, 1, ENUM),
                Field(3, 1, ENUM), Field(4, 1, ENUM), Field(5, 4, UINT32)
            )
        )
        data.message(5) {
            u32(end); u32(elapsedMs); u16(1); enum(ACTIVITY_TYPE_MANUAL); enum(EVENT_ACTIVITY); enum(EVENT_TYPE_STOP)
            u32(end + utcOffsetMs / 1000)
        }

        val body = data.bytes()
        val header = Bytes().apply {
            u8(HEADER_SIZE); u8(PROTOCOL_VERSION); u16(PROFILE_VERSION.toLong()); u32(body.size.toLong())
            raw(".FIT".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        val file = Bytes()
        file.raw(header)
        file.u16(crc(header).toLong())
        file.raw(body)
        val withoutCrc = file.toByteArray()
        file.u16(crc(withoutCrc).toLong())
        return file.toByteArray()
    }

    /** FIT's CRC-16 (CRC-16/ARC), over [bytes]. */
    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            val byte = b.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    private fun fitTime(utcMs: Long): Long = Math.floorDiv(utcMs + 500, 1000L) - FIT_EPOCH_UNIX_S

    private fun semicircles(degrees: Double): Int = (degrees * (2147483648.0 / 180.0)).roundToLong().toInt()

    private fun distanceMeters(a: GpsFix, b: GpsFix): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private class Field(val number: Int, val size: Int, val baseType: Int)

    /** The records of a FIT file: definition messages and the data messages that use them. */
    private class Data {
        private val out = Bytes()

        fun define(localType: Int, globalNumber: Int, fields: List<Field>) {
            out.u8(0x40 or localType)
            out.u8(0) // reserved
            out.u8(0) // little-endian
            out.u16(globalNumber.toLong())
            out.u8(fields.size)
            for (field in fields) {
                out.u8(field.number)
                out.u8(field.size)
                out.u8(field.baseType)
            }
        }

        fun message(localType: Int, values: Bytes.() -> Unit) {
            out.u8(localType)
            out.values()
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Bytes {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) = out.write(value and 0xFF)
        fun enum(value: Int) = u8(value)
        fun u16(value: Int) = u16(value.toLong())
        fun u32(value: Int) = u32(value.toLong())
        fun u16(value: Long) {
            out.write((value and 0xFF).toInt())
            out.write(((value shr 8) and 0xFF).toInt())
        }
        fun u32(value: Long) {
            for (shift in 0 until 32 step 8) out.write(((value shr shift) and 0xFF).toInt())
        }
        fun s32(value: Int) = u32(value.toLong())
        fun string(text: String, size: Int) {
            val bytes = text.toByteArray(Charsets.UTF_8).copyOf(size)
            bytes[size - 1] = 0
            out.write(bytes)
        }
        fun raw(bytes: ByteArray) = out.write(bytes)
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
