package com.open360.ui.liveview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.core.protocol.LiveStreamProtocol.PreviewResolution
import com.open360.core.ui.Insta360Icons
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.TactileShutterButton
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.PillSegmentOption
import com.open360.ui.theme.PillSegmentedControl
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.rememberPressScale
import com.open360.ui.theme.rememberRecordingPulseAlpha
import com.open360.ui.theme.rememberReducedMotion
import com.open360.ui.theme.statusColors
import java.util.Locale

internal fun normalizeQuarterRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

internal fun computeLiveVideoUniformScale(
    viewWidth: Int,
    viewHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    rotationDegrees: Int
): Float {
    val deg = normalizeQuarterRotation(rotationDegrees)
    val isQuarterTurn = deg == 90 || deg == 270
    val effW = if (isQuarterTurn) videoHeight.toFloat() else videoWidth.toFloat()
    val effH = if (isQuarterTurn) videoWidth.toFloat() else videoHeight.toFloat()
    return minOf(viewWidth / effW, viewHeight / effH)
}

internal fun applyLiveVideoTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    rotationDegrees: Int
) {
    if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return
    val deg = normalizeQuarterRotation(rotationDegrees)
    val scale = computeLiveVideoUniformScale(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        rotationDegrees = deg
    )
    val cx = viewWidth / 2f
    val cy = viewHeight / 2f
    val matrix = Matrix().apply {
        postTranslate(-cx, -cy)
        postScale(videoWidth / viewWidth.toFloat(), videoHeight / viewHeight.toFloat())
        if (deg != 0) postRotate(deg.toFloat())
        postScale(scale, scale)
        postTranslate(cx, cy)
    }
    textureView.setTransform(matrix)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun LiveVideoSurface(
    videoWidth: Int,
    videoHeight: Int,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    modifier: Modifier = Modifier,
    rotationDegrees: Int = 0
) {
    val latestOnAvailable by rememberUpdatedState(onSurfaceAvailable)
    val latestOnDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val latestVideoWidth by rememberUpdatedState(videoWidth)
    val latestVideoHeight by rememberUpdatedState(videoHeight)
    val latestRotationDegrees by rememberUpdatedState(rotationDegrees)
    val surfaceHolder = remember { arrayOfNulls<Surface>(1) }

    DisposableEffect(Unit) {
        onDispose {
            surfaceHolder[0]?.let { existing ->
                surfaceHolder[0] = null
                latestOnDestroyed(existing)
                existing.release()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                keepScreenOn = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyLiveVideoTransform(
                            textureView = this@apply,
                            viewWidth = width,
                            viewHeight = height,
                            videoWidth = latestVideoWidth,
                            videoHeight = latestVideoHeight,
                            rotationDegrees = latestRotationDegrees
                        )
                        surfaceHolder[0]?.let { prev ->
                            latestOnDestroyed(prev)
                            prev.release()
                        }
                        val surface = Surface(surfaceTexture)
                        surfaceHolder[0] = surface
                        latestOnAvailable(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        applyLiveVideoTransform(
                            textureView = this@apply,
                            viewWidth = width,
                            viewHeight = height,
                            videoWidth = latestVideoWidth,
                            videoHeight = latestVideoHeight,
                            rotationDegrees = latestRotationDegrees
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        surfaceHolder[0]?.let { existing ->
                            surfaceHolder[0] = null
                            latestOnDestroyed(existing)
                            existing.release()
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                }
            }
        },
        update = { textureView ->
            if (textureView.width > 0 && textureView.height > 0) {
                applyLiveVideoTransform(
                    textureView = textureView,
                    viewWidth = textureView.width,
                    viewHeight = textureView.height,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    rotationDegrees = rotationDegrees
                )
            }
        }
    )
}

@Composable
internal fun PreviewResolutionSelector(
    selected: PreviewResolution,
    onSelect: (PreviewResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = PreviewResolution.entries
    PillSegmentedControl(
        options = entries.map { PillSegmentOption(it.label) },
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        onSelect = { index -> onSelect(entries[index]) },
        role = Role.RadioButton,
        minHeight = 40.dp,
        textStyle = MaterialTheme.typography.labelMedium,
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveViewFullscreenDialog(
    title: String,
    state: LiveViewUiState,
    batteryPercent: Int?,
    isCharging: Boolean,
    currentMode: CaptureMode,
    isRecording: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    isTakingPhoto: Boolean,
    recordingDurationSeconds: Long,
    isConnected: Boolean,
    isAsleep: Boolean,
    isMovingLink: Boolean,
    onStop: () -> Unit,
    onSelectResolution: (PreviewResolution) -> Unit,
    onRetry: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onShutterClick: () -> Unit,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onModeSelected: (CaptureMode) -> Unit = {},
    cameraMode: CameraMode? = CameraMode.fromCaptureMode(currentMode),
    onCameraModeSelected: (CameraMode) -> Unit = {},
    canSelectMode: Boolean = !isRecording && !isStarting && !isStopping && !isTakingPhoto,
    canSelectResolution: Boolean = state.supportsResolutionSelection && !state.resolutionLockedByCamera
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val shutterEnabled = isConnected && !isAsleep && !isMovingLink
    val modeLocked = isRecording || isStarting || isStopping || isTakingPhoto
    val modeEnabled = canSelectMode && shutterEnabled && !modeLocked
    val shownMode = cameraMode?.takeIf { it.isPhoto == (currentMode == CaptureMode.PHOTO) }
    val showSubModeStrip = cameraMode != null && !isRecording

    val isQuarterTurn = state.rotationDegrees == 90 || state.rotationDegrees == 270
    val effWidth = if (isQuarterTurn) state.videoHeight else state.videoWidth
    val effHeight = if (isQuarterTurn) state.videoWidth else state.videoHeight
    val aspect = (effWidth.toFloat() / effHeight.coerceAtLeast(1).toFloat())
        .coerceIn(0.4f, 2.5f)
    val isPortraitStream = effHeight > effWidth

    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }
    LaunchedEffect(activity, isPortraitStream) {
        activity?.requestedOrientation = if (isPortraitStream) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    var hudUserVisible by remember { mutableStateOf(true) }
    val showHud = hudUserVisible || !state.hasFirstFrame || state.status == LiveViewStatus.ERROR || isRecording
    val reducedMotion = rememberReducedMotion()
    val enterMs = if (reducedMotion) 150 else 180
    val exitMs = 120

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { hudUserVisible = !hudUserVisible },
                contentAlignment = Alignment.Center
            ) {
                if (state.status != LiveViewStatus.ERROR) {
                    LiveVideoSurface(
                        videoWidth = state.videoWidth,
                        videoHeight = state.videoHeight,
                        rotationDegrees = state.rotationDegrees,
                        onSurfaceAvailable = onSurfaceAvailable,
                        onSurfaceDestroyed = onSurfaceDestroyed,
                        modifier = Modifier
                            .aspectRatio(aspect, matchHeightConstraintsFirst = isPortraitStream)
                    )
                }

                if (!state.hasFirstFrame && state.status != LiveViewStatus.ERROR) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color.White
                        )
                        state.statusMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                if (state.status == LiveViewStatus.ERROR) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    ) {
                        Text(
                            text = state.errorMessage ?: "Live preview interrupted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                                Text("Retry", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(onClick = onOpenWifiSettings, shapes = ButtonDefaults.shapes()) {
                                Text("Wi-Fi Settings", color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }

                // Top translucent HUD bar (uncluttered: back, title + telemetry, battery, stop)
                AnimatedVisibility(
                    visible = showHud,
                    enter = if (reducedMotion) {
                        fadeIn(tween(enterMs, easing = EaseOutCubic))
                    } else {
                        fadeIn(tween(enterMs, easing = EaseOutCubic)) +
                            slideInVertically(tween(enterMs, easing = EaseOutCubic)) { -it / 4 }
                    },
                    exit = if (reducedMotion) {
                        fadeOut(tween(exitMs, easing = EaseOutCubic))
                    } else {
                        fadeOut(tween(exitMs, easing = EaseOutCubic)) +
                            slideOutVertically(tween(exitMs, easing = EaseOutCubic)) { -it / 4 }
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .statusBarsPadding()
                            .displayCutoutPadding()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Exit Fullscreen",
                                tint = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatStreamMetrics(state),
                                style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                                color = Color.LightGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (batteryPercent != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                    contentDescription = "Battery",
                                    tint = if (batteryPercent > 20) MaterialTheme.statusColors.ready else MaterialTheme.statusColors.recording,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "$batteryPercent%",
                                    style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                                    color = Color.White,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        IconButton(onClick = onStop) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Live View",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom translucent HUD bar: resolution pills + recording/mode label + shutter button
                AnimatedVisibility(
                    visible = showHud,
                    enter = if (reducedMotion) {
                        fadeIn(tween(enterMs, easing = EaseOutCubic))
                    } else {
                        fadeIn(tween(enterMs, easing = EaseOutCubic)) +
                            slideInVertically(tween(enterMs, easing = EaseOutCubic)) { it / 4 }
                    },
                    exit = if (reducedMotion) {
                        fadeOut(tween(exitMs, easing = EaseOutCubic))
                    } else {
                        fadeOut(tween(exitMs, easing = EaseOutCubic)) +
                            slideOutVertically(tween(exitMs, easing = EaseOutCubic)) { it / 4 }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .navigationBarsPadding()
                                .displayCutoutPadding()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (canSelectResolution && !isRecording && showSubModeStrip) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        FullscreenResolutionPills(
                                            selectedResolution = state.resolution,
                                            enabled = !isRecording && !isStarting && !isStopping,
                                            onSelectResolution = onSelectResolution
                                        )
                                        PillSegmentedControl(
                                            options = listOf(
                                                PillSegmentOption(
                                                    label = "Video",
                                                    drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.VIDEO)
                                                ),
                                                PillSegmentOption(
                                                    label = "Photo",
                                                    drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.PHOTO)
                                                )
                                            ),
                                            selectedIndex = if (currentMode == CaptureMode.PHOTO) 1 else 0,
                                            onSelect = { index ->
                                                onModeSelected(if (index == 1) CaptureMode.PHOTO else CaptureMode.VIDEO)
                                            },
                                            enabled = modeEnabled,
                                            role = Role.RadioButton,
                                            minHeight = 36.dp,
                                            textStyle = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.widthIn(min = 176.dp, max = 200.dp)
                                        )
                                    }
                                    FullscreenSubModeStrip(
                                        currentMode = currentMode,
                                        shownMode = shownMode,
                                        enabled = modeEnabled,
                                        onCameraModeSelected = onCameraModeSelected,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                if (canSelectResolution) {
                                    FullscreenResolutionPills(
                                        selectedResolution = state.resolution,
                                        enabled = !isRecording && !isStarting && !isStopping,
                                        onSelectResolution = onSelectResolution
                                    )
                                }

                                if (isRecording) {
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                        FullscreenRecordingBadge(durationSeconds = recordingDurationSeconds)
                                    }
                                } else {
                                    PillSegmentedControl(
                                        options = listOf(
                                            PillSegmentOption(
                                                label = "Video",
                                                drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.VIDEO)
                                            ),
                                            PillSegmentOption(
                                                label = "Photo",
                                                drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.PHOTO)
                                            )
                                        ),
                                        selectedIndex = if (currentMode == CaptureMode.PHOTO) 1 else 0,
                                        onSelect = { index ->
                                            onModeSelected(if (index == 1) CaptureMode.PHOTO else CaptureMode.VIDEO)
                                        },
                                        enabled = modeEnabled,
                                        role = Role.RadioButton,
                                        minHeight = 36.dp,
                                        textStyle = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.widthIn(min = 176.dp, max = 196.dp)
                                    )
                                    if (showSubModeStrip) {
                                        FullscreenSubModeStrip(
                                            currentMode = currentMode,
                                            shownMode = shownMode,
                                            enabled = modeEnabled,
                                            onCameraModeSelected = onCameraModeSelected,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            TactileShutterButton(
                                currentMode = currentMode,
                                isRecording = isRecording,
                                isStarting = isStarting || isTakingPhoto,
                                isStopping = isStopping,
                                enabled = shutterEnabled,
                                size = 56.dp,
                                onClick = onShutterClick
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .navigationBarsPadding()
                                .displayCutoutPadding()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (canSelectResolution) {
                                FullscreenResolutionPills(
                                    selectedResolution = state.resolution,
                                    enabled = !isRecording && !isStarting && !isStopping,
                                    onSelectResolution = onSelectResolution
                                )
                            }

                            if (isRecording) {
                                FullscreenRecordingBadge(durationSeconds = recordingDurationSeconds)
                            } else {
                                PillSegmentedControl(
                                    options = listOf(
                                        PillSegmentOption(
                                            label = "Video",
                                            drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.VIDEO)
                                        ),
                                        PillSegmentOption(
                                            label = "Photo",
                                            drawableRes = Insta360Icons.captureModeDrawableRes(CaptureMode.PHOTO)
                                        )
                                    ),
                                    selectedIndex = if (currentMode == CaptureMode.PHOTO) 1 else 0,
                                    onSelect = { index ->
                                        onModeSelected(if (index == 1) CaptureMode.PHOTO else CaptureMode.VIDEO)
                                    },
                                    enabled = modeEnabled,
                                    role = Role.RadioButton,
                                    minHeight = 36.dp,
                                    textStyle = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .fillMaxWidth()
                                )
                                if (showSubModeStrip) {
                                    FullscreenSubModeStrip(
                                        currentMode = currentMode,
                                        shownMode = shownMode,
                                        enabled = modeEnabled,
                                        onCameraModeSelected = onCameraModeSelected,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            TactileShutterButton(
                                currentMode = currentMode,
                                isRecording = isRecording,
                                isStarting = isStarting || isTakingPhoto,
                                isStopping = isStopping,
                                enabled = shutterEnabled,
                                onClick = onShutterClick
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenSubModeStrip(
    currentMode: CaptureMode,
    shownMode: CameraMode?,
    enabled: Boolean,
    onCameraModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .selectableGroup()
            .horizontalScroll(scrollState, enabled = enabled),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CameraMode.modesFor(currentMode).forEach { mode ->
            val isSelected = mode == shownMode
            val inView = remember { BringIntoViewRequester() }
            LaunchedEffect(isSelected) { if (isSelected) inView.bringIntoView() }
            val chipInteraction = remember { MutableInteractionSource() }
            val chipScale by rememberPressScale(chipInteraction, targetScale = 0.97f)

            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .bringIntoViewRequester(inView)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        interactionSource = chipInteraction,
                        indication = null,
                        onClick = { if (!isSelected) onCameraModeSelected(mode) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.14f)
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Color.White
                    },
                    border = if (isSelected) {
                        null
                    } else {
                        BorderStroke(width = 1.dp, color = Color.White.copy(alpha = 0.24f))
                    },
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                            alpha = if (!enabled && !isSelected) 0.45f else 1f
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            painter = painterResource(Insta360Icons.modeDrawableRes(mode)),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenResolutionPills(
    selectedResolution: PreviewResolution,
    onSelectResolution: (PreviewResolution) -> Unit,
    enabled: Boolean = true
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreviewResolution.entries.forEach { res ->
            val selected = res == selectedResolution
            Surface(
                onClick = { if (!selected) onSelectResolution(res) },
                enabled = enabled,
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            ) {
                Text(
                    text = res.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun FullscreenRecordingBadge(durationSeconds: Long) {
    val pulseAlpha = rememberRecordingPulseAlpha(active = true)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulseAlpha.value }
                .clip(CircleShape)
                .background(MaterialTheme.statusColors.recording)
        )
        Text(
            text = "REC ${CameraStatusText.formatRecordingDuration(durationSeconds)}",
            style = MaterialTheme.typography.labelLarge.merge(TabularFigures),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.statusColors.recording,
            maxLines = 1,
            softWrap = false
        )
    }
}

internal fun formatStreamMetrics(state: LiveViewUiState): String {
    val bitrateText = if (state.bitrateKbps >= 1000) {
        String.format(Locale.US, "%.1f Mbps", state.bitrateKbps / 1000f)
    } else {
        "${state.bitrateKbps} kbps"
    }
    return "${state.videoWidth}×${state.videoHeight} • ${state.fps} fps • $bitrateText • ${state.codecLabel}"
}
