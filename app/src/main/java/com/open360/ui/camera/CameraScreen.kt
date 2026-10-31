package com.open360.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.gps.GpxTrackState
import com.open360.core.model.CaptureMode
import com.open360.core.peer.LinkMove
import com.open360.ui.theme.SurfaceCard
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.statusColors
import java.util.Locale

// Emil Kowalski custom curves & motion tokens
val EaseOutCubic = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
val EaseInOutCubic = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * Emil Kowalski press scale feedback: responds instantly on pointer-down with spring physics.
 */
@Composable
internal fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    targetScale: Float = 0.96f
): State<Float> {
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onWakeUpClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val ble = uiState.bleState
    val server = uiState.serverState
    val gps = uiState.gpsState
    val haptic = LocalHapticFeedback.current

    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Upper scrollable column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Shown until Wake Up is set up or the card is dismissed
            if (!uiState.wakeUpSetupCardDismissed &&
                WakeUpSetup.needed(hasId = uiState.wakeUpId != null, registration = ble.wakeUpRegistration)
            ) {
                WakeUpSetupCard(
                    registration = ble.wakeUpRegistration,
                    onSetUp = { viewModel.openWakeUpSetup() },
                    onDismiss = { viewModel.dismissWakeUpSetupCard() }
                )
            }

            // Camera Status Card
            CameraStatusCard(
                uiState = uiState,
                onCameraButton = { button ->
                    if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    viewModel.onCameraButton(button)
                },
                onMoveLink = { viewModel.moveLink() }
            )

            // Live GPS Telemetry Card
            val lastGpsSentTime = if (server.lastGpsSentTimeMs > 0) server.lastGpsSentTimeMs else ble.lastGpsSentTimeMs
            GpsTelemetryCard(
                gps = gps,
                isSpeedMph = uiState.isSpeedMph,
                isRecording = uiState.isRecording,
                isCameraConnected = uiState.isConnected,
                isMinimized = uiState.isGpsCardMinimized,
                joinedLateSeconds = ble.joinedRecordingSeconds,
                lastGpsSentTimeMs = lastGpsSentTime,
                track = uiState.trackState,
                isWatchGps = uiState.linkedThroughPeer,
                onToggleSpeedUnit = { viewModel.toggleSpeedUnit() },
                onToggleMinimize = { viewModel.toggleGpsCardMinimized() }
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        // Pinned bottom container holding CameraControlCard right above the bottom navigation bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        ) {
            CameraControlCard(
                currentMode = uiState.currentMode,
                isRecording = uiState.isRecording,
                isStarting = uiState.bleState.isCaptureStarting,
                isStopping = uiState.bleState.isCaptureStopping,
                isTakingPhoto = uiState.bleState.isTakingPhoto,
                durationSeconds = uiState.recordingDurationSeconds,
                isConnected = uiState.isConnected,
                isAsleep = isAsleep,
                cameraMode = ble.details.subModes?.let { CameraMode.of(it) },
                onModeSelected = { viewModel.setMode(it) },
                onCameraModeSelected = { viewModel.setCameraMode(it) },
                onShutterClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.onShutterClicked()
                }
            )
        }
    }
}

@Composable
private fun CardTitle(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(text = text, style = MaterialTheme.typography.titleMediumEmphasized)
    }
}

/** A short reading in a pill, tinted with its own colour unless [containerColor] is given. */
@Composable
private fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = color.copy(alpha = 0.16f),
    icon: ImageVector? = null,
    iconDescription: String? = null,
    dot: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconDescription,
                modifier = Modifier.size(14.dp),
                tint = color
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

// LoadingIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraStatusCard(
    uiState: RemoteUiState,
    onCameraButton: (CameraButton) -> Unit,
    onMoveLink: () -> Unit = {}
) {
    val ble = uiState.bleState
    val server = uiState.serverState
    val button = CameraButton.of(ble, uiState.isConnected)
    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake
    val isReady = uiState.isConnected && !isAsleep

    SurfaceCard {
        Column(modifier = Modifier.padding(20.dp)) {
            if (isReady) {
                // The camera, its mode and battery, and a restrained Disconnect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardTitle(icon = Icons.Default.Videocam, text = "Camera")

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val cameraDisplayString = server.cameraDisplayString
                        if (cameraDisplayString != null) {
                            StatusPill(
                                text = cameraDisplayString,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        }

                        val batteryPercent = ble.batteryPercent
                        if (batteryPercent != null) {
                            StatusPill(
                                text = "$batteryPercent%",
                                color = if (batteryPercent > 20) MaterialTheme.statusColors.ready else MaterialTheme.colorScheme.error,
                                icon = if (ble.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                iconDescription = "Battery Level"
                            )
                        }

                        OutlinedButton(
                            onClick = { onCameraButton(button) },
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothDisabled,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Disconnect", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                CameraDetailRows(ble = ble)
            } else {
                // Disconnected, looking, linking, switching on, off, or moving the link: said here
                // once, as the card's title, with what to do about it under it
                val statusColor = when {
                    uiState.linkMove != null -> MaterialTheme.colorScheme.primary
                    server.isCameraConnected -> MaterialTheme.statusColors.ready
                    isAsleep -> MaterialTheme.statusColors.waiting
                    server.isAdvertising || ble.connectionStatus == BleConnectionStatus.SCANNING -> MaterialTheme.colorScheme.primary
                    ble.connectionStatus == BleConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                val statusText = when {
                    uiState.linkMove != null -> "Moving the Link..."
                    server.isCameraConnected -> "Connected as GPS Remote"
                    isAsleep && ble.isWaking -> "Switching Camera On..."
                    isAsleep -> "Camera Is Off"
                    server.isAdvertising -> "Broadcasting as GPS Remote"
                    ble.connectionStatus == BleConnectionStatus.SCANNING -> "Looking for Camera..."
                    ble.connectionStatus == BleConnectionStatus.CONNECTING -> "Connecting to Camera..."
                    else -> "Disconnected"
                }

                val statusIcon = when {
                    uiState.linkMove != null -> Icons.Default.Sync
                    isAsleep -> Icons.Default.PowerSettingsNew
                    uiState.isConnected -> Icons.Default.CheckCircle
                    server.isAdvertising || ble.connectionStatus == BleConnectionStatus.SCANNING -> Icons.Default.Sync
                    else -> Icons.Default.BluetoothDisabled
                }

                // Under way, shown with Material's shape-morphing loading indicator
                val inProgress = uiState.linkMove != null ||
                    (isAsleep && ble.isWaking) ||
                    server.isAdvertising ||
                    ble.connectionStatus == BleConnectionStatus.SCANNING ||
                    ble.connectionStatus == BleConnectionStatus.CONNECTING

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (inProgress) {
                            LoadingIndicator(modifier = Modifier.size(48.dp), color = statusColor)
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(statusColor.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = statusText, style = MaterialTheme.typography.titleMediumEmphasized)
                        Spacer(modifier = Modifier.height(2.dp))
                        // Two lines kept for it, so the button under it stays put from Connect to Stop
                        Text(
                            text = when {
                                isAsleep && ble.isWaking -> "Switching the camera on. It takes a few seconds."
                                button == CameraButton.STOP -> "Looking for the camera. Keep it nearby."
                                isAsleep -> "The camera is nearby but switched off. Tap Wake Up or press the camera's button to switch it on."
                                uiState.wakeUpId != null -> "Keep the camera nearby and tap Connect. Open360 switches it on if it is off."
                                else -> "Turn the camera on, keep it nearby and tap Connect."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minLines = 2
                        )
                    }
                }

                // Nothing to press until a link that is moving has arrived
                if (uiState.linkMove == null) {
                    Spacer(modifier = Modifier.height(20.dp))

                    val buttonIcon = when (button) {
                        CameraButton.CONNECT -> Icons.Default.Bluetooth
                        CameraButton.STOP -> Icons.Default.Stop
                        CameraButton.WAKE_UP -> Icons.Default.PowerSettingsNew
                        CameraButton.DISCONNECT -> Icons.Default.BluetoothDisabled
                    }
                    val buttonModifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)

                    if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
                        Button(
                            onClick = { onCameraButton(button) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = buttonModifier
                        ) {
                            Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = button.label, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onCameraButton(button) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = buttonModifier
                        ) {
                            Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = button.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            when {
                uiState.linkMove == LinkMove.TO_OTHER_DEVICE -> PeerLinkRow(text = "Moving the link to your watch")
                uiState.linkMove == LinkMove.TO_THIS_DEVICE -> PeerLinkRow(text = "Moving the link to this phone")
                uiState.linkedThroughPeer -> PeerLinkRow(
                    text = "Controlled through Open360 on your watch",
                    action = "Move Here",
                    actionEnabled = uiState.canMoveLink,
                    onAction = onMoveLink
                )
                // For leaving the phone behind
                uiState.canMoveLink -> PeerLinkRow(
                    text = "Open360 is open on your watch",
                    action = "Move to Watch",
                    onAction = onMoveLink
                )
            }

            AnimatedVisibility(visible = server.lastError != null || ble.lastError != null) {
                Text(
                    text = server.lastError ?: ble.lastError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * About the camera's link and the watch: while the camera is linked through the watch, whose
 * Open360 holds the link and sends the camera its GPS, the camera shown is the watch's. With
 * [action], a button that moves the link: here from the watch, or to it.
 */
@Composable
private fun PeerLinkRow(
    text: String,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {}
) {
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Watch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                Text(text = action, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * What the camera has reported about itself, laid out in clean status tiles and warning badges.
 */
@Composable
private fun CameraDetailRows(ble: BleCameraState) {
    val details = ble.details
    val warnings = ArrayList<StatusLine>()
    val vitals = ArrayList<StatusLine>()

    // Warnings come first
    if (details.temperatureHigh) {
        warnings.add(StatusLine("Temperature", "High", isProblem = true))
    }
    if (details.batteryLow) {
        warnings.add(StatusLine("Camera Battery", "Low", isProblem = true))
    }
    val storageProblem = CameraStatusText.storageProblem(details.cardState)
    if (storageProblem != null) {
        warnings.add(StatusLine("Storage", storageProblem, isProblem = true))
    }

    // Normal vitals
    val captureSecondsLeft = details.captureSecondsLeft
    if (storageProblem == null && captureSecondsLeft != null) {
        if (details.subModes?.isPhoto == true) {
            vitals.add(StatusLine("Photos Left", CameraStatusText.photosLeft(captureSecondsLeft)))
        } else {
            vitals.add(StatusLine("Time Left", CameraStatusText.timeLeft(captureSecondsLeft)))
        }
    }
    details.pod?.let { pod ->
        vitals.add(StatusLine("Action Pod", CameraStatusText.pod(pod), isProblem = CameraStatusText.podIsLow(pod)))
    }
    CameraStatusText.recordingMode(details.recordingState)?.let { mode ->
        vitals.add(StatusLine("Recording Mode", mode))
    }
    if (ble.lastVideoFile != null) {
        val length = ble.lastVideoSeconds?.let { CameraStatusText.clipLength(it) } ?: "Saved"
        vitals.add(StatusLine("Last Clip", length, note = ble.lastVideoFile))
    }

    if (warnings.isEmpty() && vitals.isEmpty()) return

    Spacer(modifier = Modifier.height(16.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // High visibility warning pills
        warnings.forEach { warning ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${warning.label}: ${warning.value}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Clean Vitals grid / cards
        if (vitals.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vitals.take(2).forEach { item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.value,
                                style = MaterialTheme.typography.titleSmall.merge(TabularFigures),
                                fontWeight = FontWeight.SemiBold,
                                color = if (item.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Additional vitals (e.g. 3rd item or Last Clip)
            if (vitals.size > 2) {
                vitals.drop(2).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** One line of camera status: a label, its value, and an optional note shown underneath. */
private data class StatusLine(
    val label: String,
    val value: String,
    val isProblem: Boolean = false,
    val note: String? = null
)

@Composable
fun GpsTelemetryCard(
    gps: com.open360.core.gps.GpsTelemetry,
    isSpeedMph: Boolean,
    isRecording: Boolean,
    isCameraConnected: Boolean = false,
    isMinimized: Boolean = true,
    joinedLateSeconds: Int? = null,
    lastGpsSentTimeMs: Long,
    track: GpxTrackState = GpxTrackState(),
    // The GPS shown is the watch's, as the camera is linked through it and gets its GPS
    isWatchGps: Boolean = false,
    onToggleSpeedUnit: () -> Unit,
    onToggleMinimize: () -> Unit = {}
) {
    val expandEasing = EaseDrawer
    val collapseEasing = EaseOutCubic
    val transition = updateTransition(targetState = isMinimized, label = "GpsCardTransition")

    val chevronRotation by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 200, easing = collapseEasing)
            } else {
                tween(durationMillis = 260, easing = expandEasing)
            }
        },
        label = "chevronRotation"
    ) { minimized ->
        if (minimized) 0f else 180f
    }

    SurfaceCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header, tapped to show or hide the details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onToggleMinimize() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardTitle(
                    icon = Icons.Default.LocationOn,
                    text = if (isWatchGps) "Watch GPS" else "GPS Telemetry"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        text = if (gps.hasFix) {
                            if (gps.satellitesInFix == 1) "1 Satellite" else "${gps.satellitesInFix} Satellites"
                        } else "Searching...",
                        color = if (gps.hasFix) MaterialTheme.statusColors.ready else MaterialTheme.statusColors.waiting,
                        dot = true
                    )

                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isMinimized) "Expand GPS Card" else "Minimize GPS Card",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speed Display & Live Status Row
            val speedDisplay = if (gps.hasFix) {
                val speedValue = if (isSpeedMph) gps.speedMph else gps.speedKmh
                String.format(Locale.US, "%.1f", speedValue)
            } else {
                "—"
            }
            val unitText = if (isSpeedMph) "mph" else "km/h"

            val streamActive = isCameraConnected && gps.hasFix
            val badgeColor = if (streamActive) MaterialTheme.statusColors.ready else MaterialTheme.colorScheme.onSurfaceVariant
            val compactStreamText = if (streamActive) {
                if (isRecording) {
                    if (track.pointCount > 0) "Sending (${track.pointCount})" else "Sending GPS"
                } else {
                    "GPS Ready"
                }
            } else null

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = speedDisplay,
                        style = MaterialTheme.typography.displayLargeEmphasized.merge(TabularFigures).copy(
                            fontSize = 44.sp,
                            lineHeight = 44.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Tapped to switch between km/h and mph
                    Surface(
                        onClick = onToggleSpeedUnit,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            text = unitText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }

                // Streaming status when active or ready; otherwise the altitude, while the details
                // that also show it are hidden
                if (streamActive && compactStreamText != null) {
                    StatusPill(
                        text = compactStreamText,
                        color = MaterialTheme.statusColors.ready,
                        icon = Icons.Default.CheckCircle,
                        iconDescription = "GPS Status"
                    )
                } else {
                    transition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(tween(180, easing = EaseOutCubic)),
                        exit = fadeOut(tween(80, easing = EaseOutCubic))
                    ) {
                        StatusPill(
                            text = when {
                                !gps.hasFix -> "— m"
                                gps.verticalAccuracyMeters != null ->
                                    String.format(Locale.US, "%.0f ±%.0f m", gps.altitudeMeters, gps.verticalAccuracyMeters)
                                else -> String.format(Locale.US, "%.0f m", gps.altitudeMeters)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            icon = Icons.Default.Height,
                            iconDescription = "Altitude"
                        )
                    }
                }
            }

            // Expanded detail section: telemetry metrics, what is sent to the camera, the route file
            transition.AnimatedVisibility(
                visible = { !it },
                enter = fadeIn(tween(180, delayMillis = 60, easing = EaseOutCubic)) +
                        expandVertically(
                            animationSpec = tween(durationMillis = 280, easing = expandEasing),
                            expandFrom = Alignment.Top
                        ),
                exit = fadeOut(tween(80, easing = EaseOutCubic)) +
                       shrinkVertically(
                           animationSpec = tween(durationMillis = 200, easing = collapseEasing),
                           shrinkTowards = Alignment.Top
                       )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        TelemetryMetricItem(
                            icon = Icons.Default.Height,
                            label = "Altitude",
                            value = when {
                                !gps.hasFix -> "—"
                                gps.verticalAccuracyMeters != null ->
                                    String.format(Locale.US, "%.1f ±%.1f m", gps.altitudeMeters, gps.verticalAccuracyMeters)
                                else -> String.format(Locale.US, "%.1f m", gps.altitudeMeters)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricItem(
                            icon = Icons.Default.GpsFixed,
                            label = "Accuracy",
                            value = if (gps.hasFix) String.format(Locale.US, "±%.1f m", gps.accuracyMeters) else "—",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricItem(
                            icon = Icons.Default.Navigation,
                            label = "Heading",
                            value = if (gps.hasFix) String.format(Locale.US, "%.0f°", gps.bearingDegrees) else "—",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Only with a camera to send to; with none the camera card already says so
                    val streamText = when {
                        streamActive && isRecording && joinedLateSeconds != null ->
                            "Sending GPS, Joined $joinedLateSeconds s After Start"
                        streamActive && isRecording -> "Sending GPS to Camera"
                        streamActive -> "GPS Ready, Sent While Recording"
                        isCameraConnected && !gps.hasFix -> "Waiting for GPS"
                        else -> null
                    }

                    if (streamText != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (streamActive) Icons.Default.CheckCircle else Icons.Default.Sync,
                                    contentDescription = "GPS Sending Status",
                                    modifier = Modifier.size(18.dp),
                                    tint = badgeColor
                                )
                                Text(
                                    text = streamText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = badgeColor
                                )
                            }

                            if (lastGpsSentTimeMs > 0) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                        }
                    }

                    if (track.fileName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Route file: ${track.folder}/${track.fileName} (${track.pointCount} points)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val lastError = track.lastError
            if (lastError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun TelemetryMetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Video and Photo as Material 3 Expressive's connected button group: two toggle buttons, joined,
 * the chosen one filled, whose shapes morph as they are pressed and chosen.
 */
@Composable
fun ModeSegmentedControl(
    currentMode: CaptureMode,
    enabled: Boolean,
    onModeSelected: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(CaptureMode.VIDEO, CaptureMode.PHOTO)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        modes.forEachIndexed { index, mode ->
            val isSelected = currentMode == mode
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { if (!isSelected) onModeSelected(mode) },
                enabled = enabled,
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                         else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { role = Role.RadioButton }
            ) {
                Icon(
                    imageVector = if (mode == CaptureMode.VIDEO) Icons.Default.Videocam else Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (mode == CaptureMode.VIDEO) "Video" else "Photo",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Tactile physical shutter button: scales down on pointer-down with spring physics and morphs
 * between round recording trigger, square stop button, and photo capture button. While the camera
 * is asked to start, stop or take a photo, it shows Material's loading indicator and takes no tap.
 */
// LoadingIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TactileShutterButton(
    currentMode: CaptureMode,
    isRecording: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = 500f
        ),
        label = "shutterPressScale"
    )

    val innerCornerRadius by animateDpAsState(
        targetValue = if (isRecording) 8.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "innerCornerRadius"
    )
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 28.dp else 56.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "innerSize"
    )

    val isVideo = currentMode == CaptureMode.VIDEO || isRecording
    val isBusy = isStarting || isStopping
    // Material's disabled content colour, for ring and centre alike, rather than a faded red
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val ringColor = when {
        !enabled -> disabledColor
        isVideo -> MaterialTheme.statusColors.recording
        else -> MaterialTheme.colorScheme.outline
    }
    val innerColor = when {
        !enabled -> disabledColor
        isVideo -> MaterialTheme.statusColors.recording
        else -> Color.White
    }

    Box(
        modifier = modifier
            .size(86.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.08f))
            .border(3.5.dp, ringColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isBusy,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isBusy) {
            LoadingIndicator(
                modifier = Modifier.size(56.dp),
                color = if (isVideo) MaterialTheme.statusColors.recording else MaterialTheme.colorScheme.onSurface
            )
        } else {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(RoundedCornerShape(innerCornerRadius))
                    .background(innerColor)
                    .border(
                        width = if (!isVideo && enabled) 0.5.dp else 0.dp,
                        color = if (!isVideo && enabled) Color.Black.copy(alpha = 0.15f) else Color.Transparent,
                        shape = RoundedCornerShape(innerCornerRadius)
                    )
            )
        }
    }
}

/**
 * A recording's running time, large and red with a pulsing dot, and the mode it is in, shown above
 * the shutter in place of the modes while it records.
 */
@Composable
private fun RecordingTime(durationSeconds: Long, modeName: String, isRecording: Boolean, modifier: Modifier = Modifier) {
    // 01:05, or 1:02:09 once it passes an hour, as on the watch
    val hours = durationSeconds / 3600
    val mins = durationSeconds % 3600 / 60
    val secs = durationSeconds % 60
    val formatted = if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    else String.format(Locale.US, "%02d:%02d", mins, secs)

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by if (isRecording) {
        pulseTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.statusColors.recording.copy(alpha = pulseAlpha))
            )
            Text(
                text = formatted,
                style = MaterialTheme.typography.displaySmallEmphasized.merge(TabularFigures),
                color = MaterialTheme.statusColors.recording
            )
        }
        Text(
            text = modeName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// BringIntoViewRequester, for the row of modes
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraControlCard(
    currentMode: CaptureMode,
    isRecording: Boolean,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
    // From a photo tap until the camera answers, about 3 to 5 s
    isTakingPhoto: Boolean = false,
    durationSeconds: Long,
    isConnected: Boolean,
    isAsleep: Boolean = false,
    cameraMode: CameraMode? = null,
    onModeSelected: (CaptureMode) -> Unit,
    onCameraModeSelected: (CameraMode) -> Unit = {},
    onShutterClick: () -> Unit
) {
    val modeLocked = isRecording || isStarting || isStopping || isTakingPhoto
    val shownMode = cameraMode?.takeIf { it.isPhoto == (currentMode == CaptureMode.PHOTO) }

    SurfaceCard(
        modifier = Modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Above the shutter, one space for two things, as on the watch: the modes while ready,
            // and the running time and mode while recording, when the modes are held. Both are
            // laid out, the one not shown invisible and silent, so the space keeps its height and
            // the shutter, in a card fixed at the bottom, keeps its place
            val recordingAlpha by animateFloatAsState(
                targetValue = if (isRecording) 1f else 0f,
                animationSpec = tween(if (isRecording) 160 else 100, easing = EaseOutCubic),
                label = "recordingAlpha"
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = 1f - recordingAlpha }
                        .then(if (isRecording) Modifier.clearAndSetSemantics {} else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mode Segmented Control (Video / Photo)
                    ModeSegmentedControl(
                        currentMode = currentMode,
                        enabled = !modeLocked,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    // The chosen side's modes (FreeFrame Video, TimeShift, Timelapse...) as toggle
                    // buttons, the camera's own one filled
                    if (cameraMode != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState(), enabled = !isRecording)
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CameraMode.group(photo = currentMode == CaptureMode.PHOTO).forEach { mode ->
                                val isSelected = mode == shownMode
                                // The row is wider than the screen, so the camera's mode is scrolled into
                                // view whenever it changes, from here or on the camera
                                val inView = remember { BringIntoViewRequester() }
                                LaunchedEffect(isSelected) { if (isSelected) inView.bringIntoView() }

                                ToggleButton(
                                    checked = isSelected,
                                    onCheckedChange = { onCameraModeSelected(mode) },
                                    enabled = !modeLocked,
                                    colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                                    modifier = Modifier
                                        .bringIntoViewRequester(inView)
                                        .semantics { role = Role.RadioButton }
                                ) {
                                    Text(text = mode.label, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }

                RecordingTime(
                    durationSeconds = durationSeconds,
                    modeName = shownMode?.label ?: currentMode.displayName,
                    isRecording = isRecording,
                    modifier = Modifier
                        .graphicsLayer { alpha = recordingAlpha }
                        .then(if (isRecording) Modifier else Modifier.clearAndSetSemantics {})
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            TactileShutterButton(
                currentMode = currentMode,
                isRecording = isRecording,
                // Waiting for the camera to answer either way: the shutter pulses and takes no tap
                isStarting = isStarting || isTakingPhoto,
                isStopping = isStopping,
                enabled = isConnected && !isAsleep,
                onClick = onShutterClick
            )

            Spacer(modifier = Modifier.height(14.dp))

            val actionLabel = if (isTakingPhoto) {
                "Taking Photo..."
            } else if (isStopping) {
                "Stopping..."
            } else if (isRecording) {
                if (shownMode?.isPhoto == true) "Stop ${shownMode.label}" else "Stop Recording"
            } else if (isStarting) {
                "Starting..."
            } else if (currentMode == CaptureMode.PHOTO) {
                when {
                    shownMode == null || shownMode == CameraMode.PHOTO -> "Take Photo"
                    shownMode.isContinuous -> "Start ${shownMode.label}"
                    else -> "Take ${shownMode.label}"
                }
            } else {
                "Start Recording"
            }

            Text(
                text = actionLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (!isConnected || isAsleep) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (!isConnected || isAsleep) {
                Text(
                    text = if (isAsleep) "Switch the camera on to control it" else "Connect the camera to control it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

