package com.open360.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
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
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ProgressIndicatorDefaults
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

// Above and below the shutter, each of a fixed height, so the shutter stays in the same place
// whether the camera is ready, taking a photo or recording
private val ABOVE_SHUTTER = 44.dp
private val BELOW_SHUTTER = 44.dp
// The buttons either side of the shutter
private val SIDE_BUTTON = 48.dp

/**
 * The remote. While there is no camera to control it is a short list with the link button at the
 * bottom edge. Once the camera is on it is two pages: the controls, which do not scroll, with the
 * shutter in the middle and the mode button beside it, and, a swipe to the left, what the camera
 * reports, Disconnect and Settings.
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

    val cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake
    val mode = when {
        uiState.isRecording -> RemoteMode.RECORDING
        cameraOn -> RemoteMode.READY
        else -> RemoteMode.OFFLINE
    }
    val button = CameraButton.of(ble, uiState.isConnected)
    val cameraMode = ble.details.subModes?.let { CameraMode.of(it) }
    val shownMode = RemoteText.shownMode(uiState.currentMode, cameraMode)
    // Waiting for the camera to confirm the last tap, a photo included: the shutter shows it and
    // takes no other tap until then
    val busy = ble.isCaptureStarting || ble.isCaptureStopping || ble.isTakingPhoto
    val shutterLabel = RemoteText.shutter(uiState.currentMode, cameraMode, uiState.isRecording, ble.isCaptureStarting, ble.isCaptureStopping, ble.isTakingPhoto)
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

    if (mode == RemoteMode.OFFLINE) {
        OfflineRemote(
            ble = ble,
            cameraName = cameraName,
            hint = RemoteText.hint(ble, hasWakeUpId = uiState.wakeUpId != null, isLookingForCamera = button == CameraButton.STOP),
            gpsReady = gpsReady,
            error = ble.lastError ?: uiState.trackState.lastError,
            button = button,
            onLinkButton = onLinkButton,
            onOpenSettings = onOpenSettings
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    // Back on the controls whenever a recording starts or stops
    LaunchedEffect(mode) { pagerState.animateScrollToPage(0) }
    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                if (page == 0) {
                    ScreenScaffold {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(
                                modifier = Modifier.height(ABOVE_SHUTTER).padding(horizontal = 36.dp),
                                verticalArrangement = Arrangement.Bottom,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (mode == RemoteMode.RECORDING) RecordingTimer(RemoteText.elapsed(uiState.recordingDurationSeconds))
                                else ReadyHeader(ble)
                                Text(
                                    text = shownMode?.label ?: uiState.currentMode.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ModeButton(
                                    icon = shownMode?.let { modeIcon(it) } ?: modeIcon(uiState.currentMode),
                                    modeName = shownMode?.label ?: uiState.currentMode.displayName,
                                    // Held while a recording runs, as the camera's mode is
                                    enabled = !busy && mode != RemoteMode.RECORDING,
                                    onClick = onOpenModes
                                )
                                Shutter(
                                    isVideo = uiState.currentMode == CaptureMode.VIDEO,
                                    isRecording = mode == RemoteMode.RECORDING,
                                    busy = busy,
                                    label = shutterLabel,
                                    onClick = onShutter
                                )
                                // Keeps the shutter in the middle
                                Spacer(Modifier.size(SIDE_BUTTON))
                            }
                            Column(
                                modifier = Modifier.height(BELOW_SHUTTER).padding(horizontal = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                ShutterLabel(shutterLabel)
                                UnderShutterLine(
                                    warning = cameraWarnings(ble.details).firstOrNull(),
                                    gpsReady = gpsReady,
                                    isRecording = mode == RemoteMode.RECORDING,
                                    left = captureLeft(ble.details),
                                    speed = if (gpsReady) RemoteText.speed(gps.speedKmh, gps.speedMph, inMph) else null,
                                    heartRateBpm = heart.bpm
                                )
                            }
                        }
                    }
                } else {
                    CameraPage(
                        ble = ble,
                        cameraName = cameraName,
                        gpsReady = gpsReady,
                        error = ble.lastError ?: uiState.trackState.lastError,
                        button = button,
                        onLinkButton = onLinkButton,
                        onOpenSettings = onOpenSettings
                    )
                }
            }
        }
    }
}

/** While there is no camera to control: the camera, what to do next, and the link button on the edge. */
@Composable
private fun OfflineRemote(
    ble: BleCameraState,
    cameraName: String,
    hint: String?,
    gpsReady: Boolean,
    error: String?,
    button: CameraButton,
    onLinkButton: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
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
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { OfflineHeader(ble, cameraName, RemoteText.status(ble, false, 0)) }
            hint?.let { item { Note(it) } }
            // GPS and Settings share one row, so the screen fits above the edge button
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GpsStatusLine(gpsReady)
                    Spacer(Modifier.width(12.dp))
                    FilledTonalIconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(IconButtonDefaults.ExtraSmallButtonSize)
                    ) {
                        // Small and muted, so it does not compete with the edge button
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            error?.let { item { Note(it, isProblem = true) } }
        }
    }
}

