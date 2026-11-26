package com.open360.wear.ui

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.OutlinedIconButton
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
import com.open360.core.model.Insta360CameraModel
import com.open360.core.ui.Insta360Icons
import com.open360.ui.camera.CameraButton
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.SpeedUnit
import com.open360.wear.WatchHeartRate
import com.open360.wear.ui.theme.WearCameraModelAvatar
import com.open360.wear.ui.theme.WearMetricPill
import com.open360.wear.ui.theme.WearStatusDot
import com.open360.wear.ui.theme.WearSurfaceCard
import com.open360.wear.ui.theme.WearTabularFigures
import com.open360.wear.ui.theme.WearTactileShutterButton
import com.open360.wear.ui.theme.rememberWearPressScale
import com.open360.wear.ui.theme.wearEditorialOutlinedButtonColors
import com.open360.wear.ui.theme.wearEditorialTonalButtonColors
import com.open360.wear.ui.theme.wearMiddleEllipsize
import com.open360.wear.ui.theme.wearStatusColors

/** What the remote is for at the moment, which decides what it puts first. */
private enum class RemoteMode {
    OFFLINE,
    READY,
    RECORDING
}

// Above and below the shutter, each of a fixed height, so the shutter stays in the same place
// whether the camera is ready, taking a photo or recording.
private val ABOVE_SHUTTER = 44.dp
private val BELOW_SHUTTER = 46.dp
private val SIDE_BUTTON = 48.dp

/**
 * The remote. While there is no camera to control it is a short list with the link button at the
 * bottom edge. Once the camera is on it is two pages: the controls, which do not scroll, with the
 * shutter in the middle between the mode and a Video and Photo switch, and, a swipe to the left,
 * what the camera reports, Disconnect and Settings.
 */
