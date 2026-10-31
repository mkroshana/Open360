package com.open360.ui.camera

import com.open360.core.ble.CameraMode
import com.open360.core.ble.Go2BleProtocol.CardState
import com.open360.core.ble.Go2BleProtocol.ChargeBoxStatus
import com.open360.core.ble.WakeUpRegistration
import java.util.Locale

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

    /** A concise time left label for the top bar, such as "1 h 20 min left", "42 min left" or "Under 1 min left". */
    fun timeLeftLabel(seconds: Int): String = "${timeLeft(seconds)} left"

    /** A number of photos left, such as "1,105". */
    fun photosLeft(count: Int): String = String.format(Locale.US, "%,d", count)

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
     * here. The GO 3S reported 1, 2, 3, 7, 13, 17, 18 and 19 for its modes, named here as its
     * Action Pod names them; it records slow motion as 7, which the published definitions call
     * bullet time. The rest follow those definitions.
     */
    fun recordingMode(captureState: Int?): String? = when (captureState) {
        1 -> CameraMode.VIDEO.label
        2 -> CameraMode.TIMELAPSE.label
        3 -> CameraMode.INTERVAL_PHOTO.label
        7 -> CameraMode.SLOW_MOTION.label
        9 -> "HDR Video"
        12 -> "Interval Video"
        13 -> CameraMode.TIMESHIFT.label
        17 -> CameraMode.FREEFRAME_PRO.label
        18 -> CameraMode.LOOP_RECORDING.label
        19 -> CameraMode.STARLAPSE.label
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

    /** The Action Pod's line: its battery, "72%, Plugged In", "Switched Off" or "Not Connected". */
    fun pod(status: ChargeBoxStatus): String {
        if (!status.connected) return "Not Connected"
        if (!status.isOn) return "Switched Off"
        val battery = status.battery ?: return "Connected"
        return if (battery.onExternalPower) "${battery.levelPercent}%, Plugged In" else "${battery.levelPercent}%"
    }

    /** Whether the pod is running low with nothing charging it. */
    fun podIsLow(status: ChargeBoxStatus): Boolean {
        val battery = status.battery ?: return false
        return status.connected && !battery.onExternalPower && battery.levelPercent <= LOW_BATTERY_PERCENT
    }

    /** The Action Pod's compact label for the top bar: e.g. "Pod 72%", or null when not connected or unknown. */
    fun podBatteryLabel(status: ChargeBoxStatus?): String? {
        if (status == null || !status.connected) return null
        val battery = status.battery ?: return null
        return "Pod ${battery.levelPercent}%"
    }

    /** Whether there is any status to display in the top status bar. */
    fun hasTopStatus(
        batteryPercent: Int?,
        pod: ChargeBoxStatus?,
        captureSecondsLeft: Int?,
        cardState: CardState?
    ): Boolean {
        return batteryPercent != null ||
            (pod?.connected == true && pod.battery != null) ||
            captureSecondsLeft != null ||
            storageProblem(cardState) != null
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

    /**
     * Where the set-up for Wake Up stands, in a word or two for a settings row. Null when there
     * is an ID the camera has not been asked about yet, as when it is off: nothing is known then.
     */
    fun wakeUpSetupStatus(hasId: Boolean, registration: WakeUpRegistration): String? = when {
        !hasId -> "Not Set Up"
        registration == WakeUpRegistration.AUTHORIZED -> "Ready"
        registration == WakeUpRegistration.NONE -> null
        else -> wakeUpRegistration(registration)
    }

    enum class StatusDotState {
        DISCONNECTED,
        RECORDING,
        CONNECTED,
        STANDBY,
        CONNECTING
    }

    /**
     * The camera identity displayed in the top bar title, or the app's name with no camera, since
     * the camera card says how the link stands.
     */
    fun topBarTitle(
        serverConnected: Boolean,
        serverSerial: String?,
        bleConnected: Boolean,
        bleDeviceName: String?,
        isAdvertising: Boolean
    ): String = when {
        serverConnected -> serverSerial?.let { "Insta360 ($it)" } ?: "Insta360 GO 3S"
        bleConnected -> bleDeviceName ?: "Insta360 GO 3S"
        isAdvertising -> "Insta360 GPS Remote"
        else -> "Open360"
    }

    /** Resolves the status dot state for both Architecture A (GATT server) and Architecture B (BLE client). */
    fun statusDotState(
        isRecording: Boolean,
        isConnected: Boolean,
        isAsleep: Boolean,
        isAdvertising: Boolean,
        bleScanning: Boolean,
        bleConnecting: Boolean
    ): StatusDotState = when {
        isRecording -> StatusDotState.RECORDING
        isConnected && !isAsleep -> StatusDotState.CONNECTED
        isAsleep -> StatusDotState.STANDBY
        isAdvertising || bleScanning || bleConnecting -> StatusDotState.CONNECTING
        else -> StatusDotState.DISCONNECTED
    }

    const val LOW_BATTERY_PERCENT = 20
}
