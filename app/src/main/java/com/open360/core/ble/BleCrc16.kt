package com.open360.core.ble

/**
 * Computes CRC-16/MODBUS checksum over binary packets.
 * Polynomial: 0xA001 (reversed representation of 0x8005), Initial value: 0xFFFF.
 *
 * Used for framing Go2BlePacket frames on Insta360 GO 2, GO 3, and GO 3S cameras.
 */
object BleCrc16 {

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until (offset + length)) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}
