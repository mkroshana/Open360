package com.open360.core.gps

import com.open360.core.sensors.SensorReading
import com.open360.core.sensors.SensorTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitWriterTest {

    // 2026-10-14 14:43:02 UTC
    private val startMs = 1792075382000L

    private fun fix(secondsIn: Double, latitude: Double, longitude: Double, altitude: Double, speed: Double) = GpsFix(
        elapsedMs = (secondsIn * 1000).toLong(),
        utcMs = startMs + (secondsIn * 1000).toLong(),
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitude,
        speedMps = speed,
        bearingDegrees = 0.0
    )

    /** One decoded message: its global number and its fields by number. */
    private class Message(val global: Int, val fields: Map<Int, Long>)

    /** A small FIT reader for checking what the writer produced. */
    private fun decode(file: ByteArray): List<Message> {
        val buf = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.get(0).toInt()
        assertEquals(14, headerSize)
        val dataSize = buf.getInt(4)
        assertEquals(".FIT", String(file, 8, 4, Charsets.US_ASCII))
        assertEquals(headerSize + dataSize + 2, file.size)
        // A CRC run over data that ends in its own CRC comes out as zero
        assertEquals(0, FitWriter.crc(file.copyOfRange(0, headerSize)))
        assertEquals(0, FitWriter.crc(file))

        val definitions = HashMap<Int, Pair<Int, List<Triple<Int, Int, Int>>>>()
        val messages = ArrayList<Message>()
        var pos = headerSize
        while (pos < headerSize + dataSize) {
            val header = file[pos++].toInt() and 0xFF
            val local = header and 0x0F
            if (header and 0x40 != 0) {
                pos += 2 // reserved, architecture
                val global = buf.getShort(pos).toInt() and 0xFFFF
                pos += 2
                val count = file[pos++].toInt() and 0xFF
                val fields = (0 until count).map {
                    Triple(file[pos + it * 3].toInt() and 0xFF, file[pos + it * 3 + 1].toInt() and 0xFF, file[pos + it * 3 + 2].toInt() and 0xFF)
                }
                pos += count * 3
                definitions[local] = global to fields
            } else {
                val (global, fields) = definitions.getValue(local)
                val values = HashMap<Int, Long>()
                for ((number, size, baseType) in fields) {
                    var value = 0L
                    for (i in 0 until minOf(size, 8)) value = value or ((file[pos + i].toLong() and 0xFF) shl (8 * i))
                    if (baseType == 0x85) value = value.toInt().toLong()
                    values[number] = value
                    pos += size
                }
                messages += Message(global, values)
            }
        }
        return messages
    }

    @Test
    fun testCrcMatchesCrc16Arc() {
        assertEquals(0xBB3D, FitWriter.crc("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun testActivityHoldsOneRecordASecondWithScaledValues() {
        val fixes = listOf(
            fix(0.0, 6.6657512, 79.9369134, 12.3, 13.06),
            // 0.001 degrees of latitude north, about 111.2 m
            fix(1.0, 6.6667512, 79.9369134, 14.0, 13.5),
            // in the same second as the one before, so it is left out of the records
            fix(1.4, 6.6668512, 79.9369134, 14.1, 13.6),
            fix(2.0, 6.6677512, 79.9369134, 15.0, 14.0)
        )
        val messages = decode(FitWriter.activity(fixes, utcOffsetMs = 19_800_000))

        val fileId = messages.first { it.global == 0 }
        assertEquals(4L, fileId.fields[0])
        assertEquals(255L, fileId.fields[1])
        val startFit = 1792075382L - 631065600L
        assertEquals(startFit, fileId.fields[4])

        val records = messages.filter { it.global == 20 }
        assertEquals(3, records.size)
        assertEquals(listOf(startFit, startFit + 1, startFit + 2), records.map { it.fields[253] })

        val first = records[0].fields
        assertEquals(6.6657512, first.getValue(0) * 180.0 / 2147483648.0, 1e-7)
        assertEquals(79.9369134, first.getValue(1) * 180.0 / 2147483648.0, 1e-7)
        assertEquals(0L, first[5])
        // altitude as (m + 500) * 5, speed in mm/s, in both the 16-bit and enhanced fields
        assertEquals(Math.round((12.3 + 500) * 5), first[2])
        assertEquals(Math.round((12.3 + 500) * 5), first[78])
        assertEquals(13060L, first[6])
        assertEquals(13060L, first[73])

        // Distance in cm adds up along the track, including the fix that was left out
        val distanceCm = records.last().fields.getValue(5)
        assertEquals(22239.0, distanceCm.toDouble(), 30.0)

        // A timer start and a stop around the records
        val events = messages.filter { it.global == 21 }
        assertEquals(listOf(0L, 4L), events.map { it.fields[1] })

        val session = messages.single { it.global == 18 }.fields
        // no sensors: a generic activity
        assertEquals(0L, session[5])
        assertEquals(distanceCm, session[9])
        assertEquals(2000L, session[7])
        assertEquals(startFit, session[2])
        assertEquals(1L, session[26])

        val activity = messages.single { it.global == 34 }.fields
        assertEquals(1L, activity[1])
        assertEquals(startFit + 2 + 19_800L, activity[5])
        assertTrue(messages.any { it.global == 19 })
    }

    @Test
    fun testSensorReadingsGoIntoTheRecordsAndTheSession() {
        // Six fixes a second apart; the heart rate stops after 1.1 s, so the last record is 3.9 s
        // from a reading and gets no value
        val fixes = (0..5).map { fix(it.toDouble(), 6.6657512 + it * 0.0001, 79.9369134, 10.0, 8.0) }
        val sensors = SensorTrack(
            heartRateBpm = listOf(SensorReading(0, 90), SensorReading(1_100, 100)),
            powerWatts = (0..5).map { SensorReading(it * 1_000L + 200, 150 + it * 10) }
        )
        val messages = decode(FitWriter.activity(fixes, utcOffsetMs = 0, sensors = sensors))

        val records = messages.filter { it.global == 20 }.map { it.fields }
        assertEquals(listOf(90L, 100L, 100L, 100L, 100L, 255L), records.map { it[3] })
        assertEquals(listOf(150L, 160L, 170L, 180L, 190L, 200L), records.map { it[7] })
        // no cadence readings at all: the field is not in the file
        assertTrue(records.none { it.containsKey(4) })

        val session = messages.single { it.global == 18 }.fields
        // heart rate average of the five records that have one, and its maximum
        assertEquals(98L, session[16])
        assertEquals(100L, session[17])
        assertEquals(175L, session[20])
        assertEquals(200L, session[21])
        assertTrue(!session.containsKey(18))
        // with power in the file the activity is cycling, so Studio offers cadence and power
        assertEquals(2L, session[5])
    }

    @Test
    fun testNearestReadingIsUsedOnlyWithinTheGap() {
        val readings = listOf(SensorReading(1_000, 60), SensorReading(2_000, 70), SensorReading(10_000, 80))
        assertEquals(60, SensorTrack.nearest(readings, 1_400, 3_000))
        assertEquals(70, SensorTrack.nearest(readings, 1_600, 3_000))
        assertEquals(70, SensorTrack.nearest(readings, 4_900, 3_000))
        assertEquals(null, SensorTrack.nearest(readings, 6_000, 3_000))
        assertEquals(80, SensorTrack.nearest(readings, 12_000, 3_000))
        assertEquals(null, SensorTrack.nearest(emptyList(), 0, 3_000))
    }

    @Test
    fun testSouthAndWestAreNegativeSemicircles() {
        val messages = decode(FitWriter.activity(listOf(fix(0.0, -33.8688, -151.2093, 5.0, 0.0)), utcOffsetMs = 0))
        val record = messages.single { it.global == 20 }.fields
        assertEquals(-33.8688, record.getValue(0) * 180.0 / 2147483648.0, 1e-7)
        assertEquals(-151.2093, record.getValue(1) * 180.0 / 2147483648.0, 1e-7)
    }
}
