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
