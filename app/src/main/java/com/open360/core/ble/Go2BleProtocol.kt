package com.open360.core.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Protocol encoders and decoders for Insta360 BLE communication.
 *
 * Supports:
 * - Architecture B (Direct Control on GO 3S): Go2BlePacket framing with CRC-16/MODBUS over BE80/BE81/BE82.
 * - Architecture A (GPS Remote Emulation): Fixed 9-byte command frames over CE80/CE81/CE82.
 */
object Go2BleProtocol {

    const val MARKER: Byte = 0xFF.toByte()
    const val TYPE_MESSAGE: Byte = 0x07
    const val TYPE_RESPONSE: Byte = 0x06
    const val SUBTYPE_MESSAGE: Byte = 0x40
    const val SUBTYPE_SYNC: Byte = 0x41

    // The wake-up authorization and its answer use an envelope type and subtype of their own
    const val TYPE_WAKE_AUTHORIZATION: Byte = 0x0C
    const val SUBTYPE_WAKE_AUTHORIZATION: Byte = 0x02
    const val TYPE_WAKE_AUTHORIZATION_REPLY: Byte = 0x0D
    const val SUBTYPE_WAKE_AUTHORIZATION_REPLY: Byte = 0x03

    const val INNER_HEADER_SIZE = 16
    const val ENVELOPE_HEADER_SIZE = 5
    const val CRC_SIZE = 2

    // Official Insta360 command codes
    const val CMD_TAKE_PICTURE = 0x03
    const val CMD_START_CAPTURE = 0x04
    const val CMD_STOP_CAPTURE = 0x05
    const val CMD_SET_OPTIONS = 0x07
    const val CMD_GET_OPTIONS = 0x08
    const val CMD_GET_CURRENT_CAPTURE_STATUS = 0x0F
    const val CMD_CHECK_AUTHORIZATION = 0x27
    // Sent with no payload: its one published field, the operation type, defaults to BLE_CONNECT
    const val CMD_REQUEST_AUTHORIZATION = 0x56
    // 0x19 is CALIBRATE_GYRO in the published definitions and must not be sent. Earlier
    // versions sent it on every connect as a "battery query": the reply was always 100, even
    // with the battery at 96%, and the camera refused it while recording.
    const val CMD_UPLOAD_GPS = 0x35     // 53 decimal: inject GPS coordinates
    const val CMD_SET_KEY_TIME_POINT = 0x3C

    // Camera notification codes
    const val NOTIF_BATTERY_UPDATE = 0x2003
    const val NOTIF_BATTERY_LOW = 0x2004
    const val NOTIF_SHUTDOWN = 0x2005
    const val NOTIF_STORAGE_UPDATE = 0x2006
    const val NOTIF_STORAGE_FULL = 0x2007
    const val NOTIF_CURRENT_CAPTURE_STATUS = 0x2010
    const val NOTIF_AUTHORIZATION_RESULT = 0x2011
    const val NOTIF_TEMPERATURE = 0x2016
    const val NOTIF_CHARGE_BOX_STATUS = 0x201E
    const val NOTIF_CAPTURE_TIME_LEFT = 0x2021
    const val NOTIF_PERIODIC_STATUS = 0x2026

    // Architecture A fixed 9-byte commands (GPS Remote emulation)
    val REMOTE_CMD_SHUTTER = byteArrayOf(0xfc.toByte(), 0xef.toByte(), 0xfe.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x02, 0x00)
    val REMOTE_CMD_MODE = byteArrayOf(0xfc.toByte(), 0xef.toByte(), 0xfe.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x01, 0x00)
    val REMOTE_CMD_SCREEN_TOGGLE = byteArrayOf(0xfc.toByte(), 0xef.toByte(), 0xfe.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x00, 0x00)
    val REMOTE_CMD_POWER_OFF = byteArrayOf(0xfc.toByte(), 0xef.toByte(), 0xfe.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x00, 0x03)

