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
    fun testGpsRecordLayout() {
        // 2026-01-01 12:00:00.250 UTC, 37.774929 N, 122.419416 W
        val record = Go2BleProtocol.buildGpsRecord(
            timestampMs = 1767268800250L,
            latitude = 37.774929,
            longitude = -122.419416,
            speedMps = 4.5,
            bearingDegrees = 271.0,
            altitudeMeters = 15.5
        )
        assertEquals(53, record.size)

        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1767268800L, buf.long)
        assertEquals(250, buf.short.toInt())
        assertEquals('A'.code.toByte(), buf.get())
        assertEquals(37.774929, buf.double, 0.0)
        assertEquals('N'.code.toByte(), buf.get())
        assertEquals(122.419416, buf.double, 0.0)
        assertEquals('W'.code.toByte(), buf.get())
        assertEquals(4.5, buf.double, 0.0)
        assertEquals(271.0, buf.double, 0.0)
        assertEquals(15.5, buf.double, 0.0)
    }

    @Test
    fun testGpsRecordWithoutFixIsVoid() {
        val record = Go2BleProtocol.buildGpsRecord(0L, -6.5, 79.9, 0.0, 0.0, 0.0, hasFix = false)
        assertEquals('V'.code.toByte(), record[10])
        assertEquals('S'.code.toByte(), record[19])
        assertEquals('E'.code.toByte(), record[28])
    }

    @Test
    fun testUploadGpsPayloadWrapsRecordsAsProtobufBytesField() {
        val record = Go2BleProtocol.buildGpsRecord(1767268800000L, 6.5, 79.9, 1.0, 90.0, 12.0)

        // One record: tag 0x0A, single-byte length 53
        val single = Go2BleProtocol.buildUploadGpsPayload(listOf(record))
        assertEquals(2 + 53, single.size)
        assertEquals(0x0A.toByte(), single[0])
        assertEquals(53.toByte(), single[1])
        assertTrue(single.copyOfRange(2, single.size).contentEquals(record))

        // Four records: 212 bytes needs a two-byte varint length (D4 01)
        val four = Go2BleProtocol.buildUploadGpsPayload(List(4) { record })
        assertEquals(3 + 212, four.size)
        assertEquals(0x0A.toByte(), four[0])
        assertEquals(0xD4.toByte(), four[1])
        assertEquals(0x01.toByte(), four[2])
    }

    @Test
    fun testUploadGpsPacketFitsOneWriteAtNegotiatedMtu() {
        assertEquals(4, Go2BleProtocol.gpsRecordsPerPacket(247))
        assertEquals(1, Go2BleProtocol.gpsRecordsPerPacket(23))

        val record = Go2BleProtocol.buildGpsRecord(1767268800000L, 6.5, 79.9, 1.0, 90.0, 12.0)
        val payload = Go2BleProtocol.buildUploadGpsPayload(List(4) { record })
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_UPLOAD_GPS, sequence = 42, payload = payload)
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        assertTrue(packet.size <= 247 - 3)

        val decodedInner = Go2BleProtocol.decodeInnerMessage(Go2BleProtocol.decodePacket(packet)!!.innerData)
        assertNotNull(decodedInner)
        assertEquals(Go2BleProtocol.CMD_UPLOAD_GPS, decodedInner!!.commandCode)
        assertEquals(42, decodedInner.sequence)
        assertTrue(decodedInner.payload.contentEquals(payload))
    }

    @Test
    fun testRecordingCaptureStates() {
        // Seen on a GO 3S: 1 when a video starts, 0 when it stops
        assertTrue(Go2BleProtocol.isRecordingCaptureState(1))
        assertEquals(false, Go2BleProtocol.isRecordingCaptureState(0))
        // TIMELAPSE_CAPTURE is a recording, SINGLE_SHOOTING is a photo
        assertTrue(Go2BleProtocol.isRecordingCaptureState(2))
        assertEquals(false, Go2BleProtocol.isRecordingCaptureState(4))
    }

    @Test
    fun testBatteryUpdateNotificationReadsNestedLevel() {
        // Captured from a GO 3S: battery_status { power_type, battery_level = 100 }
        val onBattery = byteArrayOf(0x0A, 0x04, 0x08, 0x00, 0x10, 0x64)
        val onAdapter = byteArrayOf(0x0A, 0x04, 0x08, 0x01, 0x10, 0x64)

        assertEquals(Go2BleProtocol.BatteryStatus(100, onExternalPower = false), Go2BleProtocol.parseBatteryUpdateNotification(onBattery))
        assertEquals(Go2BleProtocol.BatteryStatus(100, onExternalPower = true), Go2BleProtocol.parseBatteryUpdateNotification(onAdapter))

        // A payload without the nested status is not a battery reading
        assertNull(Go2BleProtocol.parseBatteryUpdateNotification(byteArrayOf(0x08, 0x64, 0x10, 0x00)))
    }

    @Test
    fun testCaptureStepFromCameraNotification() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Captured from a GO 3S around one video: start accepted, then stopping
        assertEquals(Go2BleProtocol.CAPTURE_STEP_VIDEO_STARTING, Go2BleProtocol.parseCaptureStep(hex("080218f0e70220012805")))
        assertEquals(Go2BleProtocol.CAPTURE_STEP_STOPPING, Go2BleProtocol.parseCaptureStep(hex("080518b6e30320012802")))
        assertNull(Go2BleProtocol.parseCaptureStep(ByteArray(0)))
    }

    @Test
    fun testVideoFilePathFromStopCaptureReply() {
        // Captured from a GO 3S after a 41 s clip
        val reply = "0a360a2d2f4443494d2f43616d65726130312f5649445f32303236313031325f3136333034395f30305f3036362e6d7034108080800a1829"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("/DCIM/Camera01/VID_20261012_163049_00_066.mp4", Go2BleProtocol.parseCapturedFilePath(reply))

        // The battery query reply has no file in it
        assertNull(Go2BleProtocol.parseCapturedFilePath(byteArrayOf(0x08, 0x64, 0x10, 0x01)))

        // The same reply gives the clip's length in whole seconds
        assertEquals(41, Go2BleProtocol.parseCapturedFileSeconds(reply))
        assertNull(Go2BleProtocol.parseCapturedFileSeconds(byteArrayOf(0x08, 0x64, 0x10, 0x01)))
    }

    @Test
    fun testRecordingModeFromStartNoticeAndCaptureStatus() {
        // Start notices captured from a GO 3S: started from the app (Video), then with the
        // camera's button in PRO Video mode
        assertEquals(1, Go2BleProtocol.parseCaptureStepState(hex("080218a5951b20012805")))
        assertEquals(17, Go2BleProtocol.parseCaptureStepState(hex("080218aca70120112802")))

        // Capture status for the same two modes, then for the camera going idle
        assertEquals(1, Go2BleProtocol.parseCaptureState(hex("0801100020b817280530880e3800500058036001")))
        assertEquals(17, Go2BleProtocol.parseCaptureState(hex("0811100020b817280530880e3800500058036001")))
        assertEquals(0, Go2BleProtocol.parseCaptureState(hex("080010002000280030880e3800500058036001")))
        assertEquals(0, Go2BleProtocol.parseCaptureState(ByteArray(0)))
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun testCaptureTimeLeftFromCameraNotification() {
        // Captured from a GO 3S: in Video mode, then in PRO Video mode on the same storage
        assertEquals(6510, Go2BleProtocol.parseCaptureTimeLeft(hex("08ee321000")))
        assertEquals(4796, Go2BleProtocol.parseCaptureTimeLeft(hex("08bc251000")))
        assertNull(Go2BleProtocol.parseCaptureTimeLeft(ByteArray(0)))
    }

    @Test
    fun testStorageUpdateCardStates() {
        // Captured from a GO 3S with working storage
        assertEquals(Go2BleProtocol.CardState.OK, Go2BleProtocol.parseStorageUpdate(hex("0800")))
        assertEquals(Go2BleProtocol.CardState.NO_CARD, Go2BleProtocol.parseStorageUpdate(hex("0801")))
        assertEquals(Go2BleProtocol.CardState.FULL, Go2BleProtocol.parseStorageUpdate(hex("0802")))
        assertEquals(Go2BleProtocol.CardState.WRITE_PROTECTED, Go2BleProtocol.parseStorageUpdate(hex("0804")))
        assertEquals(Go2BleProtocol.CardState.ERROR, Go2BleProtocol.parseStorageUpdate(hex("0809")))
        // A message that leaves the state out carries the default
        assertEquals(Go2BleProtocol.CardState.OK, Go2BleProtocol.parseStorageUpdate(ByteArray(0)))
    }

    @Test
    fun testCaptureStatusReply() {
        // Built from the published definitions; not captured from a camera.
        // PRO Video (17) that has been recording for 75 s
        val recording = Go2BleProtocol.parseCaptureStatusReply(hex("0a040811104b"))
        assertEquals(17, recording?.captureState)
        assertEquals(75, recording?.captureSeconds)
        assertTrue(Go2BleProtocol.isRecordingCaptureState(recording!!.captureState))

        // Idle: the status is there but every field is at its default
        val idle = Go2BleProtocol.parseCaptureStatusReply(hex("0a00"))
        assertEquals(0, idle?.captureState)
        assertEquals(0, idle?.captureSeconds)

        // A reply with no status in it, such as the empty OK that answers a GPS upload
        assertNull(Go2BleProtocol.parseCaptureStatusReply(ByteArray(0)))
    }

    @Test
    fun testShutdownAndTemperatureWarnings() {
        // Built from the published definitions; not captured from a camera
        assertEquals(Go2BleProtocol.ShutdownReason.BATTERY_EMPTY, Go2BleProtocol.parseShutdown(hex("0800")))
        assertEquals(Go2BleProtocol.ShutdownReason.OVERHEATED, Go2BleProtocol.parseShutdown(hex("0801")))
        // The battery reason is the default, so it may be left out
        assertEquals(Go2BleProtocol.ShutdownReason.BATTERY_EMPTY, Go2BleProtocol.parseShutdown(ByteArray(0)))

        // TempState: low, alert, middle, high, each with a temperature value in field 2
        assertEquals(false, Go2BleProtocol.parseTemperatureIsHigh(hex("08001028")))
        assertEquals(true, Go2BleProtocol.parseTemperatureIsHigh(hex("08011046")))
        assertEquals(false, Go2BleProtocol.parseTemperatureIsHigh(hex("08021032")))
        assertEquals(true, Go2BleProtocol.parseTemperatureIsHigh(hex("0803103c")))
        assertEquals(false, Go2BleProtocol.parseTemperatureIsHigh(ByteArray(0)))
    }

    @Test
    fun testChargeBoxStatusReadsThePodBattery() {
        // Captured from a GO 3S in its Action Pod: pod at 69% on its own battery
        val onBattery = Go2BleProtocol.parseChargeBoxStatus(hex("0801120408001045180120022802"))
        assertNotNull(onBattery)
        assertTrue(onBattery!!.connected)
        assertEquals(69, onBattery.battery?.levelPercent)
        assertEquals(false, onBattery.battery?.onExternalPower)

        // The pod at 65% just after being plugged into a computer
        val pluggedIn = Go2BleProtocol.parseChargeBoxStatus(hex("0801120408011041180120022802"))
        assertEquals(65, pluggedIn?.battery?.levelPercent)
        assertEquals(true, pluggedIn?.battery?.onExternalPower)

        // The pod gone: its battery is sent as zero and is not a reading
        val gone = Go2BleProtocol.parseChargeBoxStatus(hex("0800120408001000180020022801"))
        assertEquals(false, gone?.connected)
        assertNull(gone?.battery)

        assertNull(Go2BleProtocol.parseChargeBoxStatus(ByteArray(0)))
    }

    @Test
    fun testKeepAlivePacketCarriesWifiKeepAliveBytes() {
        val decoded = Go2BleProtocol.decodePacket(Go2BleProtocol.buildKeepAlivePacket())
        assertNotNull(decoded)
        assertEquals("07000000050000", decoded!!.innerData.joinToString("") { "%02x".format(it) })
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

    @Test
    fun testParseCameraStatusPacketSerialHandshake() {
        // FE EF FE 07 00 06 33 34 55 51 47 35 -> "34UQG5"
        val raw = byteArrayOf(
            0xFE.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x07, 0x00, 0x06,
            '3'.code.toByte(), '4'.code.toByte(), 'U'.code.toByte(),
            'Q'.code.toByte(), 'G'.code.toByte(), '5'.code.toByte()
        )
        val packet = Go2BleProtocol.parseCameraStatusPacket(raw)
        assertTrue(packet is Go2BleProtocol.CameraStatusPacket.SerialHandshake)
        assertEquals("34UQG5", (packet as Go2BleProtocol.CameraStatusPacket.SerialHandshake).serial)
    }

    @Test
    fun testParseCameraStatusPacketRecordingTimer() {
        // FE EF FE 10 80 0D 01 0E 46 01 2E 30 30 3A 30 30 3A 30 35 -> ".00:00:05"
        val raw = byteArrayOf(
            0xFE.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x10, 0x80.toByte(), 0x0D,
            0x01, 0x0E, 0x46, 0x01,
            '.'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), ':'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), ':'.code.toByte(), '0'.code.toByte(), '5'.code.toByte()
        )
        val packet = Go2BleProtocol.parseCameraStatusPacket(raw)
        assertTrue(packet is Go2BleProtocol.CameraStatusPacket.DisplayString)
        val display = packet as Go2BleProtocol.CameraStatusPacket.DisplayString
        assertTrue(display.isRecording)
        assertEquals("00:00:05", display.text)
        assertEquals(5L, display.durationSeconds)
        assertEquals(false, display.isModeString)
    }

    @Test
    fun testParseCameraStatusPacketIdleMode() {
        // FE EF FE 10 81 0C 01 1C 5E 00 34 4B 7C 33 30 7C 55 57 -> "4K|30|UW"
        val raw = byteArrayOf(
            0xFE.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x10, 0x81.toByte(), 0x0C,
            0x01, 0x1C, 0x5E, 0x00,
            '4'.code.toByte(), 'K'.code.toByte(), '|'.code.toByte(), '3'.code.toByte(),
            '0'.code.toByte(), '|'.code.toByte(), 'U'.code.toByte(), 'W'.code.toByte()
        )
        val packet = Go2BleProtocol.parseCameraStatusPacket(raw)
        assertTrue(packet is Go2BleProtocol.CameraStatusPacket.DisplayString)
        val display = packet as Go2BleProtocol.CameraStatusPacket.DisplayString
        assertEquals(false, display.isRecording)
        assertEquals("4K|30|UW", display.text)
        assertEquals(null, display.durationSeconds)
        assertEquals(true, display.isModeString)
    }
}
