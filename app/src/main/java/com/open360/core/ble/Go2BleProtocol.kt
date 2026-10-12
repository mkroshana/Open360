package com.open360.core.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /**
     * Encodes latitude, longitude, and altitude into a 24-byte payload of little-endian doubles.
     */
    fun buildGpsPayload(latitude: Double, longitude: Double, altitude: Double): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(latitude)
        buf.putDouble(longitude)
        buf.putDouble(altitude)
        return buf.array()
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