/**
 * The page a swipe to the left of the controls: what the camera reports, whether the watch has
 * GPS, and Disconnect and Settings, which are rarely wanted while the camera is on.
 */
@Composable
private fun CameraPage(
    ble: BleCameraState,
    cameraName: String,
    gpsReady: Boolean,
    error: String?,
    button: CameraButton,
    onLinkButton: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    // Items narrow and shrink as they reach the round screen's top and bottom edges
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text(cameraName) }
            }
            cameraWarnings(ble.details).forEach { warning -> item { Note(warning, isProblem = true) } }
            cameraReport(ble.details, ble.lastVideoFile, ble.lastVideoSeconds).forEach { line ->
                item { ReportRow(line) }
            }
            item { GpsStatusLine(gpsReady) }
            error?.let { item { Note(it, isProblem = true) } }
            item {
                OutlinedButton(
                    onClick = onLinkButton,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(linkIcon(button), contentDescription = null) },
                    label = { Text(button.label) }
                )
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
}

/** Beside the shutter: the camera's mode as its icon, which opens the list of modes. */
@Composable
private fun ModeButton(icon: ImageVector, modeName: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(SIDE_BUTTON)
    ) {
        Icon(imageVector = icon, contentDescription = "Mode: $modeName", modifier = Modifier.size(24.dp))
    }
}

/**
 * The one line under the shutter's label, whichever matters most: a camera warning, then the
 * watch waiting for GPS, as the camera is sent void records until there is a fix, then speed and
 * heart rate while recording, or the time or photos left.
 */
@Composable
private fun UnderShutterLine(
    warning: String?,
    gpsReady: Boolean,
    isRecording: Boolean,
    left: String?,
    speed: String?,
    heartRateBpm: Int?
) {
    when {
        warning != null -> Note(warning, isProblem = true)
        !gpsReady -> GpsStatusLine(ready = false)
        isRecording -> LiveReadings(speed, heartRateBpm)
        left != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = RemoteText.gpsStatus(true),
                tint = ReadyGreen,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(text = left, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        else -> GpsStatusLine(ready = true)
    }
}

/**
 * While there is no camera to control: the camera as a badge, which spins while it is looked for,
 * linked to or woken, the status as the title, coloured by what it means, and the camera's name.
 */
@Composable
private fun OfflineHeader(ble: BleCameraState, cameraName: String, status: String) {
    val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
    val looking = ble.isWaking || ble.isReconnecting ||
        ble.connectionStatus == BleConnectionStatus.SCANNING || ble.connectionStatus == BleConnectionStatus.CONNECTING
    // Amber for a camera that is off or being switched on, the theme's colour while looking for it
    val statusColor = when {
        ble.isWaking || (linked && !looking) -> StandbyOrange
        looking -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CameraBadge(
            icon = if (linked && !looking) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
            iconTint = if (linked && !looking) StandbyOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            spinning = looking,
            ringColor = statusColor
        )
        Spacer(Modifier.size(6.dp))
        Text(text = status, style = MaterialTheme.typography.titleMedium, color = statusColor, textAlign = TextAlign.Center)
        Text(text = cameraName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The camera's icon in a circle, with a spinning ring around it while something is under way. */
@Composable
private fun CameraBadge(icon: ImageVector, iconTint: Color, spinning: Boolean, ringColor: Color) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                colors = ProgressIndicatorDefaults.colors(indicatorColor = ringColor),
                strokeWidth = 3.dp
            )
        }
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
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
    Text(text = elapsed, style = MaterialTheme.typography.numeralExtraSmall, color = RecordingRed)
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

/** The camera's warnings, most pressing first. */
private fun cameraWarnings(details: CameraDetails): List<String> = buildList {
    if (details.temperatureHigh) add("Camera Temperature High")
    if (details.batteryLow) add("Camera Battery Low")
    CameraStatusText.storageProblem(details.cardState)?.let { add("Storage $it") }
}

/** The recording time or photos left, as in "1 h 52 min left"; null with a storage problem. */
private fun captureLeft(details: CameraDetails): String? {
    if (CameraStatusText.storageProblem(details.cardState) != null) return null
    val left = details.captureSecondsLeft ?: return null
    return if (details.subModes?.isPhoto == true) "${CameraStatusText.photosLeft(left)} photos left"
    else "${CameraStatusText.timeLeft(left)} left"
}

/** One line of what the camera reports: a label and its value. */
private data class ReportLine(val label: String, val value: String, val isProblem: Boolean = false)

/**
 * What the camera reports, on the page beside the controls: the time or photos left, the pod and
 * the last clip. Its mode is left out, as the controls show it.
 */
private fun cameraReport(details: CameraDetails, lastVideoFile: String?, lastVideoSeconds: Int?): List<ReportLine> = buildList {
    if (CameraStatusText.storageProblem(details.cardState) == null) {
        details.captureSecondsLeft?.let { left ->
            if (details.subModes?.isPhoto == true) add(ReportLine("Photos Left", CameraStatusText.photosLeft(left)))
            else add(ReportLine("Time Left", CameraStatusText.timeLeft(left)))
        }
    }
    details.pod?.let { add(ReportLine("Action Pod", CameraStatusText.pod(it), isProblem = CameraStatusText.podIsLow(it))) }
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
