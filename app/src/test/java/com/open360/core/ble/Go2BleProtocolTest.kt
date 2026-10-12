package com.open360.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Go2BleProtocolTest {

    @Test
    fun testCrc16ModbusStandardVector() {
        // "123456789" CRC-16/MODBUS is 0x4B37
        val testString = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = BleCrc16.compute(testString)
        assertEquals(0x4B37, crc)
    }

    @Test
    fun testEncodeAndDecodeSyncPacket() {
        val syncPkt = Go2BleProtocol.buildSyncResponsePacket()
        // Total size = 5 header + 7 payload + 2 CRC = 14 bytes
        assertEquals(14, syncPkt.size)
        assertEquals(Go2BleProtocol.MARKER, syncPkt[0])
        assertEquals(Go2BleProtocol.SUBTYPE_SYNC, syncPkt[2])

        val decoded = Go2BleProtocol.decodePacket(syncPkt)
        assertNotNull(decoded)
        assertTrue(decoded!!.isSync)
        assertEquals(7, decoded.innerData.size)
        assertTrue(decoded.innerData.all { it == 0.toByte() })
    }

    @Test
    fun testEncodeAndDecodeTakePhotoCommand() {
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_TAKE_PICTURE, sequence = 1)
        assertEquals(16, inner.size)

        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        // 5 + 16 + 2 = 23 bytes
        assertEquals(23, packet.size)

        val decodedEnvelope = Go2BleProtocol.decodePacket(packet)
        assertNotNull(decodedEnvelope)
        assertTrue(decodedEnvelope!!.isMessage)

        val decodedInner = Go2BleProtocol.decodeInnerMessage(decodedEnvelope.innerData)
        assertNotNull(decodedInner)
        assertEquals(Go2BleProtocol.CMD_TAKE_PICTURE, decodedInner!!.commandCode)
        assertEquals(1, decodedInner.sequence)
        assertEquals(0, decodedInner.payload.size)
    }

    @Test
    fun testGpsPayloadEncodingAndDecoding() {
        val lat = 37.774929
        val lon = -122.419416
        val alt = 15.5

        val payload = Go2BleProtocol.buildGpsPayload(lat, lon, alt)
        assertEquals(24, payload.size)

        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_UPLOAD_GPS, sequence = 42, payload = payload)
        assertEquals(16 + 24, inner.size)

        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        assertEquals(5 + 40 + 2, packet.size)

        val decodedEnvelope = Go2BleProtocol.decodePacket(packet)
        assertNotNull(decodedEnvelope)

        val decodedInner = Go2BleProtocol.decodeInnerMessage(decodedEnvelope!!.innerData)
        assertNotNull(decodedInner)
        assertEquals(Go2BleProtocol.CMD_UPLOAD_GPS, decodedInner!!.commandCode)
        assertEquals(42, decodedInner.sequence)
        assertEquals(24, decodedInner.payload.size)

        val buf = ByteBuffer.wrap(decodedInner.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(lat, buf.double, 0.000001)
        assertEquals(lon, buf.double, 0.000001)
        assertEquals(alt, buf.double, 0.01)
    }

    @Test
    fun testCorruptedCrcFailsDecoding() {
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_START_CAPTURE, sequence = 5)
        val packet = Go2BleProtocol.encodeMessagePacket(inner)

        // Corrupt a byte in the packet
        packet[packet.size - 1] = (packet[packet.size - 1].toInt() xor 0xFF).toByte()

        val decoded = Go2BleProtocol.decodePacket(packet)
        assertNull(decoded)
    }

    @Test
    fun testRemoteButtonCommandShutter() {
        val shutterCmd = Go2BleProtocol.buildRemoteButtonCommand(
            button = Go2BleProtocol.REMOTE_BTN_SHUTTER,
            state = Go2BleProtocol.REMOTE_STATE_CLICK,
            sequenceNumber = 4
        )
        assertEquals(9, shutterCmd.size)
        assertEquals(0xFC.toByte(), shutterCmd[0])
        assertEquals(0xEF.toByte(), shutterCmd[1])
        assertEquals(0xFE.toByte(), shutterCmd[2])
        assertEquals(0x86.toByte(), shutterCmd[3])
        assertEquals(0x04.toByte(), shutterCmd[4])
        assertEquals(0x03.toByte(), shutterCmd[5])
        assertEquals(0x01.toByte(), shutterCmd[6])
        assertEquals(0x02.toByte(), shutterCmd[7])
        assertEquals(0x00.toByte(), shutterCmd[8])
    }

    @Test
    fun testGpsNmeaFrameMatchesSpecificationCapture() {
        // Test against the synthetic capture vector from reverse engineering:
        // Lat: 40°00.0000' N (40.0)
        // Lon: 74°00.0000' W (-74.0)
        // UTC: 12:00:00.000 on 2026-01-01
        // Speed: 0.00 knots, Course: 0.00 deg
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(java.util.Calendar.YEAR, 2026)
            set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val frame = Go2BleProtocol.buildGpsNmeaFrame(
            latitude = 40.0,
            longitude = -74.0,
            altitudeMeters = 0.0,
            speedKnots = 0.0,
            bearingDegrees = 0.0,
            timestampMs = cal.timeInMillis,
            hasFix = true
        )

        val expectedHex = "FCEFFE8300522C32362E372C072C24474E524D432C3132303030302E3030302C412C343030302E303030302C4E2C2D373430302E303030302C452C302E30302C302E30302C3031303132362C302E302C572C412C562A3645"
        val actualHex = frame.joinToString("") { "%02X".format(it) }

        assertEquals(expectedHex, actualHex)
    }
}
