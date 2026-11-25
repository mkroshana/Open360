package com.open360.core.protocol

import com.open360.core.protocol.LiveStreamProtocol.PreviewResolution
import com.open360.core.protocol.LiveStreamProtocol.StartLiveStreamConfig
import com.open360.core.protocol.LiveStreamProtocol.StreamKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiveStreamProtocolTest {

    private fun decodeVarintFields(bytes: ByteArray): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < bytes.size) {
            val (tag, nextI) = readVarint(bytes, i)
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            assertEquals("Expected varint wireType 0 for field $fieldNumber", 0, wireType)
            val (value, afterVal) = readVarint(bytes, nextI)
            result.add(fieldNumber to value)
            i = afterVal
        }
        return result
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var index = start
        while (true) {
            val b = bytes[index].toInt() and 0xFF
            index++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result to index
    }

    @Test
    fun commandCodesMatchVerifiedProtocol() {
        assertEquals(0x01, LiveStreamProtocol.CMD_START_LIVE_STREAM)
        assertEquals(0x02, LiveStreamProtocol.CMD_STOP_LIVE_STREAM)
        assertEquals(0x69, LiveStreamProtocol.CMD_REQUEST_STREAMING_IFRAME)
        assertEquals(0x76, LiveStreamProtocol.CMD_SET_ACCESS_CAMERA_FILE_STATE)
        assertEquals(0x201B, LiveStreamProtocol.NOTIF_LIVEVIEW_BEGIN_ROTATE)
        assertEquals(0x2025, LiveStreamProtocol.NOTIF_APP_LIVEVIEW_STATUS)
        assertEquals(93, LiveStreamProtocol.OPTION_CAMERA_POSTURE)
        assertEquals(0, LiveStreamProtocol.encodeStopLiveStream().size)
        assertEquals(0, LiveStreamProtocol.encodeRequestIframe().size)
    }

    @Test
    fun cameraPostureToDegreesMapsKnownPosturesAndPreservesUpDown() {
        assertEquals(0, LiveStreamProtocol.cameraPostureToDegrees(0))
        assertEquals(90, LiveStreamProtocol.cameraPostureToDegrees(1))
        assertEquals(180, LiveStreamProtocol.cameraPostureToDegrees(2))
        assertEquals(270, LiveStreamProtocol.cameraPostureToDegrees(3))
        assertNull(LiveStreamProtocol.cameraPostureToDegrees(4))
        assertNull(LiveStreamProtocol.cameraPostureToDegrees(5))
        assertNull(LiveStreamProtocol.cameraPostureToDegrees(-1))
        assertNull(LiveStreamProtocol.cameraPostureToDegrees(99))

        // GO 3S sensor baseline: adjust_rotate_android.default = 90 makes posture 3 upright (0 deg)
        assertEquals(90, LiveStreamProtocol.cameraPostureToDegrees(0, adjustDegrees = 90))
        assertEquals(180, LiveStreamProtocol.cameraPostureToDegrees(1, adjustDegrees = 90))
        assertEquals(270, LiveStreamProtocol.cameraPostureToDegrees(2, adjustDegrees = 90))
        assertEquals(0, LiveStreamProtocol.cameraPostureToDegrees(3, adjustDegrees = 90))
        assertNull(LiveStreamProtocol.cameraPostureToDegrees(4, adjustDegrees = 90))
        assertNull(LiveStreamProtocol.cameraPostureToDegrees(5, adjustDegrees = 90))

        // GO 3 / X3 / Luna offsets
        assertEquals(180, LiveStreamProtocol.cameraPostureToDegrees(0, adjustDegrees = 180))
        assertEquals(90, LiveStreamProtocol.cameraPostureToDegrees(3, adjustDegrees = 180))
        assertEquals(270, LiveStreamProtocol.cameraPostureToDegrees(0, adjustDegrees = 270))
        assertEquals(180, LiveStreamProtocol.cameraPostureToDegrees(3, adjustDegrees = 270))
    }

    @Test
    fun encodeSetAccessCameraFileStateMatchesVerifiedWireFormat() {
        assertEquals(0, LiveStreamProtocol.AccessCameraFileState.UNKNOWN.protoValue)
        assertEquals(1, LiveStreamProtocol.AccessCameraFileState.IDLE.protoValue)
        assertEquals(2, LiveStreamProtocol.AccessCameraFileState.EXPORT.protoValue)
        assertEquals(3, LiveStreamProtocol.AccessCameraFileState.DOWNLOAD.protoValue)
        assertEquals(4, LiveStreamProtocol.AccessCameraFileState.PLAYBACK.protoValue)
        assertEquals(5, LiveStreamProtocol.AccessCameraFileState.LIVE_VIEW.protoValue)

        assertArrayEquals(
            byteArrayOf(0x08, 0x05),
            LiveStreamProtocol.encodeSetAccessCameraFileState(LiveStreamProtocol.AccessCameraFileState.LIVE_VIEW)
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x01),
            LiveStreamProtocol.encodeSetAccessCameraFileState(LiveStreamProtocol.AccessCameraFileState.IDLE)
        )
        assertArrayEquals(
            ByteArray(0),
            LiveStreamProtocol.encodeSetAccessCameraFileState(LiveStreamProtocol.AccessCameraFileState.UNKNOWN)
        )
        assertArrayEquals(
            byteArrayOf(0x08, 0x05, 0x10, 0x03),
            LiveStreamProtocol.encodeSetAccessCameraFileState(
                LiveStreamProtocol.AccessCameraFileState.LIVE_VIEW,
                bitmap = 3
            )
        )
    }

    @Test
    fun encodeStartLiveStreamDefaultConfigEncodesExpectedProtobufFields() {
        val payload = LiveStreamProtocol.encodeStartLiveStream()
        val fields = decodeVarintFields(payload)
        assertEquals(
            listOf(
                2 to 1, // enable_video = true
                6 to 40, // video_bitrate = 40
                7 to 76, // resolution = RES_640_480P30 (76)
                8 to 1, // enable_gyro = true
                9 to 40, // video_bitrate1 = 40
                10 to 76 // resolution1 = RES_640_480P30 (76)
            ),
            fields
        )
    }

    @Test
    fun encodeStartLiveStreamCustomConfigIncludesOptionalFieldsAndResolutions() {
        val hd = LiveStreamProtocol.encodeStartLiveStream(
            StartLiveStreamConfig(
                enableAudio = true,
                enableVideo = true,
                videoBitrate = 60,
                resolution = PreviewResolution.RES_1280_720_P30,
                enableGyro = true,
                videoBitrate1 = 40,
                resolution1 = PreviewResolution.RES_1920_1080_P30,
                previewStreamNum = 1
            )
        )
        assertEquals(
            listOf(
                1 to 1, // enable_audio = true
                2 to 1, // enable_video = true
                6 to 60, // video_bitrate
                7 to 43, // RES_1280_720P30
                8 to 1, // enable_gyro = true
                9 to 40, // video_bitrate1
                10 to 29, // RES_1920_1080P30
                11 to 1 // preview_stream_num
            ),
            decodeVarintFields(hd)
        )
    }

    @Test
    fun parseStreamPacketExtractsAllKnownTrackTypesAndTimestamp() {
        val cases = listOf(
            0x10 to StreamKind.AUDIO,
            0x20 to StreamKind.VIDEO_PRIMARY,
            0x21 to StreamKind.VIDEO_SECONDARY,
            0x30 to StreamKind.GYRO,
            0x40 to StreamKind.EXPOSURE,
            0x7F to StreamKind.UNKNOWN
        )
        val expectedTs = 0x0011223344556677L
        val trackBody = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte())

        for ((typeByte, expectedKind) in cases) {
            val packet = ByteBuffer.allocate(12 + trackBody.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(0x01)
                .put(0x00)
                .put(0x00)
                .put(typeByte.toByte())
                .putLong(expectedTs)
                .put(trackBody)
                .array()

            val parsed = LiveStreamProtocol.parseStreamPacket(packet)
            assertNotNull("Expected valid packet for type 0x%02x".format(typeByte), parsed)
            assertEquals(typeByte, parsed!!.streamType)
            assertEquals(expectedKind, parsed.kind)
            assertEquals(expectedTs, parsed.timestampMs)
            assertArrayEquals(trackBody, parsed.data)
        }
    }

    @Test
    fun parseStreamPacketRejectsNonStreamAndMalformedPackets() {
        // Control packet (0x04), keepalive (0x05), sync (0x06)
        assertNull(LiveStreamProtocol.parseStreamPacket(byteArrayOf(0x04, 0x00, 0x00, 0x20, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(LiveStreamProtocol.parseStreamPacket(byteArrayOf(0x05, 0x00, 0x00, 0x20, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(LiveStreamProtocol.parseStreamPacket(byteArrayOf(0x06, 0x00, 0x00, 0x20, 0, 0, 0, 0, 0, 0, 0, 0)))
        // Non-zero reserved bytes
        assertNull(LiveStreamProtocol.parseStreamPacket(byteArrayOf(0x01, 0x01, 0x00, 0x20, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(LiveStreamProtocol.parseStreamPacket(byteArrayOf(0x01, 0x00, 0x02, 0x20, 0, 0, 0, 0, 0, 0, 0, 0)))
        // Truncated header
        assertNull(LiveStreamProtocol.parseStreamPacket(byteArrayOf(0x01, 0x00, 0x00, 0x20, 1, 2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun packetStreamUnpackerHandlesSplitChunksGrowthCompactionAndInvalidLengths() {
        val unpacker = PacketStreamUnpacker(initialCapacity = 64)
        val p1 = byteArrayOf(0x05, 0x00, 0x00)
        val p2 = ByteArray(150) { i -> (i and 0xFF).toByte() }.also {
            it[0] = 0x01
            it[1] = 0x00
            it[2] = 0x00
            it[3] = 0x20
        }
        val p3 = ByteArray(90 * 1024) { i -> ((i * 3) and 0xFF).toByte() }.also {
            it[0] = 0x01
            it[1] = 0x00
            it[2] = 0x00
            it[3] = 0x20
        }
        val wire = PacketFramer.framePayload(p1) +
            PacketFramer.framePayload(p2) +
            PacketFramer.framePayload(p3)

        val unpacked = mutableListOf<ByteArray>()
        // Feed in uneven 7-byte chunks first, then larger chunks to force split headers, growth, and compaction.
        var offset = 0
        while (offset < wire.size) {
            val step = if (offset < 400) 7 else 4096
            val end = minOf(wire.size, offset + step)
            val slice = wire.copyOfRange(offset, end)
            unpacker.append(slice, slice.size)
            unpacker.drainPackets { unpacked.add(it) }
            offset = end
        }

        assertEquals(3, unpacked.size)
        assertArrayEquals(p1, unpacked[0])
        assertArrayEquals(p2, unpacked[1])
        assertArrayEquals(p3, unpacked[2])

        // Invalid length headers (< 4 and > MAX_PACKET_LENGTH) advance safely without crashing or emitting.
        unpacker.reset()
        unpacker.append(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        unpacker.append(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F))
        val invalidOut = mutableListOf<ByteArray>()
        unpacker.drainPackets { invalidOut.add(it) }
        assertEquals(0, invalidOut.size)

        // Repeated cycles compact properly without retaining stale data.
        unpacker.reset()
        repeat(50) { iter ->
            val frame = ByteArray(40) { (it + iter).toByte() }
            val framed = PacketFramer.framePayload(frame)
            unpacker.append(framed)
            val batch = mutableListOf<ByteArray>()
            unpacker.drainPackets { batch.add(it) }
            assertEquals(1, batch.size)
            assertArrayEquals(frame, batch[0])
        }
    }
}
