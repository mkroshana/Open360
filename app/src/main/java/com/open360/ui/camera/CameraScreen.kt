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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.gps.GpxTrackState
import com.open360.core.model.CaptureMode
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
                }
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
fun CameraStatusCard(
    uiState: RemoteUiState,
    onCameraButton: (CameraButton) -> Unit
) {
    val ble = uiState.bleState
    val server = uiState.serverState
    val button = CameraButton.of(ble, uiState.isConnected)
    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake
    val isReady = uiState.isConnected && !isAsleep

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Device Identity & Readouts / Restrained Disconnect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Camera",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Readouts: Mode string, Battery Level & Compact Disconnect
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val cameraDisplayString = server.cameraDisplayString
                    if (cameraDisplayString != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = cameraDisplayString,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    val batteryPercent = ble.batteryPercent
                    if (batteryPercent != null) {
                        val batteryColor = if (batteryPercent > 20) Color(0xFF34C759) else MaterialTheme.colorScheme.error
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(batteryColor.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = if (ble.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                contentDescription = "Battery Level",
                                modifier = Modifier.size(16.dp),
                                tint = batteryColor
                            )
                            Text(
                                text = "${ble.batteryPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = batteryColor
                            )
                        }
                    }

                    // Compact, restrained Disconnect pill button when connected
                    if (isReady) {
                        val disconnectInteraction = remember { MutableInteractionSource() }
                        val disconnectScale by rememberPressScale(disconnectInteraction, targetScale = 0.95f)

                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = disconnectScale
                                    scaleY = disconnectScale
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = disconnectInteraction,
                                    indication = null
                                ) { onCameraButton(button) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BluetoothDisabled,
                                    contentDescription = "Disconnect",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Disconnect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (isReady) {
                // When connected and awake: Show vitals cleanly in structured tiles
                CameraDetailRows(ble = ble)
            } else {
                // When not ready (disconnected, searching, asleep, connecting)
                Spacer(modifier = Modifier.height(12.dp))

                val statusColor = when {
                    server.isCameraConnected -> Color(0xFF34C759)
                    isAsleep -> Color(0xFFFF9500)
                    server.isAdvertising || ble.connectionStatus == BleConnectionStatus.SCANNING -> Color(0xFF007AFF)
                    ble.connectionStatus == BleConnectionStatus.CONNECTING -> Color(0xFF007AFF)
                    else -> MaterialTheme.colorScheme.outline
                }

                val statusText = when {
                    server.isCameraConnected -> "Connected as GPS Remote"
                    isAsleep && ble.isWaking -> "Switching Camera On..."
                    isAsleep -> "Camera Is Off"
                    server.isAdvertising -> "Broadcasting as GPS Remote"
                    ble.connectionStatus == BleConnectionStatus.SCANNING -> "Looking for Camera..."
                    ble.connectionStatus == BleConnectionStatus.CONNECTING -> "Connecting to Camera..."
                    else -> "Disconnected"
                }

                val statusIcon = when {
                    isAsleep -> Icons.Default.PowerSettingsNew
                    uiState.isConnected -> Icons.Default.CheckCircle
                    server.isAdvertising || ble.connectionStatus == BleConnectionStatus.SCANNING -> Icons.Default.Sync
                    else -> Icons.Default.BluetoothDisabled
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = statusColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Helpful instructions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Instructions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when {
                            isAsleep && ble.isWaking -> "Switching the camera on. It takes a few seconds."
                            button == CameraButton.STOP -> "Looking for the camera. Keep it nearby."
                            isAsleep -> "The camera is nearby but switched off. Tap Wake Up or press the camera's button to switch it on."
                            uiState.wakeUpId != null -> "Keep the camera nearby and tap Connect. Open360 switches it on if it is off."
                            else -> "Turn the camera on, keep it nearby and tap Connect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Call to Action Button
                val buttonIcon = when (button) {
                    CameraButton.CONNECT -> Icons.Default.Bluetooth
                    CameraButton.STOP -> Icons.Default.Stop
                    CameraButton.WAKE_UP -> Icons.Default.PowerSettingsNew
                    CameraButton.DISCONNECT -> Icons.Default.BluetoothDisabled
                }
                val buttonInteraction = remember { MutableInteractionSource() }
                val buttonScale by rememberPressScale(buttonInteraction, targetScale = 0.97f)

                if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
                    FilledTonalButton(
                        onClick = { onCameraButton(button) },
                        interactionSource = buttonInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .graphicsLayer {
                                scaleX = buttonScale
                                scaleY = buttonScale
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = button.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onCameraButton(button) },
                        interactionSource = buttonInteraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .graphicsLayer {
                                scaleX = buttonScale
                                scaleY = buttonScale
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = button.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
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

    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // High visibility warning pills
        warnings.forEach { warning ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.value,
                                style = MaterialTheme.typography.bodyMedium,
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodySmall,
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

    val headerInteraction = remember { MutableInteractionSource() }
    val headerScale by rememberPressScale(headerInteraction, targetScale = 0.99f)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row (Clickable to toggle minimize/expand)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = headerScale
                        scaleY = headerScale
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = headerInteraction,
                        indication = null
                    ) { onToggleMinimize() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "GPS Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Satellite fix indicator pill
                    val fixColor = if (gps.hasFix) Color(0xFF34C759) else Color(0xFFFF9500)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(fixColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(fixColor)
                        )
                        Text(
                            text = if (gps.hasFix) {
                                if (gps.satellitesInFix == 1) "1 Satellite" else "${gps.satellitesInFix} Satellites"
                            } else "Searching...",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = fixColor
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isMinimized) "Expand GPS Card" else "Minimize GPS Card",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Speed Display & Live Status Row
            val speedDisplay = if (gps.hasFix) {
                val speedValue = if (isSpeedMph) gps.speedMph else gps.speedKmh
                String.format(Locale.US, "%.1f", speedValue)
            } else {
                "—"
            }
            val unitText = if (isSpeedMph) "mph" else "km/h"

            val streamActive = isCameraConnected && gps.hasFix
            val badgeBg = if (streamActive) Color(0xFF34C759).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
            val badgeColor = if (streamActive) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
            val compactStreamText = if (streamActive) {
                if (isRecording) {
                    if (track.pointCount > 0) "Sending (${track.pointCount})" else "Sending GPS"
                } else {
                    "GPS Ready"
                }
            } else null

            Box(modifier = Modifier.fillMaxWidth()) {
                val unitInteraction = remember { MutableInteractionSource() }
                val unitScale by rememberPressScale(unitInteraction, targetScale = 0.94f)

                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = speedDisplay,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 42.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .graphicsLayer {
                                scaleX = unitScale
                                scaleY = unitScale
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable(
                                interactionSource = unitInteraction,
                                indication = null
                            ) { onToggleSpeedUnit() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = unitText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // Streaming status badge when active/ready, or live altitude readout when searching or idle
                if (streamActive && compactStreamText != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF34C759).copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "GPS Status",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF34C759)
                        )
                        Text(
                            text = compactStreamText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF34C759)
                        )
                    }
                } else {
                    val altitudeDisplay = if (gps.hasFix) String.format(Locale.US, "%.0f m", gps.altitudeMeters) else "— m"
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Height,
                            contentDescription = "Altitude",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = altitudeDisplay,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Expanded detail section: telemetry metrics, full-width stream badge, route file details
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

                    // Detailed telemetry grid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryMetricItem(
                            icon = Icons.Default.Height,
                            label = "Altitude",
                            value = if (gps.hasFix) String.format(Locale.US, "%.1f m", gps.altitudeMeters) else "—",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricItem(
                            icon = Icons.Default.Speed,
                            label = "Accuracy",
                            value = if (gps.hasFix) String.format(Locale.US, "±%.1f m", gps.accuracyMeters) else "—",
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricItem(
                            icon = Icons.Default.LocationOn,
                            label = "Heading",
                            value = if (gps.hasFix) String.format(Locale.US, "%.0f°", gps.bearingDegrees) else "—",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val streamText = when {
                        streamActive && isRecording && joinedLateSeconds != null ->
                            "Sending GPS, Joined $joinedLateSeconds s After Start"
                        streamActive && isRecording -> "Sending GPS to Camera"
                        streamActive -> "GPS Ready, Sent While Recording"
                        isCameraConnected && !gps.hasFix -> "Waiting for GPS"
                        else -> "No Camera Connected"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(badgeBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (streamActive) Icons.Default.CheckCircle else Icons.Default.Sync,
                                contentDescription = "GPS Sending Status",
                                modifier = Modifier.size(16.dp),
                                tint = badgeColor
                            )
                            Text(
                                text = streamText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = badgeColor
                            )
                        }

                        if (lastGpsSentTimeMs > 0) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
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
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Apple-grade segmented mode selector: Video and Photo with sliding highlight indicator pill.
 */
@Composable
fun ModeSegmentedControl(
    currentMode: CaptureMode,
    enabled: Boolean,
    onModeSelected: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(CaptureMode.VIDEO, CaptureMode.PHOTO)
    val selectedIndex = if (currentMode == CaptureMode.VIDEO) 0 else 1

    val targetOffset by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 450f
        ),
        label = "modeIndicator"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(3.dp)
    ) {
        val tabWidth = maxWidth / modes.size

        // Sliding indicator pill
        Box(
            modifier = Modifier
                .width(tabWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = targetOffset * tabWidth.toPx()
                }
                .clip(RoundedCornerShape(19.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val isSelected = currentMode == mode
                val interactionSource = remember { MutableInteractionSource() }
                val tabScale by rememberPressScale(interactionSource, targetScale = 0.96f)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = tabScale
                            scaleY = tabScale
                        }
                        .clip(RoundedCornerShape(19.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = enabled
                        ) {
                            if (!isSelected) onModeSelected(mode)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (mode == CaptureMode.VIDEO) Icons.Default.Videocam else Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (mode == CaptureMode.VIDEO) "Video" else "Photo",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tactile physical shutter button: scales down on pointer-down with spring physics and morphs
 * between round recording trigger, square stop button, and photo capture button.
 */
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
    val ringColor = if (isVideo) Color(0xFFFF3B30) else MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)
    val innerColor = if (isVideo) Color(0xFFFF3B30) else Color.White

    val busyTransition = rememberInfiniteTransition(label = "busyPulse")
    val busyAlpha by if (isStarting || isStopping) {
        busyTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "busyAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Box(
        modifier = modifier
            .size(86.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (!enabled) 0.4f else busyAlpha
            }
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.08f))
            .border(3.5.dp, ringColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isStarting && !isStopping,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCornerRadius))
                .background(innerColor)
                .border(
                    width = if (!isVideo) 0.5.dp else 0.dp,
                    color = if (!isVideo) Color.Black.copy(alpha = 0.15f) else Color.Transparent,
                    shape = RoundedCornerShape(innerCornerRadius)
                )
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

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Segmented Control (Video / Photo)
            ModeSegmentedControl(
                currentMode = currentMode,
                enabled = !modeLocked,
                onModeSelected = onModeSelected,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Sub-mode pills (FreeFrame Video, TimeShift, Timelapse, etc.)
            if (cameraMode != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CameraMode.group(photo = currentMode == CaptureMode.PHOTO).forEach { mode ->
                        val isSelected = mode == shownMode
                        val chipInteraction = remember { MutableInteractionSource() }
                        val chipScale by rememberPressScale(chipInteraction, targetScale = 0.95f)
                        // The row is wider than the screen, so the camera's mode is scrolled into
                        // view whenever it changes, from here or on the camera
                        val inView = remember { BringIntoViewRequester() }
                        LaunchedEffect(isSelected) { if (isSelected) inView.bringIntoView() }

                        Box(
                            modifier = Modifier
                                .bringIntoViewRequester(inView)
                                .graphicsLayer {
                                    scaleX = chipScale
                                    scaleY = chipScale
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable(
                                    interactionSource = chipInteraction,
                                    indication = null,
                                    enabled = !modeLocked
                                ) {
                                    onCameraModeSelected(mode)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Shutter Button: Position is 100% INVARIANT (never displaced by recording HUD)
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

            // Live Recording Timer Display (placed below shutter, expanding downward)
            AnimatedVisibility(
                visible = isRecording,
                enter = fadeIn(tween(160, easing = EaseOutCubic)) + expandVertically(animationSpec = tween(200, easing = EaseOutCubic)),
                exit = fadeOut(tween(100, easing = EaseOutCubic)) + shrinkVertically(animationSpec = tween(160, easing = EaseOutCubic))
            ) {
                // 01:05, or 1:02:09 once it passes an hour, as on the watch
                val hours = durationSeconds / 3600
                val mins = durationSeconds % 3600 / 60
                val secs = durationSeconds % 60
                val formatted = if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
                else String.format(Locale.US, "%02d:%02d", mins, secs)

                val pulseTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by pulseTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "pulseAlpha"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFF3B30).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFFF3B30).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B30).copy(alpha = pulseAlpha))
                    )
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF3B30)
                    )
                }
            }

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

