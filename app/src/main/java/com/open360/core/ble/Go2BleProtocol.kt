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

    const val INNER_HEADER_SIZE = 16
    const val ENVELOPE_HEADER_SIZE = 5
    const val CRC_SIZE = 2

    // Official Insta360 command codes
    const val CMD_TAKE_PICTURE = 0x03
    const val CMD_START_CAPTURE = 0x04
    const val CMD_STOP_CAPTURE = 0x05
    const val CMD_CALIBRATE_GYRO = 0x19 // GO 3/3S: battery query
    const val CMD_UPLOAD_GPS = 0x35     // 53 decimal: inject GPS coordinates
    const val CMD_SET_KEY_TIME_POINT = 0x3C

    // Camera notification codes
    const val NOTIF_BATTERY_UPDATE = 0x2003
    const val NOTIF_CURRENT_CAPTURE_STATUS = 0x2010
    const val NOTIF_BATTERY_STATE_GO3 = 0x2021
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
