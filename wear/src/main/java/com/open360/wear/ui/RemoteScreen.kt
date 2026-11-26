package com.open360.wear.ui

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PedalBike
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.touchTargetAwareSize
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
import com.open360.wear.ui.theme.WearEaseOutCubic
import com.open360.wear.ui.theme.WearMetricPill
import com.open360.wear.ui.theme.WearStatusDot
import com.open360.wear.ui.theme.WearSurfaceCard
import com.open360.wear.ui.theme.WearTabularFigures
import com.open360.wear.ui.theme.WearTactileShutterButton
import com.open360.wear.ui.theme.rememberWearPressScale
import com.open360.wear.ui.theme.rememberWearRecordingPulseAlpha
import com.open360.wear.ui.theme.rememberWearReducedMotion
import com.open360.wear.ui.theme.wearEditorialOutlinedButtonColors
import com.open360.wear.ui.theme.wearEditorialTonalButtonColors
import com.open360.wear.ui.theme.wearMiddleEllipsize
import com.open360.wear.ui.theme.wearStatusColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the remote is for at the moment, which decides what it puts first. */
private enum class RemoteMode {
    OFFLINE,
    READY,
    RECORDING
}

internal data class WearControlsMetrics(
    val shutterSize: Dp,
    val sideButtonSize: Dp,
    val rowGap: Dp,
    val aboveHeight: Dp,
    val belowHeight: Dp,
    val verticalGap: Dp,
    val aboveHorizontalPadding: Dp,
    val belowHorizontalPadding: Dp
) {
    companion object {
        fun forScreenWidthDp(screenWidthDp: Int): WearControlsMetrics = when {
            screenWidthDp < 210 -> WearControlsMetrics(
                shutterSize = 72.dp,
                sideButtonSize = 44.dp,
                rowGap = 6.dp,
                aboveHeight = 38.dp,
                belowHeight = 40.dp,
                verticalGap = 3.dp,
                aboveHorizontalPadding = 32.dp,
                belowHorizontalPadding = 28.dp
            )
            screenWidthDp < 227 -> WearControlsMetrics(
                shutterSize = 78.dp,
                sideButtonSize = 48.dp,
                rowGap = 8.dp,
                aboveHeight = 42.dp,
                belowHeight = 44.dp,
                verticalGap = 4.dp,
                aboveHorizontalPadding = 30.dp,
                belowHorizontalPadding = 26.dp
            )
            else -> WearControlsMetrics(
                shutterSize = 84.dp,
                sideButtonSize = 48.dp,
                rowGap = 10.dp,
                aboveHeight = 44.dp,
                belowHeight = 46.dp,
                verticalGap = 5.dp,
                aboveHorizontalPadding = 28.dp,
                belowHorizontalPadding = 24.dp
            )
        }
    }
}