    /**
     * Serializes an inner 16-byte message header + payload for GO 2 / GO 3 / GO 3S.
     */
    fun encodeInnerMessage(commandCode: Int, sequence: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val totalInnerSize = INNER_HEADER_SIZE + payload.size
        val buf = ByteBuffer.allocate(totalInnerSize).order(ByteOrder.LITTLE_ENDIAN)

        // Bytes 0..3: uint32 LE total inner size
        buf.putInt(totalInnerSize)
        // Byte 4: mode = 0x04 (message)
        buf.put(0x04)
        // Bytes 5..6: alignment and reserved
        buf.put(0x00)
        buf.put(0x00)
        // Bytes 7..8: uint16 LE message code
        buf.putShort(commandCode.toShort())
        // Byte 9: contentType = 0x02 (protobuf)
        buf.put(0x02)
        // Byte 10: sequence number (1..254)
        buf.put((sequence and 0xFF).toByte())
        // Bytes 11..12: reserved
        buf.putShort(0x00)
        // Byte 13: is_last_fragment = 0x80
        buf.put(0x80.toByte())
        // Bytes 14..15: reserved
        buf.putShort(0x00)

        if (payload.isNotEmpty()) {
            buf.put(payload)
        }
        return buf.array()
    }

    /**
     * Encloses inner message data in a Go2BlePacket envelope with CRC-16/MODBUS.
     */
    fun encodeMessagePacket(innerData: ByteArray): ByteArray {
        val totalLength = ENVELOPE_HEADER_SIZE + innerData.size + CRC_SIZE
        val buf = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(MARKER)
        buf.put(TYPE_MESSAGE)
        buf.put(SUBTYPE_MESSAGE)
        buf.putShort(innerData.size.toShort())
        buf.put(innerData)

        val crc = BleCrc16.compute(buf.array(), 0, ENVELOPE_HEADER_SIZE + innerData.size)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    /**
     * Constructs the mandatory sync response packet (7 zero bytes in a sync envelope 0x41).
     */
    fun buildSyncResponsePacket(): ByteArray {
        val syncData = ByteArray(7)
        val totalLength = ENVELOPE_HEADER_SIZE + syncData.size + CRC_SIZE
        val buf = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(MARKER)
        buf.put(TYPE_MESSAGE)
        buf.put(SUBTYPE_SYNC)
        buf.putShort(syncData.size.toShort())
        buf.put(syncData)

        val crc = BleCrc16.compute(buf.array(), 0, ENVELOPE_HEADER_SIZE + syncData.size)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    // CameraCaptureState values that are single shots, not an ongoing recording:
    // SINGLE_SHOOTING, HDR_SHOOTING, SELF_TIMER_SHOOTING, SETTINGS_NEW_VALUE, BURST_SHOOTING,
    // AEB_NIGHT_SHOOTING, SINGLE_POWER_PANO_SHOOTING, HDR_POWER_PANO_SHOOTING
    private val PHOTO_CAPTURE_STATES = setOf(4, 5, 6, 8, 10, 14, 15, 16)

    /**
     * Whether a CameraCaptureState from the capture status notification means the camera is
     * recording. 0 is NOT_CAPTURE and 1 is NORMAL_CAPTURE (video).
     */
    fun isRecordingCaptureState(state: Int): Boolean = state != 0 && state !in PHOTO_CAPTURE_STATES

    // Values of field 1 in notification 0x2026, the camera's capture progress. Seen on a GO 3S:
    // 2 when a video start is accepted, 3 once it is capturing, 8 then 4 for a photo,
    // 5 when stopping, 6 when done.
    const val CAPTURE_STEP_VIDEO_STARTING = 2
    const val CAPTURE_STEP_STOPPING = 5
    const val CAPTURE_STEP_DONE = 6

    /**
     * Reads the capture step (field 1) from notification 0x2026.
     */
    fun parseCaptureStep(payload: ByteArray): Int? = (readProtoFields(payload)[1] as? Long)?.toInt()

    /**
     * Reads the CameraCaptureState the capture step belongs to (field 4 of notification 0x2026).
     * Seen on a GO 3S: 1 for a Video clip, 17 for a PRO Video clip, 4 for a photo. It arrives
     * with the start notice, two to three seconds before the capture status notification.
     */
    fun parseCaptureStepState(payload: ByteArray): Int? = (readProtoFields(payload)[4] as? Long)?.toInt()

    /**
     * Reads the CameraCaptureState (field 1) from the capture status notification (0x2010).
     * A message that leaves it out carries the default, NOT_CAPTURE.
     */
    fun parseCaptureState(payload: ByteArray): Int = (readProtoFields(payload)[1] as? Long ?: 0L).toInt()

    /**
     * Reads the file path from the camera's reply to a stop capture command, for example
     * "/DCIM/Camera01/VID_20261012_163049_00_066.mp4". The reply holds a video message in
     * field 1 whose own field 1 is the path.
     */
    fun parseCapturedFilePath(payload: ByteArray): String? {
        val video = readProtoFields(payload)[1] as? ByteArray ?: return null
        val path = readProtoFields(video)[1] as? ByteArray ?: return null
        return String(path, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /**
     * Reads the clip length in whole seconds from the camera's reply to a stop capture command
     * (field 3 of the video message). On a GO 3S it was 41 for a 41.7 s clip.
     */
    fun parseCapturedFileSeconds(payload: ByteArray): Int? {
        val video = readProtoFields(payload)[1] as? ByteArray ?: return null
        return (readProtoFields(video)[3] as? Long)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }

    data class BatteryStatus(val levelPercent: Int, val onExternalPower: Boolean)

    /**
     * Decodes the battery update notification (0x2003). Field 1 is a nested BatteryStatus:
     * power_type (1: 0 battery, 1 adapter), battery_level (2), battery_scale (3).
     */
    fun parseBatteryUpdateNotification(payload: ByteArray): BatteryStatus? {
        val status = readProtoFields(payload)[1] as? ByteArray ?: return null
        return readBatteryStatus(status)
    }

    private fun readBatteryStatus(status: ByteArray): BatteryStatus? {
        val fields = readProtoFields(status)
        val level = fields[2] as? Long ?: return null
        val scale = (fields[3] as? Long)?.takeIf { it > 0 } ?: 100L
        val powerType = fields[1] as? Long ?: 0L
        return BatteryStatus(
            levelPercent = (level * 100 / scale).toInt().coerceIn(0, 100),
            onExternalPower = powerType == 1L
        )
    }

    /** The Action Pod as the camera reports it. [battery] is null when the pod is not connected. */
    data class ChargeBoxStatus(val connected: Boolean, val battery: BatteryStatus?)

    /**
     * Decodes the charge box connect status notification (0x201e, ChargeboxConnectedStatus),
     * the camera's report on its Action Pod: connected or not (field 1) and the pod's own
     * BatteryStatus (field 2). When the pod is not connected its battery is sent as zero.
     */
    fun parseChargeBoxStatus(payload: ByteArray): ChargeBoxStatus? {
        val fields = readProtoFields(payload)
        if (fields.isEmpty()) return null
        val connected = fields[1] as? Long == 1L
        val battery = (fields[2] as? ByteArray)?.let { readBatteryStatus(it) }
        return ChargeBoxStatus(connected, if (connected) battery else null)
    }

    /**
     * Reads the recording time left on the storage, in seconds, from notification 0x2021
     * (field 1). The code is not in the published definitions, but its value tracks the
     * REMAINING_CAPTURE_TIME option: on a GO 3S the notification read 5668 and the option read
     * 5652 after two more short clips. It falls while a clip records and differs between
     * capture modes on the same storage (6552 in Video, 4796 in PRO Video).
     */
    fun parseCaptureTimeLeft(payload: ByteArray): Int? =
        (readProtoFields(payload)[1] as? Long)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    /** The camera's storage states, CardState in the published definitions. */
    enum class CardState { OK, NO_CARD, FULL, INVALID_FORMAT, WRITE_PROTECTED, ERROR }

    /**
     * Decodes the storage update notification (0x2006, NotificationCardUpdate). Field 1 is the
     * state; a message without it means the default, which is OK.
     */
    fun parseStorageUpdate(payload: ByteArray): CardState =
        when (readProtoFields(payload)[1] as? Long ?: 0L) {
            0L -> CardState.OK
            1L -> CardState.NO_CARD
            2L -> CardState.FULL
            3L -> CardState.INVALID_FORMAT
            4L -> CardState.WRITE_PROTECTED
            else -> CardState.ERROR
        }

    // OptionType values in the published definitions. In a reply each option sits in the
    // Options field with the same number.
    private const val OPTION_REMAINING_CAPTURE_TIME = 9
    private const val OPTION_BATTERY_STATUS = 11
    private const val OPTION_STORAGE_STATE = 20
    private const val OPTION_CHARGEBOX_CONNECTED_STATUS = 85

    /**
     * Builds the payload of a GetOptions request for the status the app shows: battery,
     * recording time left, storage state and the Action Pod. Field 1 is the packed list of
     * OptionType values.
     */
    fun buildStatusOptionsRequest(): ByteArray {
        val types = byteArrayOf(
            OPTION_REMAINING_CAPTURE_TIME.toByte(),
            OPTION_BATTERY_STATUS.toByte(),
            OPTION_STORAGE_STATE.toByte(),
            OPTION_CHARGEBOX_CONNECTED_STATUS.toByte()
        )
        return byteArrayOf(0x0A, types.size.toByte()) + types
    }

    /** The camera's answer to [buildStatusOptionsRequest]. A value it left out is null. */
    data class StatusOptions(
        val battery: BatteryStatus?,
        val captureSecondsLeft: Int?,
        val cardState: CardState?,
        val pod: ChargeBoxStatus?
    )

    /**
     * Decodes the reply to a GetOptions request (GetOptionsResp): field 2 is an Options message
     * holding the values asked for. Returns null when the reply has no Options in it.
     */
    fun parseStatusOptionsReply(payload: ByteArray): StatusOptions? {
        val options = readProtoFields(payload)[2] as? ByteArray ?: return null
        val fields = readProtoFields(options)
        return StatusOptions(
            battery = (fields[OPTION_BATTERY_STATUS] as? ByteArray)?.let { readBatteryStatus(it) },
            captureSecondsLeft = (fields[OPTION_REMAINING_CAPTURE_TIME] as? Long)
                ?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
            // StorageState starts with the same CardState as the storage update notification
            cardState = (fields[OPTION_STORAGE_STATE] as? ByteArray)?.let { parseStorageUpdate(it) },
            pod = (fields[OPTION_CHARGEBOX_CONNECTED_STATUS] as? ByteArray)?.let { parseChargeBoxStatus(it) }
        )
    }

    /** What the camera is doing: its CameraCaptureState and how long it has been recording. */
    data class CaptureStatusReply(val captureState: Int, val captureSeconds: Int)

    /**
     * Decodes the reply to a capture status request (GetCurrentCaptureStatusResp). Field 1 is a
     * CameraCaptureStatus holding the CameraCaptureState (field 1) and the time recorded so
     * far in seconds (field 2); fields left out carry their defaults, so an idle camera may
     * send an empty status. Returns null when the reply has no status at all. Taken from the
     * published definitions.
     */
    fun parseCaptureStatusReply(payload: ByteArray): CaptureStatusReply? {
        val status = readProtoFields(payload)[1] as? ByteArray ?: return null
        val fields = readProtoFields(status)
        return CaptureStatusReply(
            captureState = (fields[1] as? Long ?: 0L).toInt(),
            captureSeconds = (fields[2] as? Long ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        )
    }

    /** Why the camera says it is shutting down, ErrorCode in NotificationShutdown. */
    enum class ShutdownReason { BATTERY_EMPTY, OVERHEATED }

    /**
     * Decodes the shutdown notification (0x2005, NotificationShutdown). Field 1 is 0 for the
     * battery running out and 1 for the temperature going over its limit. Taken from the
     * published definitions; a GO 3S has not been seen sending it.
     */
    fun parseShutdown(payload: ByteArray): ShutdownReason =
        if (readProtoFields(payload)[1] as? Long == 1L) ShutdownReason.OVERHEATED else ShutdownReason.BATTERY_EMPTY

    /**
     * Whether the temperature notification (0x2016, TempState) reports the camera as hot.
     * Field 1 is the state: 0 low, 1 alert, 2 middle, 3 high. Alert and high both count.
     * Taken from the published definitions; a GO 3S has not been seen sending it.
     */
    fun parseTemperatureIsHigh(payload: ByteArray): Boolean =
        when (readProtoFields(payload)[1] as? Long ?: 0L) {
            1L, 3L -> true
            else -> false
        }

    /**
     * Reads the top-level fields of a protobuf message: varints as Long, length-delimited
     * fields as ByteArray. Fixed-width fields are skipped.
     */
    private fun readProtoFields(data: ByteArray): Map<Int, Any> {
        val fields = HashMap<Int, Any>()
        var pos = 0

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        while (pos < data.size) {
            val key = readVarint().toInt()
            val number = key ushr 3
            when (key and 0x07) {
                0 -> fields[number] = readVarint()
                1 -> pos += 8
                2 -> {
                    val length = readVarint().toInt()
                    if (length < 0 || pos + length > data.size) return fields
                    fields[number] = data.copyOfRange(pos, pos + length)
                    pos += length
                }
                5 -> pos += 4
                else -> return fields
            }
        }
        return fields
    }

    const val GPS_RECORD_SIZE = 53

    /**
     * Encodes one GPS fix as the 53-byte little-endian record Insta360 cameras store in the
     * video file: unix seconds (8), milliseconds (2), status 'A'/'V' (1), latitude (8),
     * 'N'/'S' (1), longitude (8), 'E'/'W' (1), speed in m/s (8), track in degrees (8),
     * altitude in metres (8).
     */
    fun buildGpsRecord(
        timestampMs: Long,
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        bearingDegrees: Double,
        altitudeMeters: Double,
        hasFix: Boolean = true
    ): ByteArray {
        val buf = ByteBuffer.allocate(GPS_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(timestampMs / 1000)
        buf.putShort((timestampMs % 1000).toShort())
        buf.put((if (hasFix) 'A' else 'V').code.toByte())
        buf.putDouble(abs(latitude))
        buf.put((if (latitude >= 0.0) 'N' else 'S').code.toByte())
        buf.putDouble(abs(longitude))
        buf.put((if (longitude >= 0.0) 'E' else 'W').code.toByte())
        buf.putDouble(speedMps)
        buf.putDouble(bearingDegrees)
        buf.putDouble(altitudeMeters)
        return buf.array()
    }

    /**
     * Wraps GPS records in the UploadGps protobuf message (field 1, bytes) sent with CMD_UPLOAD_GPS.
     */
    fun buildUploadGpsPayload(records: List<ByteArray>): ByteArray {
        val total = records.sumOf { it.size }
        val out = ByteArrayOutputStream(total + 4)
        out.write(0x0A)
        var length = total
        while (length >= 0x80) {
            out.write((length and 0x7F) or 0x80)
            length = length ushr 7
        }
        out.write(length)
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    /**
     * Number of GPS records that fit in one UploadGps packet at the given ATT MTU, so the packet
     * goes out as a single write.
     */
    fun gpsRecordsPerPacket(mtu: Int): Int {
        // ATT write header (3) and protobuf tag + length (3) around the envelope and inner header.
        val room = mtu - 3 - ENVELOPE_HEADER_SIZE - INNER_HEADER_SIZE - 3 - CRC_SIZE
        return (room / GPS_RECORD_SIZE).coerceAtLeast(1)
    }

    /**
     * Builds the keep-alive packet: the camera's Wi-Fi keep-alive (length prefix + 05 00 00)
     * carried in the BLE envelope.
     */
    fun buildKeepAlivePacket(): ByteArray {
        return encodeMessagePacket(byteArrayOf(0x07, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00))
    }

    /**
     * Builds a CheckAuthorization request, which asks the camera whether it has authorized an
     * id: the id (field 1) and a second field of 2, as the Insta360 app was seen to send it.
     */
    fun buildCheckAuthorizationPayload(authorizationId: String): ByteArray {
        val id = authorizationId.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x0A, id.size.toByte()) + id + byteArrayOf(0x10, 0x02)
    }

    /** The camera's answer to CheckAuthorization, AuthorizationStatus in the published definitions. */
    enum class AuthorizationStatus { AUTHORIZED, UNAUTHORIZED, BUSY }

    /**
     * Decodes the reply to CheckAuthorization. Field 1 is the status; a reply that leaves it out
     * carries the default, AUTHORIZED, which is what the camera sent for the Insta360 app's id.
     */
    fun parseCheckAuthorizationReply(payload: ByteArray): AuthorizationStatus =
        when (readProtoFields(payload)[1] as? Long ?: 0L) {
            0L -> AuthorizationStatus.AUTHORIZED
            1L -> AuthorizationStatus.UNAUTHORIZED
            else -> AuthorizationStatus.BUSY
        }

    /** How a request for authorization ended, AuthorizationResult in the published definitions. */
    enum class AuthorizationResult { SUCCESS, REJECTED, TIMED_OUT, BUSY }

    /** Decodes the authorization result notification (0x2011). Field 1 is the result. */
    fun parseAuthorizationResult(payload: ByteArray): AuthorizationResult =
        when (readProtoFields(payload)[1] as? Long ?: 0L) {
            0L -> AuthorizationResult.SUCCESS
            1L -> AuthorizationResult.REJECTED
            2L -> AuthorizationResult.TIMED_OUT
            else -> AuthorizationResult.BUSY
        }

    private const val OPTION_AUTHORIZATION_ID = 38

    /**
     * Builds a SetOptions request that gives the camera its authorization id: the option's type
     * in the packed list (field 1) and, in the Options message (field 2), the id as the field of
     * the same number.
     */
    fun buildSetAuthorizationIdPayload(authorizationId: String): ByteArray {
        val id = authorizationId.toByteArray(Charsets.US_ASCII)
        // Field 38 as a length-delimited value is the two-byte tag b2 02
        val options = byteArrayOf(0xB2.toByte(), 0x02, id.size.toByte()) + id
        return byteArrayOf(0x0A, 0x01, OPTION_AUTHORIZATION_ID.toByte(), 0x12, options.size.toByte()) + options
    }

    private val AUTHORIZATION_ID_FORMAT =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Whether [id] has the form of an authorization id: 36 characters, like a UUID. */
    fun isValidAuthorizationId(id: String): Boolean = AUTHORIZATION_ID_FORMAT.matches(id)

    /**
     * Builds the message that switches on a GO camera that is powered off but connected over
     * Bluetooth: FF 0C 02, the length, the authorization id as 36 ASCII characters, and the
     * checksum. Captured from the Insta360 app, which writes it as the first thing after
     * subscribing to notifications. The id is the one that app registered with the camera.
     */
    fun buildWakeAuthorizationPacket(authorizationId: String): ByteArray {
        val id = authorizationId.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(ENVELOPE_HEADER_SIZE + id.size + CRC_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MARKER)
        buf.put(TYPE_WAKE_AUTHORIZATION)
        buf.put(SUBTYPE_WAKE_AUTHORIZATION)
        buf.putShort(id.size.toShort())
        buf.put(id)
        val crc = BleCrc16.compute(buf.array(), 0, ENVELOPE_HEADER_SIZE + id.size)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    /**
     * Reads the camera's answer to the wake-up authorization (FF 0D 03, one byte): true when it
     * accepts it, which it did with the value 1 about 0.3 s after the message, two seconds before
     * the camera itself was on. Returns null for any other packet.
     */
    fun parseWakeAuthorizationReply(raw: ByteArray): Boolean? {
        val packet = decodePacket(raw) ?: return null
        if (packet.typeByte != TYPE_WAKE_AUTHORIZATION_REPLY || packet.subtypeByte != SUBTYPE_WAKE_AUTHORIZATION_REPLY) {
            return null
        }
        return packet.innerData.firstOrNull() == 1.toByte()
    }

    const val REMOTE_BTN_POWER = 0x00
    const val REMOTE_BTN_MODE = 0x01
    const val REMOTE_BTN_SHUTTER = 0x02

    const val REMOTE_STATE_CLICK = 0x00
    const val REMOTE_STATE_HELD = 0x03

    /**
     * Builds a 9-byte button command for Architecture A (GPS Remote emulation over CE82).
     */
    fun buildRemoteButtonCommand(button: Int, state: Int, sequenceNumber: Int = 0): ByteArray {
        return byteArrayOf(
            0xFC.toByte(),
            0xEF.toByte(),
            0xFE.toByte(),
            0x86.toByte(),
            (sequenceNumber and 0xFF).toByte(),
            0x03,
            0x01,
            (button and 0xFF).toByte(),
            (state and 0xFF).toByte()
        )
    }

    /**
     * Formats GPS telemetry into an NMEA RMC frame for Architecture A (GPS Remote emulation over CE82).
     *
     * Format:
     * FC EF FE 83 00 <LEN> ,26.7,\x07,$GNRMC,...*CS
     *
     * Wire Quirks:
     * - Longitude is signed and hemisphere letter is always 'E'.
     * - Extra field 'V' between mode character and checksum.
     * - Status 'V' (void) is emitted when fix is not acquired to maintain BLE keep-alive.
     */
    fun buildGpsNmeaFrame(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double = 0.0,
        speedKnots: Double = 0.0,
        bearingDegrees: Double = 0.0,
        timestampMs: Long = System.currentTimeMillis(),
        hasFix: Boolean = true
    ): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestampMs }
        val utc = String.format(
            Locale.US,
            "%02d%02d%02d.000",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND)
        )
        val ddmmyy = String.format(
            Locale.US,
            "%02d%02d%02d",
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100
        )

        val status = if (hasFix) "A" else "V"

        // Latitude: ddmm.mmmm
        val latAbs = abs(latitude)
        val latDeg = latAbs.toInt()
        val latMin = (latAbs - latDeg) * 60.0
        val latFormatted = String.format(Locale.US, "%02d%07.4f", latDeg, latMin)
        val latHemi = if (latitude >= 0.0) "N" else "S"

        // Longitude: signed dddmm.mmmm with hemisphere always 'E'
        val lonAbs = abs(longitude)
        val lonDeg = lonAbs.toInt()
        val lonMin = (lonAbs - lonDeg) * 60.0
        val signedDegMin = (if (longitude < 0.0) -1.0 else 1.0) * (lonDeg * 100.0 + lonMin)
        val lonFormatted = String.format(Locale.US, "%.4f", signedDegMin)
        val lonHemi = "E"

        val speedStr = String.format(Locale.US, "%.2f", speedKnots)
        val courseStr = String.format(Locale.US, "%.2f", bearingDegrees)

        // NMEA RMC body (excluding leading '$' and trailing '*CS')
        val body = "GNRMC,$utc,$status,$latFormatted,$latHemi,$lonFormatted,$lonHemi,$speedStr,$courseStr,$ddmmyy,0.0,W,A,V"

        var cs = 0
        for (ch in body) {
            cs = cs xor ch.code
        }
        val csHex = String.format(Locale.US, "%02X", cs)
        val nmeaSentence = "$$body*$csHex"

        // Prefix: empty field, "26.7", byte 0x07, empty field -> ",26.7,\x07,"
        val prefix = byteArrayOf(
            ','.code.toByte(),
            '2'.code.toByte(),
            '6'.code.toByte(),
            '.'.code.toByte(),
            '7'.code.toByte(),
            ','.code.toByte(),
            0x07,
            ','.code.toByte()
        )
        val nmeaBytes = nmeaSentence.toByteArray(Charsets.US_ASCII)

        val payload = ByteArray(prefix.size + nmeaBytes.size)
        System.arraycopy(prefix, 0, payload, 0, prefix.size)
        System.arraycopy(nmeaBytes, 0, payload, prefix.size, nmeaBytes.size)

        val frame = ByteArray(6 + payload.size)
        frame[0] = 0xFC.toByte()
        frame[1] = 0xEF.toByte()
        frame[2] = 0xFE.toByte()
        frame[3] = 0x83.toByte() // GPS message type
        frame[4] = 0x00         // flag / reserved
        frame[5] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 6, payload.size)
        return frame
    }

    /**
     * Decoded camera status packets sent to the remote over CE81 (Architecture A).
     */
    sealed class CameraStatusPacket {
        data class SerialHandshake(val serial: String) : CameraStatusPacket()
        data class DisplayString(
            val text: String,
            val isRecording: Boolean,
            val durationSeconds: Long?,
            val isModeString: Boolean
        ) : CameraStatusPacket()
        data class StatusWord(val payload: ByteArray) : CameraStatusPacket()
        data class Unknown(val type: Byte, val payload: ByteArray) : CameraStatusPacket()
    }

    /**
     * Parses camera-to-remote frames received on CE81 (magic: FE EF FE).
     */
    fun parseCameraStatusPacket(data: ByteArray): CameraStatusPacket? {
        if (data.size < 6) return null
        if (data[0] != 0xFE.toByte() || data[1] != 0xEF.toByte() || data[2] != 0xFE.toByte()) {
            return null
        }
        val type = data[3]
        val len = data[5].toInt() and 0xFF
        if (data.size < 6 + len) return null
        val payload = data.copyOfRange(6, 6 + len)

        return when (type) {
            0x07.toByte() -> {
                val serial = String(payload, Charsets.US_ASCII).trim()
                CameraStatusPacket.SerialHandshake(serial)
            }
            0x10.toByte() -> {
                if (payload.size > 4) {
                    val text = String(payload, 4, payload.size - 4, Charsets.US_ASCII).trim()
                    if (text.startsWith(".")) {
                        val timeStr = text.removePrefix(".")
                        val parts = timeStr.split(":")
                        val seconds = if (parts.size == 3) {
                            val h = parts[0].toLongOrNull() ?: 0L
                            val m = parts[1].toLongOrNull() ?: 0L
                            val s = parts[2].toLongOrNull() ?: 0L
                            h * 3600 + m * 60 + s
                        } else null
                        CameraStatusPacket.DisplayString(
                            text = timeStr,
                            isRecording = true,
                            durationSeconds = seconds,
                            isModeString = false
                        )
                    } else {
                        CameraStatusPacket.DisplayString(
                            text = text,
                            isRecording = false,
                            durationSeconds = null,
                            isModeString = text.contains("|")
                        )
                    }
                } else {
                    CameraStatusPacket.Unknown(type, payload)
                }
            }
            0x02.toByte() -> CameraStatusPacket.StatusWord(payload)
            else -> CameraStatusPacket.Unknown(type, payload)
        }
    }

    /**
     * Decoded outer Go2BlePacket.
     */
    data class DecodedPacket(
        val typeByte: Byte,
        val subtypeByte: Byte,
        val innerData: ByteArray
    ) {
        val isSync: Boolean get() = subtypeByte == SUBTYPE_SYNC
        val isMessage: Boolean get() = subtypeByte == SUBTYPE_MESSAGE
    }

    /**
     * Strips the Go2BlePacket envelope and validates CRC-16/MODBUS.
     */
    fun decodePacket(raw: ByteArray): DecodedPacket? {
        if (raw.size < ENVELOPE_HEADER_SIZE + CRC_SIZE) return null
        if (raw[0] != MARKER) return null

        val typeByte = raw[1]
        val subtypeByte = raw[2]
        val declaredSize = (raw[3].toInt() and 0xFF) or ((raw[4].toInt() and 0xFF) shl 8)

        val expectedTotal = ENVELOPE_HEADER_SIZE + declaredSize + CRC_SIZE
        if (raw.size < expectedTotal) return null

        val expectedCrc = (raw[ENVELOPE_HEADER_SIZE + declaredSize].toInt() and 0xFF) or
                ((raw[ENVELOPE_HEADER_SIZE + declaredSize + 1].toInt() and 0xFF) shl 8)
        val computedCrc = BleCrc16.compute(raw, 0, ENVELOPE_HEADER_SIZE + declaredSize)

        if (expectedCrc != computedCrc) return null

        val innerData = raw.copyOfRange(ENVELOPE_HEADER_SIZE, ENVELOPE_HEADER_SIZE + declaredSize)
        return DecodedPacket(typeByte, subtypeByte, innerData)
    }

    data class DecodedInnerMessage(
        val commandCode: Int,
        val sequence: Int,
        val payload: ByteArray
    )

    /**
     * Decodes the inner 16-byte header and extracts payload.
     */
    fun decodeInnerMessage(innerData: ByteArray): DecodedInnerMessage? {
        if (innerData.size < INNER_HEADER_SIZE) return null
        if (innerData[4].toInt() != 0x04) return null // Mode check

        val buf = ByteBuffer.wrap(innerData).order(ByteOrder.LITTLE_ENDIAN)
        val totalInnerSize = buf.int
        buf.position(7)
        val commandCode = buf.short.toInt() and 0xFFFF
        buf.position(10)
        val sequence = buf.get().toInt() and 0xFF

        val payloadSize = if (totalInnerSize >= INNER_HEADER_SIZE && totalInnerSize <= innerData.size) {
            totalInnerSize - INNER_HEADER_SIZE
        } else {
            innerData.size - INNER_HEADER_SIZE
        }

        val payload = if (payloadSize > 0 && INNER_HEADER_SIZE + payloadSize <= innerData.size) {
            innerData.copyOfRange(INNER_HEADER_SIZE, INNER_HEADER_SIZE + payloadSize)
        } else {
            ByteArray(0)
        }

        return DecodedInnerMessage(commandCode, sequence, payload)
    }
}
