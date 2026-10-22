package com.open360.wear.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode

/** The icon for each of the camera's modes, picked for what the mode does. */
fun modeIcon(mode: CameraMode): ImageVector = when (mode) {
    CameraMode.VIDEO -> Icons.Filled.Videocam
    // Framing chosen after shooting
    CameraMode.FREEFRAME_PRO -> Icons.Filled.CropFree
    // A sped-up clip
    CameraMode.TIMESHIFT -> Icons.Filled.FastForward
    CameraMode.TIMELAPSE -> Icons.Filled.Timelapse
    CameraMode.SLOW_MOTION -> Icons.Filled.SlowMotionVideo
    CameraMode.LOOP_RECORDING -> Icons.Filled.Loop
    CameraMode.PHOTO -> Icons.Filled.PhotoCamera
    CameraMode.HDR_PHOTO -> Icons.Filled.HdrOn
    // A photo every few seconds
    CameraMode.INTERVAL_PHOTO -> Icons.Filled.Timer
    // Long exposures of the night sky
    CameraMode.STARLAPSE -> Icons.Filled.Bedtime
}

/** The icon for Video or Photo, before the camera has said which of their modes it is in. */
fun modeIcon(choice: CaptureMode): ImageVector =
    if (choice == CaptureMode.PHOTO) Icons.Filled.PhotoCamera else Icons.Filled.Videocam
