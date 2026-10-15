package com.open360.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketFramerTest {

    private fun String.hexToByteArray(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun testSyncPacketMatchesCapturedWireBytes() {
        val syncPkt = PacketFramer.buildSyncPacket()
        val expectedHex = "1100000006000073794e63654e64696e53"
        assertEquals(expectedHex, syncPkt.toHexString())
    }

    @Test
    fun testKeepAlivePacketMatchesCapturedWireBytes() {
        val keepAlivePkt = PacketFramer.buildKeepAlivePacket()
        val expectedHex = "07000000050000"
        assertEquals(expectedHex, keepAlivePkt.toHexString())
    }

    @Test
    fun testUnpackStreamsHandlesConcatenatedPackets() {
        val stream = "1100000006000073794e63654e64696e5307000000050000".hexToByteArray()
        val (packets, remaining) = PacketFramer.unpackStreams(stream)

        assertEquals(2, packets.size)
        assertEquals("06000073794e63654e64696e53", packets[0].toHexString())
        assertEquals("050000", packets[1].toHexString())
        assertEquals(0, remaining.size)
    }

    @Test
    fun testParseMessageReadsCodeSequenceAndBody() {
        // A file list request as sent, read back
        val (sent, _) = PacketFramer.unpackStreams(PacketFramer.buildCommandPacket(13, 0x010203, "0802".hexToByteArray()))
        val command = PacketFramer.parseMessage(sent[0])
        assertEquals(13, command?.messageCode)
        assertEquals(0x010203, command?.sequenceNumber)
        assertEquals("0802", command?.body?.toHexString())

        // A notification from a GO 3S: code 0x201e, with the camera's own sequence number
        val (received, _) = PacketFramer.unpackStreams("1e0000000400001e2002ff8a43f40301080112040800103e180120022802".hexToByteArray())
        val notification = PacketFramer.parseMessage(received[0])
        assertEquals(0x201E, notification?.messageCode)
        assertEquals(0x438AFF, notification?.sequenceNumber)
        assertEquals("080112040800103e180120022802", notification?.body?.toHexString())

        // Keep-alive packets are not messages
        assertEquals(null, PacketFramer.parseMessage("050000".hexToByteArray()))
    }

    @Test
    fun testParseLiveCameraBatteryNotification62Percent() {
        // Raw packet received live from Insta360 GO 3S:
        val rawWireHex = "1e0000000400001e2002ff8a43f40301080112040800103e180120022802"
        val rawBytes = rawWireHex.hexToByteArray()
        val (packets, _) = PacketFramer.unpackStreams(rawBytes)

        assertEquals(1, packets.size)
        val notif = NotificationParser.parse(packets[0])
        assertNotNull(notif)
        assertTrue(notif is CameraNotification.BatteryUpdate)
        val battery = notif as CameraNotification.BatteryUpdate
        assertEquals(62, battery.percentage)
        assertTrue(battery.isCharging)
    }

    @Test
    fun testParseLiveCameraBatteryNotification61Percent() {
        // Next raw packet received live from Insta360 GO 3S:
        val rawWireHex = "1e0000000400001e2002ff8a43f40301080112040800103d180120022802"
        val rawBytes = rawWireHex.hexToByteArray()
        val (packets, _) = PacketFramer.unpackStreams(rawBytes)

        assertEquals(1, packets.size)
        val notif = NotificationParser.parse(packets[0])
        assertNotNull(notif)
        assertTrue(notif is CameraNotification.BatteryUpdate)
        val battery = notif as CameraNotification.BatteryUpdate
        assertEquals(61, battery.percentage)
        assertTrue(battery.isCharging)
    }
}
