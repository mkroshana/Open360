package com.open360.ui.camera

import com.open360.core.ble.Go2BleProtocol.CardState

/**
 * Wording for what the camera reports about itself. Kept apart from the screen so it can be
 * tested.
 */
object CameraStatusText {

    /** A time left such as "1 h 20 min", "42 min" or "Under 1 min". */
    fun timeLeft(seconds: Int): String {
        val minutes = seconds / 60
        return when {
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
            minutes >= 1 -> "$minutes min"
            else -> "Under 1 min"
        }
    }

    /** What is wrong with the camera's storage, or null when nothing is. */
    fun storageProblem(state: CardState?): String? = when (state) {
        null, CardState.OK -> null
        CardState.NO_CARD -> "Not Found"
        CardState.FULL -> "Full"
        CardState.INVALID_FORMAT -> "Needs Formatting"
        CardState.WRITE_PROTECTED -> "Write-Protected"
        CardState.ERROR -> "Error"
    }
}
