package com.open360.wear.ui

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.ui.camera.CameraStatusText
import java.util.Locale

/**
 * The watch's wording for the camera, worded as the phone app words it. Kept apart from the
 * screens so it can be tested.
 */
object RemoteText {

    /** What the link and the camera are doing, in a few words for the top of the screen. */
    fun status(ble: BleCameraState, isRecording: Boolean, recordingSeconds: Long): String {
        val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
        return when {
            isRecording -> "Recording ${elapsed(recordingSeconds)}"
            linked && !ble.isCameraAwake && ble.isWaking -> "Switching Camera On..."
            linked && !ble.isCameraAwake -> "Camera Is Off"
            linked -> "Ready"
            ble.isReconnecting || ble.isWaking -> "Connecting to Camera..."
            ble.connectionStatus == BleConnectionStatus.SCANNING -> "Looking for Camera..."
            ble.connectionStatus == BleConnectionStatus.CONNECTING -> "Connecting to Camera..."
            else -> "Disconnected"
        }
    }

    /**
     * The status for the dimmed screen, which changes at most once a minute: a recording's length
     * in whole minutes, as in "Recording, 3 min".
     */
    fun ambientStatus(ble: BleCameraState, isRecording: Boolean, recordingSeconds: Long): String =
        if (isRecording) "Recording, ${CameraStatusText.timeLeft(recordingSeconds.toInt())}"
        else status(ble, isRecording = false, recordingSeconds = 0)

    /**
     * What a stopped recording confirms: "Saved, 3 min 2 s" once the camera has named the clip,
     * "Clip Saved" when it named it without a length, and "Recording Stopped" when it said nothing,
     * as for one stopped with the camera's button.
     */
    fun saved(seconds: Int?, named: Boolean): String = when {
        named && seconds != null -> "Saved, ${CameraStatusText.clipLength(seconds)}"
        named -> "Clip Saved"
        else -> "Recording Stopped"
    }

    /** A recording's length so far, such as "01:05" or "1:02:09". */
    fun elapsed(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val secs = seconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.US, "%02d:%02d", minutes, secs)
    }

    /**
     * What the shutter does now: the camera's own mode when it is in the chosen group, labelled
     * as the phone app labels it ("Start Interval Photo", "Take HDR Photo", "Stop Recording").
     */
    fun shutter(
        choice: CaptureMode,
        cameraMode: CameraMode?,
        isRecording: Boolean,
        isStarting: Boolean,
        isStopping: Boolean
    ): String {
        val shown = shownMode(choice, cameraMode)
        return when {
            isStopping -> "Stopping..."
            isRecording -> if (shown?.isPhoto == true) "Stop ${shown.label}" else "Stop Recording"
            isStarting -> "Starting..."
            choice == CaptureMode.PHOTO -> when {
                shown == null || shown == CameraMode.PHOTO -> "Take Photo"
                shown.isContinuous -> "Start ${shown.label}"
                else -> "Take ${shown.label}"
            }
            else -> "Start Recording"
        }
    }

    /** The camera's mode when it is in the group chosen, Video or Photo; null otherwise. */
    fun shownMode(choice: CaptureMode, cameraMode: CameraMode?): CameraMode? =
        cameraMode?.takeIf { it.isPhoto == (choice == CaptureMode.PHOTO) }

    /**
     * What to do next while the camera cannot be controlled, in one short line under the status
     * that already says what is happening; null when the camera can be controlled.
     */
    fun hint(ble: BleCameraState, hasWakeUpId: Boolean, isLookingForCamera: Boolean): String? {
        val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
        return when {
            linked && ble.isCameraAwake -> null
            linked && ble.isWaking -> "Takes a few seconds"
            isLookingForCamera -> "Keep the camera nearby"
            linked -> "Or switch it on with its button"
            hasWakeUpId -> "Connect also switches it on"
            else -> "Switch the camera on, then connect"
        }
    }

    /** A fix older than this no longer counts as GPS being ready. Fixes come once a second. */
    const val GPS_FIX_STALE_MS = 5000L

    /** Whether the watch has a current GPS fix: one within [GPS_FIX_STALE_MS]. */
    fun gpsReady(hasFix: Boolean, fixAgeMs: Long): Boolean = hasFix && fixAgeMs in 0..GPS_FIX_STALE_MS

    /** The GPS status line: "GPS Ready", or "Waiting for GPS" until there is a current fix. */
    fun gpsStatus(ready: Boolean): String = if (ready) "GPS Ready" else "Waiting for GPS"

    /** A speed in the unit chosen, such as "32 km/h". */
    fun speed(kmh: Float, mph: Float, inMph: Boolean): String =
        if (inMph) String.format(Locale.US, "%.0f mph", mph) else String.format(Locale.US, "%.0f km/h", kmh)
}
