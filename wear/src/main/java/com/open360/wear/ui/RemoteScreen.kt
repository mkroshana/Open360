package com.open360.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraDetails
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.ui.camera.CameraButton
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.SpeedUnit
import com.open360.wear.WatchHeartRate

private val RecordingRed = Color(0xFFFF3B30)
private val ReadyGreen = Color(0xFF34C759)
private val StandbyOrange = Color(0xFFFF9500)

/** What the remote is for at the moment, which decides what it puts first. */
private enum class RemoteMode {
    // No camera to control: the link button is the one thing to do
    OFFLINE,
    // The camera is on: the shutter is the main control
    READY,
    // A recording or a timelapse, interval or starlapse run: its time and the stop
    RECORDING
}

/**
 * The remote. What it shows first follows the camera: the link button at the bottom edge while
 * there is no camera to control, the shutter once it is on, and the running time while it records.
 * Each says whether the watch has GPS. Below come what the camera reports, Disconnect and Settings.
 */
@Composable
fun RemoteScreen(
    viewModel: CameraViewModel,
    heartRate: WatchHeartRate,
    speedUnit: SpeedUnit,
    onOpenModes: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heart by heartRate.state.collectAsStateWithLifecycle()
    val inMph by speedUnit.inMph.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val gps = uiState.gpsState
    // Read again with each GPS update, which comes at least once a second while tracking
    val gpsReady = RemoteText.gpsReady(gps.hasFix, SystemClock.elapsedRealtime() - gps.fixElapsedRealtimeMs)
    val haptic = LocalHapticFeedback.current
    val listState = rememberTransformingLazyColumnState()
    // Items narrow and shrink as they reach the round screen's top and bottom edges
    val transformationSpec = rememberTransformationSpec()

    val cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake
    val mode = when {
        uiState.isRecording -> RemoteMode.RECORDING
        cameraOn -> RemoteMode.READY
        else -> RemoteMode.OFFLINE
    }
    // Each layout opens at its top: the list would otherwise keep its place from the one before
    LaunchedEffect(mode) { listState.scrollToItem(0) }
    val button = CameraButton.of(ble, uiState.isConnected)
    val cameraMode = ble.details.subModes?.let { CameraMode.of(it) }
    val shownMode = RemoteText.shownMode(uiState.currentMode, cameraMode)
    val busy = ble.isCaptureStarting || ble.isCaptureStopping
    val shutterLabel = RemoteText.shutter(uiState.currentMode, cameraMode, uiState.isRecording, ble.isCaptureStarting, ble.isCaptureStopping)
    // The camera last linked to, by the name it advertises, before this link names it
    val cameraName = remember(ble.deviceName) { ble.deviceName ?: "GO 3S ${viewModel.bleManager.getSavedSerialSuffix()}" }

    val onShutter = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.onShutterClicked()
    }
    val onLinkButton = {
        if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        viewModel.onCameraButton(button)
    }

    val content: @Composable BoxScope.(PaddingValues) -> Unit = { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (mode) {
                RemoteMode.OFFLINE -> {
                    item { OfflineHeader(ble, cameraName, RemoteText.status(ble, false, 0)) }
                    item { GpsStatusLine(gpsReady) }
                    RemoteText.hint(ble, hasWakeUpId = uiState.wakeUpId != null, isLookingForCamera = button == CameraButton.STOP)?.let { hint ->
                        item { Note(hint) }
                    }
                }
                RemoteMode.READY -> {
                    item { ReadyHeader(ble) }
                    item { Shutter(isVideo = uiState.currentMode == CaptureMode.VIDEO, isRecording = false, busy = busy, label = shutterLabel, onClick = onShutter) }
                    item { ShutterLabel(shutterLabel) }
                    item {
                        CompactButton(
                            onClick = onOpenModes,
                            enabled = !busy,
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.transformedHeight(this, transformationSpec),
                            icon = {
                                Icon(
                                    imageVector = shownMode?.let { modeIcon(it) } ?: modeIcon(uiState.currentMode),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text(shownMode?.label ?: uiState.currentMode.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                    item { GpsStatusLine(gpsReady) }
                    readyNotes(ble.details).forEach { line -> item { Note(line.text, isProblem = line.isProblem) } }
                }
                RemoteMode.RECORDING -> {
                    item { RecordingTimer(RemoteText.elapsed(uiState.recordingDurationSeconds)) }
                    item { Shutter(isVideo = true, isRecording = true, busy = busy, label = shutterLabel, onClick = onShutter) }
                    item { ShutterLabel(shutterLabel) }
                    item {
                        LiveReadings(
                            speed = if (gpsReady) RemoteText.speed(gps.speedKmh, gps.speedMph, inMph) else null,
                            heartRateBpm = heart.bpm
                        )
                    }
                    // The camera is sent void records until there is a fix again
                    if (!gpsReady) item { GpsStatusLine(ready = false) }
                }
            }
            if (mode != RemoteMode.OFFLINE) {
                cameraReport(ble.details, ble.lastVideoFile, ble.lastVideoSeconds).forEach { line ->
                    item { ReportRow(line) }
                }
            }
            (ble.lastError ?: uiState.trackState.lastError)?.let { error ->
                item { Note(error, isProblem = true) }
            }
            // Ending the link is rarely wanted while the camera is on, so it waits below the rest
            if (mode != RemoteMode.OFFLINE) {
                item {
                    OutlinedButton(
                        onClick = onLinkButton,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(linkIcon(button), contentDescription = null) },
                        label = { Text(button.label) }
                    )
                }
            }
            item {
                CompactButton(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Settings") }
                )
            }
        }
    }

    if (mode == RemoteMode.OFFLINE) {
        // The one thing to do: the link button on the bottom edge, visible without scrolling
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                EdgeButton(
                    onClick = onLinkButton,
                    buttonSize = EdgeButtonSize.Small,
                    colors = if (button == CameraButton.STOP) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()
                ) {
                    Icon(linkIcon(button), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(button.label)
                }
            },
            content = content
        )
    } else {
        ScreenScaffold(scrollState = listState, content = content)
    }
}

/** What the link is doing while there is no camera to control, and the camera it is for. */
@Composable
private fun OfflineHeader(ble: BleCameraState, cameraName: String, status: String) {
    val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
    val looking = ble.isWaking || ble.isReconnecting ||
        ble.connectionStatus == BleConnectionStatus.SCANNING || ble.connectionStatus == BleConnectionStatus.CONNECTING
    val icon = when {
        looking -> Icons.Filled.BluetoothSearching
        linked -> Icons.Filled.VideocamOff
        else -> Icons.Filled.BluetoothDisabled
    }
    // The icon beside the status rather than above it, so the list fits above the edge button
    Column(modifier = Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (linked && !looking) StandbyOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(text = status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Text(text = cameraName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Ready" and the camera's battery, above the shutter. */
@Composable
private fun ReadyHeader(ble: BleCameraState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Ready", style = MaterialTheme.typography.labelMedium, color = ReadyGreen)
        val battery = ble.batteryPercent
        if (battery != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = batteryIcon(battery, ble.isCharging),
                contentDescription = if (ble.isCharging) "Camera battery, charging" else "Camera battery",
                tint = if (battery <= CameraStatusText.LOW_BATTERY_PERCENT && !ble.isCharging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(text = "$battery%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Whether the watch has a GPS fix to send the camera: green when it has, amber while it waits. */
@Composable
private fun GpsStatusLine(ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ready) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
            contentDescription = null,
            tint = if (ready) ReadyGreen else StandbyOrange,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = RemoteText.gpsStatus(ready), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A recording's running time, large and red, in place of the status while it records. */
@Composable
private fun RecordingTimer(elapsed: String) {
    Text(text = elapsed, style = MaterialTheme.typography.numeralSmall, color = RecordingRed)
}

/** The watch's speed and heart rate while recording, each left out when there is none. */
@Composable
private fun LiveReadings(speed: String?, heartRateBpm: Int?) {
    if (speed == null && heartRateBpm == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (speed != null) {
            Icon(Icons.Filled.Speed, contentDescription = "Speed", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(text = speed, style = MaterialTheme.typography.labelMedium)
        }
        if (speed != null && heartRateBpm != null) Spacer(Modifier.width(12.dp))
        if (heartRateBpm != null) {
            Icon(Icons.Filled.Favorite, contentDescription = "Heart rate", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(text = "$heartRateBpm bpm", style = MaterialTheme.typography.labelMedium)
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
    label: String,
    onClick: () -> Unit
) {
    val ring = if (isVideo || isRecording) RecordingRed else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .clickable(enabled = !busy, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(44.dp))
            isRecording -> Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(RecordingRed))
            else -> Box(Modifier.size(64.dp).clip(CircleShape).background(if (isVideo) RecordingRed else Color.White))
        }
    }
}

@Composable
private fun ShutterLabel(label: String) {
    Text(text = label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
}

private fun linkIcon(button: CameraButton): ImageVector = when (button) {
    CameraButton.CONNECT -> Icons.Filled.Bluetooth
    CameraButton.STOP -> Icons.Filled.Close
    CameraButton.WAKE_UP -> Icons.Filled.PowerSettingsNew
    CameraButton.DISCONNECT -> Icons.Filled.BluetoothDisabled
}

/** The battery icon with as many bars as the camera has charge, or the charging one. */
private fun batteryIcon(percent: Int, charging: Boolean): ImageVector = when {
    charging -> Icons.Filled.BatteryChargingFull
    percent >= 95 -> Icons.Filled.BatteryFull
    percent >= 80 -> Icons.Filled.Battery6Bar
    percent >= 65 -> Icons.Filled.Battery5Bar
    percent >= 50 -> Icons.Filled.Battery4Bar
    percent >= 35 -> Icons.Filled.Battery3Bar
    percent >= 20 -> Icons.Filled.Battery2Bar
    percent >= 5 -> Icons.Filled.Battery1Bar
    else -> Icons.Filled.Battery0Bar
}

@Composable
private fun Note(text: String, isProblem: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/** A short line under the shutter, which may be a warning. */
private data class NoteLine(val text: String, val isProblem: Boolean = false)

/** Under the shutter: the camera's warnings first, then the recording time or photos left. */
private fun readyNotes(details: CameraDetails): List<NoteLine> = buildList {
    if (details.temperatureHigh) add(NoteLine("Camera Temperature High", isProblem = true))
    if (details.batteryLow) add(NoteLine("Camera Battery Low", isProblem = true))
    val storageProblem = CameraStatusText.storageProblem(details.cardState)
    if (storageProblem != null) add(NoteLine("Storage $storageProblem", isProblem = true))
    val left = details.captureSecondsLeft
    if (storageProblem == null && left != null) {
        if (details.subModes?.isPhoto == true) add(NoteLine("${CameraStatusText.photosLeft(left)} photos left"))
        else add(NoteLine("${CameraStatusText.timeLeft(left)} left"))
    }
}

/** One line of what the camera reports: a label and its value. */
private data class ReportLine(val label: String, val value: String, val isProblem: Boolean = false)

/** The rest of what the camera reports, below the controls: the pod, the recording mode and the last clip. */
private fun cameraReport(details: CameraDetails, lastVideoFile: String?, lastVideoSeconds: Int?): List<ReportLine> = buildList {
    details.pod?.let { add(ReportLine("Action Pod", CameraStatusText.pod(it), isProblem = CameraStatusText.podIsLow(it))) }
    CameraStatusText.recordingMode(details.recordingState)?.let { add(ReportLine("Recording Mode", it)) }
    if (lastVideoFile != null) {
        add(ReportLine("Last Clip", lastVideoSeconds?.let { CameraStatusText.clipLength(it) } ?: "Saved"))
    }
}

@Composable
private fun ReportRow(line: ReportLine) {
    // The label above its value, both centred, as a label and value side by side do not fit
    // the round screen's narrower rows
    Column(modifier = Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = line.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = line.value,
            style = MaterialTheme.typography.labelMedium,
            color = if (line.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}