internal data class WearOfflineMetrics(
    val topPadding: Dp,
    val avatarSize: Dp,
    val avatarToPillGap: Dp,
    val pillToNameGap: Dp,
    val itemSpacing: Dp,
    val settingsButtonSize: Dp,
    val edgeButtonSize: EdgeButtonSize,
    val edgeButtonSpacing: Dp
) {
    companion object {
        fun forScreenHeightDp(screenHeightDp: Int): WearOfflineMetrics = when {
            screenHeightDp < 210 -> WearOfflineMetrics(
                topPadding = 18.dp,
                avatarSize = 34.dp,
                avatarToPillGap = 2.dp,
                pillToNameGap = 2.dp,
                itemSpacing = 2.dp,
                settingsButtonSize = IconButtonDefaults.ExtraSmallButtonSize,
                edgeButtonSize = EdgeButtonSize.ExtraSmall,
                edgeButtonSpacing = 6.dp
            )
            screenHeightDp < 235 -> WearOfflineMetrics(
                topPadding = 20.dp,
                avatarSize = 36.dp,
                avatarToPillGap = 2.dp,
                pillToNameGap = 2.dp,
                itemSpacing = 2.dp,
                settingsButtonSize = IconButtonDefaults.ExtraSmallButtonSize,
                edgeButtonSize = EdgeButtonSize.ExtraSmall,
                edgeButtonSpacing = 6.dp
            )
            else -> WearOfflineMetrics(
                topPadding = 22.dp,
                avatarSize = 40.dp,
                avatarToPillGap = 2.dp,
                pillToNameGap = 2.dp,
                itemSpacing = 3.dp,
                settingsButtonSize = IconButtonDefaults.ExtraSmallButtonSize,
                edgeButtonSize = EdgeButtonSize.ExtraSmall,
                edgeButtonSpacing = 8.dp
            )
        }
    }
}

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
    modifier: Modifier = Modifier,
    missingBlePermissions: Boolean = false,
    missingLocationPermission: Boolean = false,
    onRequestPermissions: () -> Unit = {},
    isScreenLocked: Boolean = false,
    onScreenLockedChange: (Boolean) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heart by heartRate.state.collectAsStateWithLifecycle()
    val sensors by viewModel.sportsSensors.state.collectAsStateWithLifecycle()
    val inMph by speedUnit.inMph.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val gps = uiState.gpsState
    // Read again with each GPS update, which comes at least once a second while tracking
    val gpsReady = RemoteText.gpsReady(gps.hasFix, SystemClock.elapsedRealtime() - gps.fixElapsedRealtimeMs)
    val context = LocalContext.current
    val liveHeartRateBpm = heart.bpm
        ?: sensors.saved.firstOrNull { it.connected && it.heartRateBpm != null }?.heartRateBpm
    val livePowerWatts = sensors.saved.firstOrNull { it.connected && it.powerWatts != null }?.powerWatts
    val liveCadenceRpm = sensors.saved.firstOrNull { it.connected && it.cadenceRpm != null }?.cadenceRpm

    val cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake
    val mode = when {
        uiState.isRecording -> RemoteMode.RECORDING
        cameraOn -> RemoteMode.READY
        else -> RemoteMode.OFFLINE
    }
    val activeWarning = cameraWarnings(ble.details).firstOrNull()
    var previousWarning by remember { mutableStateOf(activeWarning) }
    LaunchedEffect(activeWarning, mode) {
        if (mode == RemoteMode.RECORDING && activeWarning != null && activeWarning != previousWarning) {
            WearHaptics.trigger(context, WearHapticPattern.FAILURE)
        }
        previousWarning = activeWarning
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
        WearHaptics.trigger(context, WearHapticPattern.SHUTTER_TAP)
        viewModel.onShutterClicked()
    }
    val onLinkButton = {
        if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
            WearHaptics.trigger(context, WearHapticPattern.LINK_ACTION)
        }
        viewModel.onCameraButton(button)
    }

    if (mode == RemoteMode.OFFLINE) {
        OfflineRemote(
            ble = ble,
            cameraName = cameraName,
            hint = if (missingBlePermissions) {
                "Allow Bluetooth & Location to connect"
            } else {
                RemoteText.hint(
                    ble,
                    hasWakeUpId = uiState.wakeUpId != null,
                    isLookingForCamera = button == CameraButton.STOP,
                    throughPhone = uiState.linkedThroughPeer
                )
            },
            gpsReady = gpsReady,
            error = ble.lastError ?: uiState.trackState.lastError,
            button = button,
            isMovingLink = uiState.linkMove != null,
            missingBlePermissions = missingBlePermissions,
            missingLocationPermission = missingLocationPermission,
            onRequestPermissions = onRequestPermissions,
            onLinkButton = onLinkButton,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        return
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val metrics = WearControlsMetrics.forScreenWidthDp(screenWidthDp)
    val maxTelemetryItems = if (screenWidthDp < 227) 2 else 3
    val lowBatteryPercent = ble.batteryPercent?.takeIf {
        it <= CameraStatusText.LOW_BATTERY_PERCENT && !ble.isCharging
    }
    val reducedMotion = rememberWearReducedMotion()
    val bezelPulse = rememberWearRecordingPulseAlpha(active = mode == RemoteMode.RECORDING)
    val switchRecedeSpec = if (reducedMotion) {
        snap<Float>()
    } else {
        tween<Float>(durationMillis = 180, easing = WearEaseOutCubic)
    }
    val switchAlpha by animateFloatAsState(
        targetValue = if (mode == RemoteMode.RECORDING) 0.28f else 1f,
        animationSpec = switchRecedeSpec,
        label = "wearSwitchRecedeAlpha"
    )
    val switchScale by animateFloatAsState(
        targetValue = if (mode == RemoteMode.RECORDING) 0.90f else 1f,
        animationSpec = switchRecedeSpec,
        label = "wearSwitchRecedeScale"
    )
    val pagerState = rememberPagerState(pageCount = { 2 })
    // Back on the controls whenever a recording starts or stops
    LaunchedEffect(mode) { pagerState.animateScrollToPage(0) }
    HorizontalPagerScaffold(pagerState = pagerState, modifier = modifier) {
        HorizontalPager(state = pagerState, userScrollEnabled = !isScreenLocked) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                if (page == 0) {
                    ScreenScaffold {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(metrics.verticalGap, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    modifier = Modifier
                                        .height(metrics.aboveHeight)
                                        .padding(horizontal = metrics.aboveHorizontalPadding),
                                    verticalArrangement = Arrangement.Bottom,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (mode == RemoteMode.RECORDING) {
                                        RecordingTimer(
                                            elapsed = RemoteText.elapsed(uiState.recordingDurationSeconds),
                                            lowBatteryPercent = lowBatteryPercent
                                        )
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
                                    horizontalArrangement = Arrangement.spacedBy(metrics.rowGap)
                                ) {
                                    if (mode == RemoteMode.RECORDING) {
                                        LockScreenButton(
                                            buttonSize = metrics.sideButtonSize,
                                            onClick = {
                                                WearHaptics.trigger(context, WearHapticPattern.LINK_ACTION)
                                                onScreenLockedChange(true)
                                            }
                                        )
                                    } else {
                                        ModeButton(
                                            drawableRes = shownMode?.let { Insta360Icons.modeDrawableRes(it) }
                                                ?: Insta360Icons.captureModeDrawableRes(uiState.currentMode),
                                            modeName = shownMode?.label ?: uiState.currentMode.displayName,
                                            enabled = !busy,
                                            buttonSize = metrics.sideButtonSize,
                                            onClick = onOpenModes
                                        )
                                    }
                                    WearTactileShutterButton(
                                        currentMode = uiState.currentMode,
                                        isRecording = mode == RemoteMode.RECORDING,
                                        isBusy = busy,
                                        enabled = true,
                                        contentDescription = shutterLabel,
                                        onClick = onShutter,
                                        size = metrics.shutterSize
                                    )
                                    PhotoVideoSwitch(
                                        current = uiState.currentMode,
                                        enabled = !busy && mode != RemoteMode.RECORDING,
                                        buttonSize = metrics.sideButtonSize,
                                        onSwitch = { viewModel.setMode(it) },
                                        modifier = Modifier.graphicsLayer {
                                            alpha = switchAlpha
                                            scaleX = switchScale
                                            scaleY = switchScale
                                        }
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .height(metrics.belowHeight)
                                        .padding(horizontal = metrics.belowHorizontalPadding),
                                    verticalArrangement = Arrangement.Top,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    ShutterLabel(shutterLabel)
                                    Spacer(Modifier.height(2.dp))
                                    UnderShutterLine(
                                        warning = activeWarning,
                                        gpsReady = gpsReady,
                                        isRecording = mode == RemoteMode.RECORDING,
                                        left = captureLeft(ble.details),
                                        speed = if (gpsReady) RemoteText.speed(gps.speedKmh, gps.speedMph, inMph) else null,
                                        heartRateBpm = liveHeartRateBpm,
                                        powerWatts = livePowerWatts,
                                        cadenceRpm = liveCadenceRpm,
                                        maxTelemetryItems = maxTelemetryItems
                                    )
                                }
                            }
                            if (mode == RemoteMode.RECORDING) {
                                val recordingRingColor = MaterialTheme.wearStatusColors.recording
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = bezelPulse.value * 0.45f }
                                        .drawBehind {
                                            val strokeWidthPx = 2.dp.toPx()
                                            val inset = 2.dp.toPx() + strokeWidthPx / 2f
                                            val radius = size.minDimension / 2f - inset
                                            if (radius > 0f) {
                                                drawCircle(
                                                    color = recordingRingColor,
                                                    radius = radius,
                                                    center = center,
                                                    style = Stroke(width = strokeWidthPx)
                                                )
                                            }
                                        }
                                )
                            }
                            if (isScreenLocked && mode == RemoteMode.RECORDING) {
                                TouchLockOverlay(
                                    elapsed = RemoteText.elapsed(uiState.recordingDurationSeconds),
                                    lowBatteryPercent = lowBatteryPercent,
                                    warning = activeWarning,
                                    gpsReady = gpsReady,
                                    speed = if (gpsReady) RemoteText.speed(gps.speedKmh, gps.speedMph, inMph) else null,
                                    heartRateBpm = liveHeartRateBpm,
                                    powerWatts = livePowerWatts,
                                    cadenceRpm = liveCadenceRpm,
                                    maxTelemetryItems = maxTelemetryItems,
                                    reducedMotion = reducedMotion,
                                    onUnlock = {
                                        WearHaptics.trigger(context, WearHapticPattern.UNLOCK)
                                        onScreenLockedChange(false)
                                    }
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
    missingBlePermissions: Boolean,
    missingLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onLinkButton: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberTransformingLazyColumnState()
    val offlineMetrics = WearOfflineMetrics.forScreenHeightDp(LocalConfiguration.current.screenHeightDp)
    val layoutDirection = LocalLayoutDirection.current
    val defaultPadding = ScreenScaffoldDefaults.contentPadding
    val scaffoldPadding = PaddingValues(
        start = defaultPadding.calculateStartPadding(layoutDirection),
        top = offlineMetrics.topPadding,
        end = defaultPadding.calculateEndPadding(layoutDirection),
        bottom = defaultPadding.calculateBottomPadding()
    )
    val settingsInteraction = remember { MutableInteractionSource() }
    val settingsScale by rememberWearPressScale(settingsInteraction, targetScale = 0.94f)

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier,
        contentPadding = scaffoldPadding,
        edgeButtonSpacing = offlineMetrics.edgeButtonSpacing,
        edgeButton = {
            if (missingBlePermissions) {
                EdgeButton(
                    onClick = onRequestPermissions,
                    buttonSize = offlineMetrics.edgeButtonSize,
                    enabled = true,
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Allow Access")
                }
            } else {
                EdgeButton(
                    onClick = onLinkButton,
                    buttonSize = offlineMetrics.edgeButtonSize,
                    enabled = !isMovingLink,
                    colors = if (button == CameraButton.STOP) {
                        wearEditorialTonalButtonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    LinkActionIcon(button = button, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isMovingLink) "Moving..." else button.label)
                }
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(offlineMetrics.itemSpacing, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                OfflineHeader(
                    ble = ble,
                    cameraName = cameraName,
                    status = if (isMovingLink) RemoteText.MOVING_LINK else RemoteText.status(ble, false, 0),
                    isMovingLink = isMovingLink,
                    avatarSize = offlineMetrics.avatarSize,
                    avatarToPillGap = offlineMetrics.avatarToPillGap,
                    pillToNameGap = offlineMetrics.pillToNameGap
                )
            }
            hint?.let {
                item {
                    Note(text = it)
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val gpsRecoverable = !missingBlePermissions && missingLocationPermission
                    WearMetricPill(
                        text = if (gpsRecoverable) "Allow GPS" else RemoteText.gpsStatus(gpsReady),
                        icon = if (gpsReady) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
                        iconTint = if (gpsReady) MaterialTheme.wearStatusColors.ready else MaterialTheme.wearStatusColors.waiting,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                        modifier = if (gpsRecoverable) {
                            Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onRequestPermissions)
                        } else {
                            Modifier
                        }
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
                            .size(offlineMetrics.settingsButtonSize)
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
                        icon = Icons.Filled.Smartphone,
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec)
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
                        maxLines = 2,
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec)
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
                    iconTint = if (gpsReady) MaterialTheme.wearStatusColors.ready else MaterialTheme.wearStatusColors.waiting,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                )
            }
            error?.let {
                item {
                    WearMetricPill(
                        text = it,
                        icon = Icons.Default.Warning,
                        isProblem = true,
                        maxLines = 2,
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec)
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
                    icon = { LinkActionIcon(button = button, modifier = Modifier.size(18.dp)) },
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

/** Beside the shutter while recording: locks screen touches until held to unlock. */
@Composable
private fun LockScreenButton(
    buttonSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale by rememberWearPressScale(interactionSource, targetScale = 0.94f)

    FilledTonalIconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .touchTargetAwareSize(buttonSize)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        Icon(
            imageVector = Icons.Filled.LockOpen,
            contentDescription = "Lock screen",
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Full-screen touch/water lock overlay while recording: absorbs stray taps and swipes and
 * requires holding the centered lock ring for 1.1 s to unlock.
 */
@Composable
private fun TouchLockOverlay(
    elapsed: String,
    lowBatteryPercent: Int?,
    warning: String?,
    gpsReady: Boolean,
    speed: String?,
    heartRateBpm: Int?,
    powerWatts: Int?,
    cadenceRpm: Int?,
    maxTelemetryItems: Int,
    reducedMotion: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val unlockProgress = remember { Animatable(0f) }
    val currentOnUnlock by rememberUpdatedState(onUnlock)
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val progressColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RecordingTimer(elapsed = elapsed, lowBatteryPercent = lowBatteryPercent)
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .drawBehind {
                        val strokeWidthPx = 3.dp.toPx()
                        val inset = strokeWidthPx / 2f + 1.dp.toPx()
                        val arcDiameter = size.minDimension - inset * 2f
                        if (arcDiameter > 0f) {
                            val topLeft = Offset(inset, inset)
                            val arcSize = Size(arcDiameter, arcDiameter)
                            drawArc(
                                color = trackColor,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidthPx)
                            )
                            val sweep = unlockProgress.value.coerceIn(0f, 1f) * 360f
                            if (sweep > 0f) {
                                drawArc(
                                    color = progressColor,
                                    startAngle = -90f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
                    .semantics {
                        contentDescription = "Hold to unlock"
                        role = Role.Button
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                resetJob?.cancel()
                                val holdJob = scope.launch {
                                    unlockProgress.snapTo(0f)
                                    unlockProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(durationMillis = 1100, easing = LinearEasing)
                                    )
                                    if (unlockProgress.value >= 0.99f) {
                                        currentOnUnlock()
                                    }
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    holdJob.cancel()
                                    if (unlockProgress.value < 0.99f) {
                                        resetJob = scope.launch {
                                            unlockProgress.animateTo(
                                                targetValue = 0f,
                                                animationSpec = if (reducedMotion) {
                                                    snap()
                                                } else {
                                                    tween(durationMillis = 160, easing = WearEaseOutCubic)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = "Hold to unlock",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            UnderShutterLine(
                warning = warning,
                gpsReady = gpsReady,
                isRecording = true,
                left = null,
                speed = speed,
                heartRateBpm = heartRateBpm,
                powerWatts = powerWatts,
                cadenceRpm = cadenceRpm,
                maxTelemetryItems = maxTelemetryItems
            )
        }
    }
}

/** Beside the shutter: the camera's mode glyph inside a hairline capsule, opening the mode list. */
@Composable
private fun ModeButton(
    @DrawableRes drawableRes: Int,
    modeName: String,
    enabled: Boolean,
    buttonSize: Dp,
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
            .touchTargetAwareSize(buttonSize)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = "Mode: $modeName",
            modifier = Modifier.size(20.dp)
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
    buttonSize: Dp,
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
            .touchTargetAwareSize(buttonSize)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        Icon(
            painter = painterResource(Insta360Icons.captureModeDrawableRes(other)),
            contentDescription = "Switch to ${other.displayName}",
            modifier = Modifier.size(20.dp)
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
    heartRateBpm: Int?,
    powerWatts: Int? = null,
    cadenceRpm: Int? = null,
    maxTelemetryItems: Int = 3
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
        isRecording -> LiveReadingsPill(
            speed = speed,
            heartRateBpm = heartRateBpm,
            powerWatts = powerWatts,
            cadenceRpm = cadenceRpm,
            maxItems = maxTelemetryItems
        )
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

private data class LiveTelemetryItem(
    val icon: ImageVector,
    val contentDescription: String,
    val iconTint: Color,
    val text: String
)

@Composable
private fun LiveReadingsPill(
    speed: String?,
    heartRateBpm: Int?,
    modifier: Modifier = Modifier,
    powerWatts: Int? = null,
    cadenceRpm: Int? = null,
    maxItems: Int = 3
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val recordingTint = MaterialTheme.wearStatusColors.recording
    val primaryTint = MaterialTheme.colorScheme.primary
    val items = buildList {
        if (speed != null) {
            add(LiveTelemetryItem(Icons.Filled.Speed, "Speed", onSurfaceVariant, speed))
        }
        if (heartRateBpm != null) {
            add(LiveTelemetryItem(Icons.Filled.Favorite, "Heart rate", recordingTint, "$heartRateBpm bpm"))
        }
        if (powerWatts != null) {
            add(LiveTelemetryItem(Icons.Filled.Bolt, "Power", primaryTint, RemoteText.power(powerWatts)))
        }
        if (cadenceRpm != null) {
            add(LiveTelemetryItem(Icons.Filled.PedalBike, "Cadence", onSurfaceVariant, RemoteText.cadence(cadenceRpm)))
        }
    }.take(maxItems.coerceAtLeast(0))

    if (items.isEmpty()) {
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
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Spacer(Modifier.width(2.dp))
            }
            Icon(
                imageVector = item.icon,
                contentDescription = item.contentDescription,
                tint = item.iconTint,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = item.text,
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
    modifier: Modifier = Modifier,
    isMovingLink: Boolean = false,
    avatarSize: Dp = 40.dp,
    avatarToPillGap: Dp = 2.dp,
    pillToNameGap: Dp = 2.dp
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
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WearCameraModelAvatar(
            model = displayModel,
            size = avatarSize,
            spinning = looking,
            reserveRingSpace = true,
            ringColor = ringColor
        )
        Spacer(Modifier.height(avatarToPillGap))
        WearMetricPill(
            text = status,
            dotState = dotState,
            textColor = statusColor,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
            textStyle = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(pillToNameGap))
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
private fun RecordingTimer(
    elapsed: String,
    lowBatteryPercent: Int? = null
) {
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
        if (lowBatteryPercent != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(Insta360Icons.icBattery),
                contentDescription = "Low camera battery",
                tint = MaterialTheme.wearStatusColors.recording,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "${lowBatteryPercent}%",
                style = MaterialTheme.typography.labelSmall.merge(WearTabularFigures),
                color = MaterialTheme.wearStatusColors.recording,
                maxLines = 1,
                softWrap = false
            )
        }
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

@Composable
private fun LinkActionIcon(
    button: CameraButton,
    modifier: Modifier = Modifier
) {
    if (button == CameraButton.CONNECT) {
        Icon(
            painter = painterResource(Insta360Icons.icBluetooth),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        val icon = when (button) {
            CameraButton.STOP -> Icons.Filled.Close
            CameraButton.WAKE_UP -> Icons.Filled.PowerSettingsNew
            CameraButton.DISCONNECT -> Icons.Filled.BluetoothDisabled
            CameraButton.CONNECT -> Icons.Filled.PowerSettingsNew
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier
        )
    }
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
