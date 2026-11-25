package com.open360.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol codecs and framing helpers for Insta360 Wi-Fi Live View over TCP port 6666.
 *
 * Verified against shipped Insta360 APK (`SetAccessCameraFileState`, `StartLiveStream` protobuf
 * schemas, `VideoResolution` enum, and `UcdLayout` / `UcdReceived$Stream`) and official GO 3S
 * runtime logs:
 * - Commands (`0x04` control packets): `StartLiveStream` (0x01), `StopLiveStream` (0x02),
 *   `RequestStreamingIframe` (0x69), `SetAccessCameraFileState` (0x76).
 * - Incoming stream packets (`0x01` multiplexed frames): 12-byte header
 *   `0x01 0x00 0x00 <streamType:u8> <timestampMs:i64LE>` followed by raw track payload.
 */
object LiveStreamProtocol {

    const val CMD_START_LIVE_STREAM = 0x01
    const val CMD_STOP_LIVE_STREAM = 0x02
    const val CMD_REQUEST_STREAMING_IFRAME = 0x69
    const val CMD_SET_ACCESS_CAMERA_FILE_STATE = 0x76
    const val NOTIF_LIVEVIEW_BEGIN_ROTATE = 0x201B
    const val NOTIF_APP_LIVEVIEW_STATUS = 0x2025
    const val OPTION_CAMERA_POSTURE = 93

    enum class AccessCameraFileState(val protoValue: Int) {
        UNKNOWN(0),
        IDLE(1),
        EXPORT(2),
        DOWNLOAD(3),
        PLAYBACK(4),
        LIVE_VIEW(5)
    }

    const val PACKET_TYPE_STREAM: Byte = 0x01
    const val STREAM_HEADER_SIZE = 12

    const val STREAM_TYPE_AUDIO = 0x10
    const val STREAM_TYPE_VIDEO_PRIMARY = 0x20
    const val STREAM_TYPE_VIDEO_SECONDARY = 0x21
    const val STREAM_TYPE_GYRO = 0x30
    const val STREAM_TYPE_EXPOSURE = 0x40

    enum class PreviewResolution(
        val protoValue: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val label: String
    ) {
        RES_640_480_P30(76, 640, 480, 30, "480p"),
        RES_1280_720_P30(43, 1280, 720, 30, "720p"),
        RES_1920_1080_P30(29, 1920, 1080, 30, "1080p")
    }

    /**
     * Maps `insta360.messages.CameraPostureType` (`0..5`) to clockwise preview rotation degrees,
     * adjusted by the camera model's hardware sensor baseline ([adjustDegrees], from the official
     * `adjust_rotate_android.default` config). Returns null for `CAMERA_POSTURE_UP` (4),
     * `CAMERA_POSTURE_DOWN` (5), or unknown values so callers preserve the previous orientation
     * when the camera is pointed straight up/down.
     */
    fun cameraPostureToDegrees(postureValue: Int, adjustDegrees: Int = 0): Int? {
        val raw = when (postureValue) {
            0 -> 0
            1 -> 90
            2 -> 180
            3 -> 270
            else -> return null
        }
        return (((raw + adjustDegrees) % 360) + 360) % 360
    }

    data class StartLiveStreamConfig(
        val enableAudio: Boolean = false,
        val enableVideo: Boolean = true,
        val videoBitrate: Int = 40,
        val resolution: PreviewResolution = PreviewResolution.RES_640_480_P30,
        val enableGyro: Boolean = true,
        val videoBitrate1: Int = 40,
        val resolution1: PreviewResolution = PreviewResolution.RES_640_480_P30,
        val previewStreamNum: Int = 0
    )

    enum class StreamKind {
        AUDIO,
        VIDEO_PRIMARY,
        VIDEO_SECONDARY,
        GYRO,
        EXPOSURE,
        UNKNOWN
    }

    class StreamPacket(
        val streamType: Int,
        val kind: StreamKind,
        val timestampMs: Long,
        val data: ByteArray
    )

    /**
     * Encodes `insta360.messages.StartLiveStream` using Protobuf wire format.
     */
    fun encodeStartLiveStream(config: StartLiveStreamConfig = StartLiveStreamConfig()): ByteArray {
        val out = ByteArrayOutputStream(32)
        if (config.enableAudio) writeVarintField(out, fieldNumber = 1, value = 1)
        if (config.enableVideo) writeVarintField(out, fieldNumber = 2, value = 1)
        if (config.videoBitrate > 0) writeVarintField(out, fieldNumber = 6, value = config.videoBitrate)
        writeVarintField(out, fieldNumber = 7, value = config.resolution.protoValue)
        if (config.enableGyro) writeVarintField(out, fieldNumber = 8, value = 1)
        if (config.videoBitrate1 > 0) writeVarintField(out, fieldNumber = 9, value = config.videoBitrate1)
        writeVarintField(out, fieldNumber = 10, value = config.resolution1.protoValue)
        if (config.previewStreamNum > 0) writeVarintField(out, fieldNumber = 11, value = config.previewStreamNum)
        return out.toByteArray()
    }

    /** Encodes `insta360.messages.StopLiveStream` (empty protobuf payload). */
    fun encodeStopLiveStream(): ByteArray = ByteArray(0)

    /** Encodes `insta360.messages.RequestStreamingIframe` (empty protobuf payload). */
    fun encodeRequestIframe(): ByteArray = ByteArray(0)

    /**
     * Encodes `insta360.messages.SetAccessCameraFileState` using Proto3 wire format (`0x76`).
     */
    fun encodeSetAccessCameraFileState(
        state: AccessCameraFileState,
        bitmap: Int = 0
    ): ByteArray {
        val out = ByteArrayOutputStream(8)
        if (state.protoValue != 0) writeVarintField(out, fieldNumber = 1, value = state.protoValue)
        if (bitmap != 0) writeVarintField(out, fieldNumber = 2, value = bitmap)
        return out.toByteArray()
    }

    /**
     * Parses a multiplexed stream packet (after stripping the 4-byte outer length prefix).
     * Returns null when [payload] is too short or does not carry the `0x01 0x00 0x00` header.
     */
    fun parseStreamPacket(payload: ByteArray): StreamPacket? {
        if (payload.size < STREAM_HEADER_SIZE) return null
        if (payload[0] != PACKET_TYPE_STREAM || payload[1] != 0x00.toByte() || payload[2] != 0x00.toByte()) {
            return null
        }

        val streamType = payload[3].toInt() and 0xFF
        val kind = when (streamType) {
            STREAM_TYPE_AUDIO -> StreamKind.AUDIO
            STREAM_TYPE_VIDEO_PRIMARY -> StreamKind.VIDEO_PRIMARY
            STREAM_TYPE_VIDEO_SECONDARY -> StreamKind.VIDEO_SECONDARY
            STREAM_TYPE_GYRO -> StreamKind.GYRO
            STREAM_TYPE_EXPOSURE -> StreamKind.EXPOSURE
            else -> StreamKind.UNKNOWN
        }
        val timestampMs = ByteBuffer.wrap(payload, 4, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
        val data = payload.copyOfRange(STREAM_HEADER_SIZE, payload.size)
        return StreamPacket(
            streamType = streamType,
            kind = kind,
            timestampMs = timestampMs,
            data = data
        )
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeRawVarint32(out, (fieldNumber shl 3) or 0)
        writeRawVarint32(out, value)
    }

    private fun writeRawVarint32(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }
}
