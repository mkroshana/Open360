package com.open360.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.ui.camera.CameraButton
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraViewModel

private val RecordingRed = Color(0xFFFF3B30)
private val ConnectedGreen = Color(0xFF34C759)
private val StandbyOrange = Color(0xFFFF9500)

/**
 * The remote: what the camera is doing, the shutter, its mode, the one link button, what the
 * camera reports about itself and the watch's GPS.
 */
@Composable
fun RemoteScreen(
    viewModel: CameraViewModel,
    onOpenModes: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val gps = uiState.gpsState
    val haptic = LocalHapticFeedback.current
    val listState = rememberTransformingLazyColumnState()

    val cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake
    val button = CameraButton.of(ble, uiState.isConnected)
    val cameraMode = ble.details.subModes?.let { CameraMode.of(it) }
    val shownMode = RemoteText.shownMode(uiState.currentMode, cameraMode)
    val busy = ble.isCaptureStarting || ble.isCaptureStopping
    val shutterLabel = RemoteText.shutter(uiState.currentMode, cameraMode, uiState.isRecording, ble.isCaptureStarting, ble.isCaptureStopping)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                StatusHeader(
                    status = RemoteText.status(ble, uiState.isRecording, uiState.recordingDurationSeconds),
                    color = when {
                        uiState.isRecording -> RecordingRed
                        cameraOn -> ConnectedGreen
                        ble.connectionStatus == BleConnectionStatus.CONNECTED -> StandbyOrange
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    battery = ble.batteryPercent?.let { "Camera $it%${if (ble.isCharging) ", Charging" else ""}" }
                )
            }
            item {
                Shutter(
                    isVideo = uiState.currentMode == CaptureMode.VIDEO,
                    isRecording = uiState.isRecording,
                    busy = busy,
                    enabled = cameraOn,
                    label = shutterLabel,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.onShutterClicked()
                    }
                )
            }
            item {
                Text(
                    text = shutterLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (cameraOn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            item {
                val modeLocked = uiState.isRecording || busy
                FilledTonalButton(
                    onClick = onOpenModes,
                    enabled = !modeLocked,
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = if (uiState.currentMode == CaptureMode.PHOTO) Icons.Filled.PhotoCamera else Icons.Filled.Videocam,
                            contentDescription = null
                        )
                    },
                    secondaryLabel = { Text(if (modeLocked) "Held While Recording" else "Mode") },
                    label = { Text(shownMode?.label ?: uiState.currentMode.displayName) }
                )
            }
            item {
                LinkButton(
                    button = button,
                    onClick = {
                        if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        viewModel.onCameraButton(button)
                    }
                )
            }
            RemoteText.hint(ble, hasWakeUpId = uiState.wakeUpId != null, isLookingForCamera = button == CameraButton.STOP)?.let { hint ->
                item { Note(hint) }
            }
            if (cameraOn) {
                cameraReport(ble.details, ble.lastVideoFile, ble.lastVideoSeconds).forEach { line ->
                    item { ReportRow(line) }
                }
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.toggleSpeedUnit() },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(if (gps.hasFix) Icons.Filled.LocationOn else Icons.Filled.LocationOff, contentDescription = null) },
                    secondaryLabel = {
                        Text(
                            when {
                                !gps.hasFix -> "No GPS Fix"
                                uiState.isRecording -> "Sending GPS to the Camera"
                                else -> "GPS Fix, ${gps.satellitesInFix} Satellites"
                            }
                        )
                    },
                    label = { Text(if (gps.hasFix) RemoteText.speed(gps.speedKmh, gps.speedMph, uiState.isSpeedMph) else "GPS") }
                )
            }
            (ble.lastError ?: uiState.trackState.lastError)?.let { error ->
                item { Note(error, isProblem = true) }
            }
            item {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(status: String, color: Color, battery: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = status, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
        if (battery != null) {
            Text(text = battery, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The shutter, drawn as a camera's: a red disc for video, a white one for a photo, and a red
 * square to stop.
 */
@Composable
private fun Shutter(
    isVideo: Boolean,
    isRecording: Boolean,
    busy: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val ring = if (isVideo || isRecording) RecordingRed else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(80.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .clickable(enabled = enabled && !busy, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(44.dp))
            isRecording -> Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(RecordingRed))
            else -> Box(Modifier.size(60.dp).clip(CircleShape).background(if (isVideo) RecordingRed else Color.White))
        }
    }
}

/** The camera card's one link button, as on the phone. */
@Composable
private fun LinkButton(button: CameraButton, onClick: () -> Unit) {
    val icon: ImageVector = when (button) {
        CameraButton.CONNECT -> Icons.Filled.Bluetooth
        CameraButton.STOP -> Icons.Filled.Stop
        CameraButton.WAKE_UP -> Icons.Filled.PowerSettingsNew
        CameraButton.DISCONNECT -> Icons.Filled.BluetoothDisabled
    }
    if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(button.label) }
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(button.label) }
        )
    }
}

@Composable
private fun Note(text: String, isProblem: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

/** One line of what the camera reports: a label and its value. */
private data class ReportLine(val label: String, val value: String, val isProblem: Boolean = false)

/** The phone's camera details, warnings first: storage, time or photos left, the pod, the last clip. */
private fun cameraReport(
    details: com.open360.core.ble.CameraDetails,
    lastVideoFile: String?,
    lastVideoSeconds: Int?
): List<ReportLine> = buildList {
    if (details.temperatureHigh) add(ReportLine("Temperature", "High", isProblem = true))
    if (details.batteryLow) add(ReportLine("Camera Battery", "Low", isProblem = true))
    val storageProblem = CameraStatusText.storageProblem(details.cardState)
    if (storageProblem != null) add(ReportLine("Storage", storageProblem, isProblem = true))
    val left = details.captureSecondsLeft
    if (storageProblem == null && left != null) {
        if (details.subModes?.isPhoto == true) add(ReportLine("Photos Left", CameraStatusText.photosLeft(left)))
        else add(ReportLine("Time Left", CameraStatusText.timeLeft(left)))
    }
    details.pod?.let { add(ReportLine("Action Pod", CameraStatusText.pod(it), isProblem = CameraStatusText.podIsLow(it))) }
    CameraStatusText.recordingMode(details.recordingState)?.let { add(ReportLine("Recording Mode", it)) }
    if (lastVideoFile != null) {
        add(ReportLine("Last Clip", lastVideoSeconds?.let { CameraStatusText.clipLength(it) } ?: "Saved"))
    }
}

@Composable
private fun ReportRow(line: ReportLine) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = line.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = line.value,
            style = MaterialTheme.typography.labelMedium,
            color = if (line.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