@Composable
fun RemoteScreen(
    viewModel: CameraViewModel,
    heartRate: WatchHeartRate,
    speedUnit: SpeedUnit,
    onOpenModes: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heart by heartRate.state.collectAsStateWithLifecycle()
    val sensors by viewModel.sportsSensors.state.collectAsStateWithLifecycle()
    val inMph by speedUnit.inMph.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val gps = uiState.gpsState
    // Read again with each GPS update, which comes at least once a second while tracking
    val gpsReady = RemoteText.gpsReady(gps.hasFix, SystemClock.elapsedRealtime() - gps.fixElapsedRealtimeMs)
    val haptic = LocalHapticFeedback.current
    val liveHeartRateBpm = heart.bpm
        ?: sensors.saved.firstOrNull { it.connected && it.heartRateBpm != null }?.heartRateBpm

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
    // takes no other tap until then.
    val busy = ble.isCaptureStarting || ble.isCaptureStopping || ble.isTakingPhoto || uiState.linkMove != null
    val shutterLabel = RemoteText.shutter(
        uiState.currentMode,
        cameraMode,
        uiState.isRecording,
        ble.isCaptureStarting,
        ble.isCaptureStopping,
        ble.isTakingPhoto
    )
    // The camera last linked to, by the name it advertises, before this link names it.
    val cameraName = remember(ble.deviceName) {
        ble.deviceName
            ?: viewModel.bleManager.getSavedSerialSuffix().takeIf { it.isNotBlank() }?.let { "GO 3S $it" }
            ?: "Insta360 Camera"
    }

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
            hint = RemoteText.hint(
                ble,
                hasWakeUpId = uiState.wakeUpId != null,
                isLookingForCamera = button == CameraButton.STOP,
                throughPhone = uiState.linkedThroughPeer
            ),
            gpsReady = gpsReady,
            error = ble.lastError ?: uiState.trackState.lastError,
            button = button,
            isMovingLink = uiState.linkMove != null,
            onLinkButton = onLinkButton,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    // Back on the controls whenever a recording starts or stops
    LaunchedEffect(mode) { pagerState.animateScrollToPage(0) }
    HorizontalPagerScaffold(pagerState = pagerState, modifier = modifier) {
        HorizontalPager(state = pagerState) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                if (page == 0) {
                    ScreenScaffold {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(
                                modifier = Modifier
                                    .height(ABOVE_SHUTTER)
                                    .padding(horizontal = 24.dp),
                                verticalArrangement = Arrangement.Bottom,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (mode == RemoteMode.RECORDING) {
                                    RecordingTimer(RemoteText.elapsed(uiState.recordingDurationSeconds))
                                } else {
                                    ReadyHeader(ble = ble, throughPhone = uiState.linkedThroughPeer)
                                }
                                Text(
                                    text = (if (mode == RemoteMode.RECORDING) {
                                        CameraStatusText.recordingMode(ble.details.recordingState)
                                    } else {
                                        null
                                    }) ?: shownMode?.label ?: uiState.currentMode.displayName,
                                    style = MaterialTheme.typography.bodySmall,
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
                                    drawableRes = shownMode?.let { Insta360Icons.modeDrawableRes(it) }
                                        ?: Insta360Icons.captureModeDrawableRes(uiState.currentMode),
                                    modeName = shownMode?.label ?: uiState.currentMode.displayName,
                                    enabled = !busy && mode != RemoteMode.RECORDING,
                                    onClick = onOpenModes
                                )
                                WearTactileShutterButton(
                                    currentMode = uiState.currentMode,
                                    isRecording = mode == RemoteMode.RECORDING,
                                    isBusy = busy,
                                    enabled = !busy,
                                    contentDescription = shutterLabel,
                                    onClick = onShutter,
                                    size = 84.dp
                                )
                                PhotoVideoSwitch(
                                    current = uiState.currentMode,
                                    enabled = !busy && mode != RemoteMode.RECORDING,
                                    onSwitch = { viewModel.setMode(it) }
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .height(BELOW_SHUTTER)
                                    .padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.Top,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                ShutterLabel(shutterLabel)
                                Spacer(Modifier.height(2.dp))
                                UnderShutterLine(
                                    warning = cameraWarnings(ble.details).firstOrNull(),
                                    gpsReady = gpsReady,
                                    isRecording = mode == RemoteMode.RECORDING,
                                    left = captureLeft(ble.details),
                                    speed = if (gpsReady) RemoteText.speed(gps.speedKmh, gps.speedMph, inMph) else null,
                                    heartRateBpm = liveHeartRateBpm
                                )
                            }
                        }
                    }
                } else {
                    CameraPage(
                        ble = ble,
                        cameraName = cameraName,
                        isRecording = mode == RemoteMode.RECORDING,
                        throughPhone = uiState.linkedThroughPeer,
                        gpsReady = gpsReady,
                        error = ble.lastError ?: uiState.trackState.lastError,
                        button = button,
                        canMoveLink = uiState.canMoveLink,
                        onMoveLink = { viewModel.moveLink() },
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
    isMovingLink: Boolean,
    onLinkButton: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberTransformingLazyColumnState()
    val settingsInteraction = remember { MutableInteractionSource() }
    val settingsScale by rememberWearPressScale(settingsInteraction, targetScale = 0.94f)

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier,
        edgeButton = {
            EdgeButton(
                onClick = onLinkButton,
                buttonSize = EdgeButtonSize.Small,
                enabled = !isMovingLink,
                colors = if (button == CameraButton.STOP) {
                    wearEditorialTonalButtonColors()
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(linkIcon(button), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isMovingLink) "Moving..." else button.label)
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                OfflineHeader(
                    ble = ble,
                    cameraName = cameraName,
                    status = if (isMovingLink) RemoteText.MOVING_LINK else RemoteText.status(ble, false, 0),
                    isMovingLink = isMovingLink
                )
            }
            hint?.let { item { Note(it) } }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    WearMetricPill(
                        text = RemoteText.gpsStatus(gpsReady),
                        icon = if (gpsReady) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
                        iconTint = if (gpsReady) MaterialTheme.wearStatusColors.ready else MaterialTheme.wearStatusColors.waiting
                    )
                    Spacer(Modifier.width(10.dp))
                    FilledTonalIconButton(
                        onClick = onOpenSettings,
                        interactionSource = settingsInteraction,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        ),
                        modifier = Modifier
                            .size(IconButtonDefaults.ExtraSmallButtonSize)
                            .graphicsLayer {
                                scaleX = settingsScale
                                scaleY = settingsScale
                            }
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            error?.let {
                item {
                    WearMetricPill(
                        text = it,
                        icon = Icons.Default.Warning,
                        isProblem = true,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/**
 * The page a swipe to the left of the controls: what the camera reports, whether the watch has
 * GPS, moving the link between the watch and the phone, and Disconnect and Settings.
 */
@Composable
private fun CameraPage(
    ble: BleCameraState,
    cameraName: String,
    isRecording: Boolean,
    throughPhone: Boolean,
    gpsReady: Boolean,
    error: String?,
    button: CameraButton,
    canMoveLink: Boolean,
    onMoveLink: () -> Unit,
    onLinkButton: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val displayModel = ble.resolvedModel ?: Insta360CameraModel.GO_3S
    val dotState = CameraStatusText.statusDotState(
        isRecording = isRecording,
        isConnected = ble.connectionStatus == BleConnectionStatus.CONNECTED,
        isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake,
        isAdvertising = false,
        bleScanning = ble.connectionStatus == BleConnectionStatus.SCANNING,
        bleConnecting = ble.connectionStatus == BleConnectionStatus.CONNECTING || ble.isReconnecting || ble.isWaking
    )
    val telemetryItems = buildCameraTelemetryRows(ble)

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
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        WearCameraModelAvatar(
                            model = displayModel,
                            size = 28.dp,
                            reserveRingSpace = false
                        )
                        Text(
                            text = cameraName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        WearStatusDot(dotState = dotState, size = 7.dp)
                    }
                }
            }
            if (throughPhone) {
                item {
                    WearMetricPill(
                        text = "Through your phone",
                        icon = Icons.Filled.Smartphone
                    )
                }
            }
            cameraWarnings(ble.details).forEach { warning ->
                item {
                    WearMetricPill(
                        text = warning,
                        icon = if (warning.startsWith("Storage")) null else Icons.Default.Warning,
                        iconRes = if (warning.startsWith("Storage")) Insta360Icons.icSdError else null,
                        isProblem = true,
                        maxLines = 2
                    )
                }
            }
            if (telemetryItems.isNotEmpty()) {
                item {
                    WearSurfaceCard(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        telemetryItems.forEachIndexed { index, row ->
                            if (index > 0) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                )
                            }
                            TelemetryRowView(row = row)
                        }
                    }
                }
            }
            item {
                WearMetricPill(
                    text = RemoteText.gpsStatus(gpsReady),
                    icon = if (gpsReady) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
                    iconTint = if (gpsReady) MaterialTheme.wearStatusColors.ready else MaterialTheme.wearStatusColors.waiting
                )
            }
            error?.let {
                item {
                    WearMetricPill(
                        text = it,
                        icon = Icons.Default.Warning,
                        isProblem = true,
                        maxLines = 2
                    )
                }
            }
            if (throughPhone || canMoveLink) {
                item {
                    FilledTonalButton(
                        onClick = onMoveLink,
                        enabled = canMoveLink,
                        colors = wearEditorialTonalButtonColors(),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(if (throughPhone) Icons.Filled.Watch else Icons.Filled.Smartphone, contentDescription = null) },
                        label = { Text(if (throughPhone) "Move Here" else "Move to Phone") }
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onLinkButton,
                    colors = wearEditorialOutlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(linkIcon(button), contentDescription = null) },
                    label = { Text(button.label) }
                )
            }
            item {
                CompactButton(
                    onClick = onOpenSettings,
                    colors = wearEditorialTonalButtonColors(),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Settings") }
                )
            }
        }
    }
}

/** Beside the shutter: the camera's mode glyph inside a hairline capsule, opening the mode list. */
@Composable
private fun ModeButton(
    @DrawableRes drawableRes: Int,
    modeName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale by rememberWearPressScale(interactionSource, targetScale = 0.94f)
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    }

    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(width = 1.dp, color = borderColor),
        modifier = modifier
            .size(SIDE_BUTTON)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = "Mode: $modeName",
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Beside the shutter, across from the mode: one tap between Video and Photo, showing the capture
 * mode it switches to.
 */
@Composable
private fun PhotoVideoSwitch(
    current: CaptureMode,
    enabled: Boolean,
    onSwitch: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val other = if (current == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale by rememberWearPressScale(interactionSource, targetScale = 0.94f)
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }

    OutlinedIconButton(
        onClick = { onSwitch(other) },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(width = 1.dp, color = borderColor),
        modifier = modifier
            .size(SIDE_BUTTON)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        Icon(
            painter = painterResource(Insta360Icons.captureModeDrawableRes(other)),
            contentDescription = "Switch to ${other.displayName}",
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * The one line under the shutter's label, whichever matters most: a camera warning, then the
 * watch waiting for GPS, then speed and heart rate while recording, or remaining capacity.
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
    val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
    val pillTextStyle = MaterialTheme.typography.bodySmall
    when {
        warning != null -> WearMetricPill(
            text = warning,
            icon = if (warning.startsWith("Storage")) null else Icons.Default.Warning,
            iconRes = if (warning.startsWith("Storage")) Insta360Icons.icSdError else null,
            isProblem = true,
            contentPadding = compactPadding,
            textStyle = pillTextStyle
        )
        !gpsReady -> WearMetricPill(
            text = RemoteText.gpsStatus(false),
            icon = Icons.Filled.LocationOff,
            iconTint = MaterialTheme.wearStatusColors.waiting,
            contentPadding = compactPadding,
            textStyle = pillTextStyle
        )
        isRecording -> LiveReadingsPill(speed = speed, heartRateBpm = heartRateBpm)
        left != null -> WearMetricPill(
            text = left,
            icon = Icons.Filled.LocationOn,
            iconTint = MaterialTheme.wearStatusColors.ready,
            contentPadding = compactPadding,
            textStyle = pillTextStyle
        )
        else -> WearMetricPill(
            text = RemoteText.gpsStatus(true),
            icon = Icons.Filled.LocationOn,
            iconTint = MaterialTheme.wearStatusColors.ready,
            contentPadding = compactPadding,
            textStyle = pillTextStyle
        )
    }
}

@Composable
private fun LiveReadingsPill(
    speed: String?,
    heartRateBpm: Int?,
    modifier: Modifier = Modifier
) {
    if (speed == null && heartRateBpm == null) {
        WearMetricPill(
            text = RemoteText.gpsStatus(true),
            icon = Icons.Filled.LocationOn,
            iconTint = MaterialTheme.wearStatusColors.ready,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = modifier
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = CircleShape
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        if (speed != null) {
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = "Speed",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = speed,
                style = MaterialTheme.typography.bodySmall.merge(WearTabularFigures),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
        if (speed != null && heartRateBpm != null) {
            Spacer(Modifier.width(2.dp))
        }
        if (heartRateBpm != null) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Heart rate",
                tint = MaterialTheme.wearStatusColors.recording,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "$heartRateBpm bpm",
                style = MaterialTheme.typography.bodySmall.merge(WearTabularFigures),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * While there is no camera to control: the resolved camera model avatar (with activity ring while
 * looking, connecting, or waking), editorial status pill with [WearStatusDot], and camera name.
 */
@Composable
private fun OfflineHeader(
    ble: BleCameraState,
    cameraName: String,
    status: String,
    isMovingLink: Boolean = false
) {
    val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
    val looking = isMovingLink || ble.isWaking || ble.isReconnecting ||
        ble.connectionStatus == BleConnectionStatus.SCANNING || ble.connectionStatus == BleConnectionStatus.CONNECTING
    val displayModel = ble.resolvedModel ?: Insta360CameraModel.GO_3S
    val dotState = CameraStatusText.statusDotState(
        isRecording = false,
        isConnected = linked,
        isAsleep = linked && !ble.isCameraAwake,
        isAdvertising = false,
        bleScanning = ble.connectionStatus == BleConnectionStatus.SCANNING,
        bleConnecting = looking
    )
    val statusColor = when (dotState) {
        CameraStatusText.StatusDotState.STANDBY -> MaterialTheme.wearStatusColors.waiting
        CameraStatusText.StatusDotState.CONNECTING -> MaterialTheme.colorScheme.primary
        CameraStatusText.StatusDotState.CONNECTED -> MaterialTheme.wearStatusColors.ready
        CameraStatusText.StatusDotState.RECORDING -> MaterialTheme.wearStatusColors.recording
        CameraStatusText.StatusDotState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
    }
    val ringColor = when (dotState) {
        CameraStatusText.StatusDotState.STANDBY -> MaterialTheme.wearStatusColors.waiting
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WearCameraModelAvatar(
            model = displayModel,
            size = 48.dp,
            spinning = looking,
            reserveRingSpace = true,
            ringColor = ringColor
        )
        Spacer(Modifier.height(6.dp))
        WearMetricPill(
            text = status,
            dotState = dotState,
            textColor = statusColor,
            textStyle = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = cameraName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Ready indicator dot, compact camera battery readout, and phone link icon above the shutter. */
@Composable
private fun ReadyHeader(ble: BleCameraState, throughPhone: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        WearStatusDot(
            dotState = CameraStatusText.StatusDotState.CONNECTED,
            size = 7.dp
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "Ready",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.wearStatusColors.ready,
            maxLines = 1,
            softWrap = false
        )
        val battery = ble.batteryPercent
        if (battery != null) {
            val batteryLow = battery <= CameraStatusText.LOW_BATTERY_PERCENT && !ble.isCharging
            val batteryColor = when {
                batteryLow -> MaterialTheme.colorScheme.error
                ble.isCharging -> MaterialTheme.wearStatusColors.ready
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Spacer(Modifier.width(6.dp))
            if (ble.isCharging) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = "Camera battery, charging",
                    tint = batteryColor,
                    modifier = Modifier.size(13.dp)
                )
            } else {
                Icon(
                    painter = painterResource(Insta360Icons.icBattery),
                    contentDescription = "Camera battery",
                    tint = batteryColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            Text(
                text = "$battery%",
                style = MaterialTheme.typography.labelSmall.merge(WearTabularFigures),
                color = if (batteryLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        if (throughPhone) {
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Filled.Smartphone,
                contentDescription = "Through your phone",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

/** Pulsing recording dot and bold tabular running timer above the shutter while recording. */
@Composable
private fun RecordingTimer(elapsed: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        WearStatusDot(
            dotState = CameraStatusText.StatusDotState.RECORDING,
            size = 7.dp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = elapsed,
            style = MaterialTheme.typography.numeralExtraSmall.merge(WearTabularFigures),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.wearStatusColors.recording,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ShutterLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun linkIcon(button: CameraButton): ImageVector = when (button) {
    CameraButton.CONNECT -> Icons.Filled.Bluetooth
    CameraButton.STOP -> Icons.Filled.Close
    CameraButton.WAKE_UP -> Icons.Filled.PowerSettingsNew
    CameraButton.DISCONNECT -> Icons.Filled.BluetoothDisabled
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
    isProblem: Boolean = false
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 16.dp)
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
    return if (details.subModes?.isPhoto == true) {
        "${CameraStatusText.photosLeft(left)} photos left"
    } else {
        "${CameraStatusText.timeLeft(left)} left"
    }
}

private data class TelemetryRow(
    val label: String,
    val value: String,
    val icon: ImageVector? = null,
    @param:DrawableRes val iconRes: Int? = null,
    val isProblem: Boolean = false,
    val isHealthy: Boolean = false
)

private fun buildCameraTelemetryRows(ble: BleCameraState): List<TelemetryRow> = buildList {
    val details = ble.details
    ble.batteryPercent?.let { percent ->
        val batteryLow = percent <= CameraStatusText.LOW_BATTERY_PERCENT && !ble.isCharging
        add(
            TelemetryRow(
                label = "Battery",
                value = if (ble.isCharging) "$percent% • Charging" else "$percent%",
                icon = if (ble.isCharging) Icons.Filled.BatteryChargingFull else null,
                iconRes = if (!ble.isCharging) Insta360Icons.icBattery else null,
                isProblem = batteryLow,
                isHealthy = !batteryLow
            )
        )
    }
    details.pod?.let { pod ->
        val podLow = CameraStatusText.podIsLow(pod)
        val onExternal = pod.battery?.onExternalPower == true
        add(
            TelemetryRow(
                label = "Action Pod",
                value = CameraStatusText.pod(pod),
                icon = if (onExternal) Icons.Filled.BatteryChargingFull else Icons.Filled.Bolt,
                isProblem = podLow,
                isHealthy = onExternal
            )
        )
    }
    if (CameraStatusText.storageProblem(details.cardState) == null) {
        details.captureSecondsLeft?.let { left ->
            if (details.subModes?.isPhoto == true) {
                add(
                    TelemetryRow(
                        label = "Photos Left",
                        value = CameraStatusText.photosLeft(left),
                        iconRes = Insta360Icons.captureModeDrawableRes(CaptureMode.PHOTO)
                    )
                )
            } else {
                add(
                    TelemetryRow(
                        label = "Time Left",
                        value = CameraStatusText.timeLeft(left),
                        icon = Icons.Filled.Schedule
                    )
                )
            }
        }
        val freeBytes = details.freeSpaceBytes
        val totalBytes = details.totalSpaceBytes
        if (freeBytes != null && totalBytes != null) {
            add(
                TelemetryRow(
                    label = "Storage",
                    value = CameraStatusText.storageSpace(freeBytes, totalBytes),
                    iconRes = Insta360Icons.icSdOk
                )
            )
        }
    }
    ble.lastVideoFile?.let { file ->
        val len = ble.lastVideoSeconds?.let { CameraStatusText.clipLength(it) } ?: "Saved"
        add(
            TelemetryRow(
                label = "Last Clip • $len",
                value = wearMiddleEllipsize(file, 22),
                icon = Icons.Filled.Movie
            )
        )
    }
}

@Composable
private fun TelemetryRowView(row: TelemetryRow) {
    val iconTint = when {
        row.isProblem -> MaterialTheme.colorScheme.error
        row.isHealthy -> MaterialTheme.wearStatusColors.ready
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val valueColor = if (row.isProblem) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (row.iconRes != null) {
            Icon(
                painter = painterResource(row.iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        } else if (row.icon != null) {
            Icon(
                imageVector = row.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.value,
                style = MaterialTheme.typography.labelMedium.merge(WearTabularFigures),
                fontWeight = FontWeight.Medium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
