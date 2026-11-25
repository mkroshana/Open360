package com.open360.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface CameraNotification {
    data class BatteryUpdate(
        val percentage: Int,
        val isCharging: Boolean
    ) : CameraNotification

    data class ShutterStateUpdate(
        val isRecording: Boolean,
        val elapsedSeconds: Int
    ) : CameraNotification

    data class RawNotification(
        val messageCode: Int,
        val payload: ByteArray
    ) : CameraNotification
}

object NotificationParser {

    const val NOTIFICATION_BATTERY_UPDATE = 0x201E
    const val NOTIFICATION_CAPTURE_STATUS = 0x2010

    /**
     * Parses a raw packet payload (excluding 4-byte outer length).
     */
    fun parse(payload: ByteArray): CameraNotification? {
        if (payload.size < 12) return null

        val byteBuf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val prefix = byteArrayOf(byteBuf.get(), byteBuf.get(), byteBuf.get())
        if (prefix[0] != 0x04.toByte() || prefix[1] != 0x00.toByte() || prefix[2] != 0x00.toByte()) {
            return null
        }

        val messageCode = byteBuf.short.toInt() and 0xFFFF
        val body = payload.copyOfRange(12, payload.size)

        return when (messageCode) {
            NOTIFICATION_BATTERY_UPDATE -> parseBatteryNotification(body) ?: CameraNotification.RawNotification(messageCode, body)
            else -> CameraNotification.RawNotification(messageCode, body)
        }
    }

    /**
     * Extracts battery percentage and charging state from protobuf payload.
     * Expected structure: ... 10 <percentage_hex> 18 <charging_hex> ...
     */
    private fun parseBatteryNotification(body: ByteArray): CameraNotification.BatteryUpdate? {
        var percentage: Int? = null
        var isCharging = false

        var i = 0
        while (i < body.size - 1) {
            val tag = body[i].toInt() and 0xFF
            if (tag == 0x10) { // Field 2: battery level
                percentage = body[i + 1].toInt() and 0xFF
                i += 2
            } else if (tag == 0x18) { // Field 3: charging state
                isCharging = (body[i + 1].toInt() and 0xFF) == 1
                i += 2
            } else {
                i++
            }
        }

        return percentage?.let {
            CameraNotification.BatteryUpdate(
                percentage = it,
                isCharging = isCharging
            )
        }
    }
}
