package com.open360.wear.ui

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
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
            linked -> "Connected"
            ble.isReconnecting || ble.isWaking -> "Connecting to Camera..."
            ble.connectionStatus == BleConnectionStatus.SCANNING -> "Looking for Camera..."
            ble.connectionStatus == BleConnectionStatus.CONNECTING -> "Connecting to Camera..."
            else -> "Disconnected"
        }
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

    /** What to do next while the camera cannot be controlled, or null when it can. */
    fun hint(ble: BleCameraState, hasWakeUpId: Boolean, isLookingForCamera: Boolean): String? {
        val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
        return when {
            linked && ble.isCameraAwake -> null
            linked && ble.isWaking -> "Switching the camera on. It takes a few seconds."
            isLookingForCamera -> "Looking for the camera. Keep it nearby."
            linked -> "The camera is switched off. Tap Wake Up or press its button."
            hasWakeUpId -> "Keep the camera nearby and tap Connect. Open360 switches it on if it is off."
            else -> "Turn the camera on, keep it nearby and tap Connect."
        }
    }

    /** A speed in the unit chosen, such as "32 km/h". */
    fun speed(kmh: Float, mph: Float, inMph: Boolean): String =
        if (inMph) String.format(Locale.US, "%.0f mph", mph) else String.format(Locale.US, "%.0f km/h", kmh)
}
