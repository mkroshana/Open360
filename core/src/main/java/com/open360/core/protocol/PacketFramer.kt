package com.open360.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles framing, encoding, and decoding of binary TCP packets for Insta360 cameras.
 *
 * Packet format:
 * - 4 bytes: Total length of payload + 4 (Little-Endian Int32)
 * - N bytes: Packet payload
 */
object PacketFramer {

    // Magic sync payload: \x06\x00\x00syNceNdinS
    val SYNC_PAYLOAD: ByteArray = byteArrayOf(
        0x06, 0x00, 0x00,
        's'.code.toByte(), 'y'.code.toByte(), 'N'.code.toByte(), 'c'.code.toByte(),
        'e'.code.toByte(), 'N'.code.toByte(), 'd'.code.toByte(), 'i'.code.toByte(),
        'n'.code.toByte(), 'S'.code.toByte()
    )

    // Magic keep-alive payload: \x05\x00\x00
    val KEEPALIVE_PAYLOAD: ByteArray = byteArrayOf(0x05, 0x00, 0x00)

    /**
     * Encloses a payload in a 4-byte little-endian length prefix.
     */
    fun framePayload(payload: ByteArray): ByteArray {
        val totalLength = payload.size + 4
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(totalLength)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Constructs the handshake sync packet.
     */
    fun buildSyncPacket(): ByteArray = framePayload(SYNC_PAYLOAD)

    /**
     * Constructs the keepalive heartbeat packet.
     */
    fun buildKeepAlivePacket(): ByteArray = framePayload(KEEPALIVE_PAYLOAD)

    /**
     * Builds a command message packet with the 12-byte Insta360 header.
     *
     * Header structure (12 bytes):
     * - [0..2]: b'\x04\x00\x00' (Request/Response type)
     * - [3..4]: messageCode (Little-Endian UInt16)
     * - [5]: b'\x02'
     * - [6..8]: seqNumber (Little-Endian 3-byte Int24)
     * - [9..11]: b'\x80\x00\x00'
     */
    fun buildCommandPacket(messageCode: Int, sequenceNumber: Int, protobufBytes: ByteArray = ByteArray(0)): ByteArray {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x04, 0x00, 0x00))
        header.putShort(messageCode.toShort())
        header.put(0x02)
        val seqBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequenceNumber).array()
        header.put(seqBytes, 0, 3)
        header.put(byteArrayOf(0x80.toByte(), 0x00, 0x00))

        val payload = ByteArray(12 + protobufBytes.size)
        System.arraycopy(header.array(), 0, payload, 0, 12)
        if (protobufBytes.isNotEmpty()) {
            System.arraycopy(protobufBytes, 0, payload, 12, protobufBytes.size)
        }

        return framePayload(payload)
    }

    /** A message as it arrives: its code (200 for an OK reply), sequence number and protobuf body. */
    class Message(val messageCode: Int, val sequenceNumber: Int, val body: ByteArray)

    /**
     * Reads a message packet (without its length prefix) by the header [buildCommandPacket]
     * writes. A reply carries the sequence number of the command it answers. Null for packets
     * that are not messages, such as sync and keep-alive packets.
     */
    fun parseMessage(payload: ByteArray): Message? {
        if (payload.size < 12 || payload[0] != 0x04.toByte() || payload[1] != 0x00.toByte() || payload[2] != 0x00.toByte()) {
            return null
        }
        val messageCode = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)
        val sequenceNumber = (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8) or
            ((payload[8].toInt() and 0xFF) shl 16)
        return Message(messageCode, sequenceNumber, payload.copyOfRange(12, payload.size))
    }

    /**
     * Extracts complete packets from an incoming byte buffer.
     * Returns a pair of (List of complete payload ByteArrays, remaining incomplete bytes).
     */
    fun unpackStreams(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + 4 <= buffer.size) {
            val byteBuf = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
            val packetLen = byteBuf.int

            if (packetLen < 4 || packetLen > 10 * 1024 * 1024) {
                // Invalid length marker; advance by 1 byte to resynchronize
                offset++
                continue
            }

            if (offset + packetLen <= buffer.size) {
                // We have a full packet
                val payload = buffer.copyOfRange(offset + 4, offset + packetLen)
                packets.add(payload)
                offset += packetLen
            } else {
                // Incomplete packet in buffer; wait for more data
                break
            }
        }

        val remaining = if (offset < buffer.size) buffer.copyOfRange(offset, buffer.size) else ByteArray(0)
        return Pair(packets, remaining)
    }
}

/**
 * Reusable compacting buffer that unpacks length-prefixed Insta360 TCP packets without per-chunk
 * quadratic array concatenation (essential for 30 fps H.264 video streaming).
 */
class PacketStreamUnpacker(initialCapacity: Int = 128 * 1024) {

    companion object {
        private const val MAX_PACKET_LENGTH = 10 * 1024 * 1024
    }

    private var buffer = ByteArray(initialCapacity.coerceAtLeast(4096))
    private var readPos = 0
    private var writePos = 0

    fun append(src: ByteArray, length: Int = src.size) {
        if (length <= 0) return
        require(length <= src.size) { "length ($length) exceeds source size (${src.size})" }
        ensureTailCapacity(length)
        System.arraycopy(src, 0, buffer, writePos, length)
        writePos += length
    }

    fun drainPackets(onPacket: (ByteArray) -> Unit) {
        while (writePos - readPos >= 4) {
            val packetLen = (buffer[readPos].toInt() and 0xFF) or
                ((buffer[readPos + 1].toInt() and 0xFF) shl 8) or
                ((buffer[readPos + 2].toInt() and 0xFF) shl 16) or
                ((buffer[readPos + 3].toInt() and 0xFF) shl 24)

            if (packetLen < 4 || packetLen > MAX_PACKET_LENGTH) {
                // Invalid length marker; advance by 1 byte to resynchronize
                readPos++
                continue
            }

            if (writePos - readPos < packetLen) {
                break
            }

            val payload = buffer.copyOfRange(readPos + 4, readPos + packetLen)
            readPos += packetLen
            onPacket(payload)
        }

        if (readPos == writePos) {
            readPos = 0
            writePos = 0
        }
    }

    fun reset() {
        readPos = 0
        writePos = 0
    }

    private fun ensureTailCapacity(needed: Int) {
        if (buffer.size - writePos >= needed) return
        val unconsumed = writePos - readPos
        if (readPos > 0) {
            System.arraycopy(buffer, readPos, buffer, 0, unconsumed)
            readPos = 0
            writePos = unconsumed
        }
        if (buffer.size - writePos >= needed) return

        var newCapacity = buffer.size
        val requiredCapacity = unconsumed + needed
        while (newCapacity < requiredCapacity) {
            newCapacity *= 2
        }
        buffer = buffer.copyOf(newCapacity)
    }
}
