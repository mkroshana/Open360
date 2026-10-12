package com.open360.ui.camera

import com.open360.core.ble.Go2BleProtocol.CardState
import com.open360.core.ble.Go2BleProtocol.ChargeBoxStatus
import com.open360.core.ble.WakeUpRegistration

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

    /**
     * The name of a recording mode from its CameraCaptureState, or null for one with no name
     * here. Only Video (1) and PRO Video (17) have been seen from a GO 3S; the rest follow the
     * names in the published definitions.
     */
    fun recordingMode(captureState: Int?): String? = when (captureState) {
        1 -> "Video"
        2 -> "Timelapse"
        7 -> "Bullet Time"
        9 -> "HDR Video"
        12 -> "Interval Video"
        13 -> "TimeShift"
        17 -> "PRO Video"
        18 -> "Loop Recording"
        20 -> "FPV"
        22 -> "Slow Motion"
        else -> null
    }

    /** A clip length such as "41 s", "3 min 30 s" or "1 h 2 min". */
    fun clipLength(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min $secs s"
            else -> "$secs s"
        }
    }

    /** The Action Pod's line: its battery, "72%, Plugged In", or "Not Connected". */
    fun pod(status: ChargeBoxStatus): String {
        if (!status.connected) return "Not Connected"
        val battery = status.battery ?: return "Connected"
        return if (battery.onExternalPower) "${battery.levelPercent}%, Plugged In" else "${battery.levelPercent}%"
    }

    /** Whether the pod is running low with nothing charging it. */
    fun podIsLow(status: ChargeBoxStatus): Boolean {
        val battery = status.battery ?: return false
        return status.connected && !battery.onExternalPower && battery.levelPercent <= LOW_BATTERY_PERCENT
    }

    /** What the camera says about the wake-up ID, or null when it has not been asked. */
    fun wakeUpRegistration(registration: WakeUpRegistration): String? = when (registration) {
        WakeUpRegistration.NONE -> null
        WakeUpRegistration.CHECKING -> "Asking the Camera..."
        WakeUpRegistration.CONFIRM_ON_CAMERA -> "Allow Open360 on the Camera"
        WakeUpRegistration.AUTHORIZED -> "Accepted by the Camera"
        WakeUpRegistration.REJECTED -> "Declined on the Camera"
        WakeUpRegistration.TIMED_OUT -> "Not Allowed in Time"
        WakeUpRegistration.CAMERA_BUSY -> "The Camera Is Busy, Try Again"
        WakeUpRegistration.NO_ANSWER -> "No Answer From the Camera"
    }

    /** Whether the camera was asked about the wake-up ID and did not come to accept it. */
    fun wakeUpRegistrationIsProblem(registration: WakeUpRegistration): Boolean = when (registration) {
        WakeUpRegistration.NONE,
        WakeUpRegistration.CHECKING,
        WakeUpRegistration.CONFIRM_ON_CAMERA,
        WakeUpRegistration.AUTHORIZED -> false
        WakeUpRegistration.REJECTED,
        WakeUpRegistration.TIMED_OUT,
        WakeUpRegistration.CAMERA_BUSY,
        WakeUpRegistration.NO_ANSWER -> true
    }

    const val LOW_BATTERY_PERCENT = 20
}
