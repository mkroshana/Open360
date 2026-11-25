package com.open360.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.open360.core.connection.ConnectionSettings
import com.open360.core.connection.DirectConnectionState
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.ui.Insta360Icons
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.gps.GpxTrackState
import com.open360.core.model.CaptureMode
import com.open360.core.peer.LinkMove
import com.open360.core.sensors.SensorKind
import com.open360.core.sensors.SportsSensorsState
import com.open360.ui.theme.EaseDrawer
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.PillSegmentOption
import com.open360.ui.theme.PillSegmentedControl
import com.open360.ui.theme.SurfaceCard
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.middleEllipsize
import com.open360.ui.theme.pluralize
import com.open360.ui.theme.rememberPressScale
import com.open360.ui.theme.rememberReducedMotion
import com.open360.ui.theme.statusColors
import java.util.Locale

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onEnsureCameraPermissions: ((() -> Unit) -> Unit) = { it() },
    connectionSettings: ConnectionSettings = ConnectionSettings(),
    directState: DirectConnectionState = DirectConnectionState(),
    onOpenConnectionMethods: () -> Unit = {},
    onConnectDirect: (CameraTransportMode) -> Unit = {},
    onDisconnectDirect: () -> Unit = {},
    onToggleGpsRemoteServer: (Boolean) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val sensors by viewModel.sportsSensors.state.collectAsState()
    val ble = uiState.bleState
    val server = uiState.serverState
    val gps = uiState.gpsState
    val haptic = LocalHapticFeedback.current

    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake
    val effectiveConnected = uiState.isConnected || directState.isConnected
    val effectiveRecording = uiState.isRecording || directState.isRecording
    val resolvedModel = ble.resolvedModel ?: directState.cameraModel ?: connectionSettings.selectedModelOverride

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
                connectionSettings = connectionSettings,
                directState = directState,
                resolvedModel = resolvedModel,
                onOpenConnectionMethods = onOpenConnectionMethods,
                onConnectDirect = onConnectDirect,
                onDisconnectDirect = onDisconnectDirect,
                onToggleGpsRemoteServer = onToggleGpsRemoteServer,
                onCameraButton = { button ->
                    if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEnsureCameraPermissions { viewModel.onCameraButton(button) }
                    } else {
                        viewModel.onCameraButton(button)
                    }
                },
                onMoveLink = { viewModel.moveLink() }
            )

            // Live GPS Telemetry Card
            val lastGpsSentTime = if (server.lastGpsSentTimeMs > 0) server.lastGpsSentTimeMs else ble.lastGpsSentTimeMs
            GpsTelemetryCard(
                gps = gps,
                isSpeedMph = uiState.isSpeedMph,
                isRecording = effectiveRecording,
                isCameraConnected = effectiveConnected,
                isMinimized = uiState.isGpsCardMinimized,
                joinedLateSeconds = ble.joinedRecordingSeconds,
                lastGpsSentTimeMs = lastGpsSentTime,
                track = uiState.trackState,
                isWatchGps = uiState.linkedThroughPeer,
                onToggleSpeedUnit = { viewModel.toggleSpeedUnit() },
                onToggleMinimize = { viewModel.toggleGpsCardMinimized() }
            )

            SportsSensorsLiveRow(sensors = sensors)

            Spacer(modifier = Modifier.height(4.dp))
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
        Text(
            text = text,
            style = MaterialTheme.typography.titleMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
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
            style = MaterialTheme.typography.labelMedium.merge(TabularFigures),
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// LoadingIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraStatusCard(
    uiState: RemoteUiState,
    onCameraButton: (CameraButton) -> Unit,
    onMoveLink: () -> Unit = {},
    connectionSettings: ConnectionSettings = ConnectionSettings(),
    directState: DirectConnectionState = DirectConnectionState(),
    resolvedModel: Insta360CameraModel? = null,
    onOpenConnectionMethods: () -> Unit = {},
    onConnectDirect: (CameraTransportMode) -> Unit = {},
    onDisconnectDirect: () -> Unit = {},
    onToggleGpsRemoteServer: (Boolean) -> Unit = {}
) {
    val ble = uiState.bleState
    val server = uiState.serverState
    val button = CameraButton.of(ble, uiState.isConnected)
    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake
    val isReady = uiState.isConnected && !isAsleep
    val reducedMotion = rememberReducedMotion()
    val preferredTransport = connectionSettings.preferredTransport
    val displayModel = resolvedModel ?: Insta360CameraModel.GO_3S

    val hasPeerBanner = uiState.linkMove != null || uiState.linkedThroughPeer || uiState.canMoveLink
    val lastClipFile = ble.lastVideoFile
    val hasError = server.lastError != null || ble.lastError != null || directState.lastError != null
    val hasDirectRow = isReady && (directState.isConnected || preferredTransport != CameraTransportMode.BLE_DIRECT)
    val hasPrimaryContent = !isReady || lastClipFile != null || hasDirectRow

    // When the camera is connected and ready over BLE, CameraStatusTopBar owns device identity,
    // battery, Action Pod, storage, time left, warnings, and Disconnect.
    if (isReady && !directState.isConnected && preferredTransport == CameraTransportMode.BLE_DIRECT &&
        !hasPeerBanner && lastClipFile == null && !hasError
    ) return

    SurfaceCard {
        Column(modifier = Modifier.padding(if (isReady) 16.dp else 20.dp)) {
            if (isReady) {
                if (lastClipFile != null) {
                    val length = ble.lastVideoSeconds?.let { CameraStatusText.clipLength(it) } ?: "Saved"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                shape = MaterialTheme.shapes.large
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Last Clip",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = middleEllipsize(lastClipFile, 28),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = length,
                            style = MaterialTheme.typography.labelLarge.merge(TabularFigures),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (hasDirectRow) {
                    if (lastClipFile != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TransportMethodPill(
                                preferredTransport = preferredTransport,
                                onClick = onOpenConnectionMethods
                            )
                        }
                        when {
                            directState.isConnected -> {
                                OutlinedButton(
                                    onClick = onDisconnectDirect,
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text(
                                        text = "Disconnect",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            preferredTransport == CameraTransportMode.GPS_REMOTE_SERVER -> {
                                val serverActive = server.isAdvertising || server.isCameraConnected
                                if (serverActive) {
                                    OutlinedButton(
                                        onClick = { onToggleGpsRemoteServer(false) },
                                        shapes = ButtonDefaults.shapes(),
                                        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                        modifier = Modifier.heightIn(min = 36.dp)
                                    ) {
                                        Text(
                                            text = "Stop",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = { onToggleGpsRemoteServer(true) },
                                        shapes = ButtonDefaults.shapes(),
                                        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                        modifier = Modifier.heightIn(min = 36.dp)
                                    ) {
                                        Text(
                                            text = "Start",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                            else -> {
                                FilledTonalButton(
                                    enabled = !directState.isConnecting,
                                    onClick = { onConnectDirect(preferredTransport) },
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text(
                                        text = if (directState.isConnecting) "Connecting..." else "Connect",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
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
                    ble.connectionStatus == BleConnectionStatus.CONNECTING ||
                    directState.isConnecting

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        if (inProgress) {
                            LoadingIndicator(modifier = Modifier.size(48.dp), color = statusColor)
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(Insta360Icons.cameraDrawableRes(displayModel)),
                                    contentDescription = displayModel.displayName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .padding(2.dp)
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                directState.isConnected -> directState.deviceLabel ?: "Connected (${preferredTransport.shortLabel})"
                                directState.isConnecting -> "Connecting ${preferredTransport.label}..."
                                else -> statusText
                            },
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        // Two lines kept for it, so the button under it stays put from Connect to Stop
                        Text(
                            text = when {
                                directState.isConnected -> "${preferredTransport.label} active • Tap the shutter below or open Live View"
                                directState.isConnecting -> "Opening ${preferredTransport.label} session..."
                                preferredTransport != CameraTransportMode.BLE_DIRECT -> preferredTransport.summary
                                isAsleep && ble.isWaking -> "Switching the camera on. It takes a few seconds."
                                button == CameraButton.STOP -> "Looking for the camera. Keep it nearby."
                                isAsleep -> "Camera is nearby but off. Tap Wake Up or press its button to turn it on."
                                uiState.wakeUpId != null -> "Keep the camera nearby and tap Connect. Open360 switches it on if it is off."
                                else -> "Turn the camera on, keep it nearby and tap Connect."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TransportMethodPill(
                            preferredTransport = preferredTransport,
                            onClick = onOpenConnectionMethods
                        )
                    }
                }

                // Nothing to press until a link that is moving has arrived
                if (uiState.linkMove == null) {
                    Spacer(modifier = Modifier.height(18.dp))

                    val buttonModifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)

                    when (preferredTransport) {
                        CameraTransportMode.BLE_DIRECT -> {
                            val buttonIcon = when (button) {
                                CameraButton.CONNECT -> Icons.Default.Bluetooth
                                CameraButton.STOP -> Icons.Default.Stop
                                CameraButton.WAKE_UP -> Icons.Default.PowerSettingsNew
                                CameraButton.DISCONNECT -> Icons.Default.BluetoothDisabled
                            }
                            if (button == CameraButton.CONNECT || button == CameraButton.WAKE_UP) {
                                Button(
                                    onClick = { onCameraButton(button) },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = buttonModifier
                                ) {
                                    Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = button.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onCameraButton(button) },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = buttonModifier
                                ) {
                                    Icon(imageVector = buttonIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = button.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        CameraTransportMode.GPS_REMOTE_SERVER -> {
                            val serverActive = server.isAdvertising || server.isCameraConnected
                            if (serverActive) {
                                OutlinedButton(
                                    onClick = { onToggleGpsRemoteServer(false) },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = buttonModifier
                                ) {
                                    Text(
                                        text = "Stop GPS Remote Server",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { onToggleGpsRemoteServer(true) },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = buttonModifier
                                ) {
                                    Text(
                                        text = "Start GPS Remote Server",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        CameraTransportMode.USB_C_DIRECT,
                        CameraTransportMode.WIFI_SOFTAP,
                        CameraTransportMode.WIFI_STATION,
                        CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> {
                            if (directState.isConnected) {
                                OutlinedButton(
                                    onClick = onDisconnectDirect,
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = buttonModifier
                                ) {
                                    Text(
                                        text = "Disconnect ${preferredTransport.shortLabel}",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                Button(
                                    enabled = !directState.isConnecting,
                                    onClick = { onConnectDirect(preferredTransport) },
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = buttonModifier
                                ) {
                                    Text(
                                        text = if (directState.isConnecting) "Connecting..." else "Connect via ${preferredTransport.shortLabel}",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val peerTopSpacing = when {
                !isReady -> 16.dp
                hasPrimaryContent -> 12.dp
                else -> 0.dp
            }
            when {
                uiState.linkMove == LinkMove.TO_OTHER_DEVICE -> PeerLinkRow(
                    text = "Moving the link to your watch",
                    topSpacing = peerTopSpacing
                )
                uiState.linkMove == LinkMove.TO_THIS_DEVICE -> PeerLinkRow(
                    text = "Moving the link to this phone",
                    topSpacing = peerTopSpacing
                )
                uiState.linkedThroughPeer -> PeerLinkRow(
                    text = "Controlled through Open360 on your watch",
                    action = "Move Here",
                    actionEnabled = uiState.canMoveLink,
                    topSpacing = peerTopSpacing,
                    onAction = onMoveLink
                )
                // For leaving the phone behind
                uiState.canMoveLink -> PeerLinkRow(
                    text = "Open360 is open on your watch",
                    action = "Move to Watch",
                    topSpacing = peerTopSpacing,
                    onAction = onMoveLink
                )
            }

            AnimatedVisibility(
                visible = hasError,
                enter = if (reducedMotion) {
                    fadeIn(tween(150, easing = EaseOutCubic))
                } else {
                    fadeIn(tween(180, easing = EaseOutCubic)) +
                        expandVertically(tween(200, easing = EaseOutCubic))
                },
                exit = if (reducedMotion) {
                    fadeOut(tween(120, easing = EaseOutCubic))
                } else {
                    fadeOut(tween(120, easing = EaseOutCubic)) +
                        shrinkVertically(tween(160, easing = EaseOutCubic))
                }
            ) {
                Text(
                    text = server.lastError ?: ble.lastError ?: directState.lastError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = if (hasPrimaryContent || hasPeerBanner) 8.dp else 0.dp)
                )
            }
        }
    }
}

@Composable
private fun TransportMethodPill(
    preferredTransport: CameraTransportMode,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "Method: ${preferredTransport.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = "Change connection method",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
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
    topSpacing: Dp = 16.dp,
    onAction: () -> Unit = {}
) {
    if (topSpacing > 0.dp) {
        Spacer(modifier = Modifier.height(topSpacing))
    }
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

@Composable
private fun SportsSensorsLiveRow(sensors: SportsSensorsState) {
    val connected = sensors.saved.filter { it.connected }
    if (connected.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        connected.forEach { status ->
            val readings = listOfNotNull(
                status.heartRateBpm?.let { "$it bpm" },
                status.cadenceRpm?.let { "$it rpm" },
                status.powerWatts?.let { "$it W" }
            ).joinToString(" • ")
            val icon = when {
                SensorKind.HEART_RATE in status.device.kinds -> Icons.Default.MonitorHeart
                SensorKind.POWER in status.device.kinds -> Icons.Default.Bolt
                else -> Icons.Default.PedalBike
            }
            StatusPill(
                text = readings.ifEmpty { "${status.device.name} linked" },
                color = MaterialTheme.statusColors.ready,
                icon = icon,
                iconDescription = status.device.name
            )
        }
    }
}

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
    val haptic = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val headerInteraction = remember { MutableInteractionSource() }
    val headerScale by rememberPressScale(headerInteraction, targetScale = 0.985f)
    val unitInteraction = remember { MutableInteractionSource() }
    val unitScale by rememberPressScale(unitInteraction, targetScale = 0.95f)
    val expandEasing = EaseDrawer
    val collapseEasing = EaseOutCubic
    val transition = updateTransition(targetState = isMinimized, label = "GpsCardTransition")

    val chevronRotation by transition.animateFloat(
        transitionSpec = {
            if (reducedMotion) tween(0)
            else if (targetState) tween(durationMillis = 200, easing = collapseEasing)
            else tween(durationMillis = 260, easing = expandEasing)
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
                    .graphicsLayer {
                        scaleX = headerScale
                        scaleY = headerScale
                    }
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(interactionSource = headerInteraction, indication = null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleMinimize()
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardTitle(
                    icon = Icons.Default.LocationOn,
                    text = if (isWatchGps) "Watch GPS" else "GPS Telemetry",
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        text = if (gps.hasFix) pluralize(gps.satellitesInFix, "Satellite", "Satellites") else "Searching...",
                        color = if (gps.hasFix) MaterialTheme.statusColors.ready else MaterialTheme.statusColors.waiting,
                        modifier = Modifier.widthIn(min = 112.dp),
                        dot = true
                    )

                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isMinimized) "Expand GPS Card" else "Minimize GPS Card",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
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
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.widthIn(min = 64.dp)
                    )
                    // Tapped to switch between km/h and mph
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleSpeedUnit()
                        },
                        interactionSource = unitInteraction,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.graphicsLayer {
                            scaleX = unitScale
                            scaleY = unitScale
                        }
                    ) {
                        Text(
                            text = unitText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
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
                            modifier = Modifier.widthIn(min = 100.dp),
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
                enter = if (reducedMotion) {
                    fadeIn(tween(150, easing = EaseOutCubic))
                } else {
                    fadeIn(tween(180, delayMillis = 60, easing = EaseOutCubic)) +
                        expandVertically(
                            animationSpec = tween(durationMillis = 280, easing = expandEasing),
                            expandFrom = Alignment.Top
                        )
                },
                exit = if (reducedMotion) {
                    fadeOut(tween(120, easing = EaseOutCubic))
                } else {
                    fadeOut(tween(80, easing = EaseOutCubic)) +
                        shrinkVertically(
                            animationSpec = tween(durationMillis = 200, easing = collapseEasing),
                            shrinkTowards = Alignment.Top
                        )
                }
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                            "Sending GPS (joined ${joinedLateSeconds}s after start)"
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
                                color = badgeColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (track.fileName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Route: ${middleEllipsize("${track.folder}/${track.fileName}", 36)} (${pluralize(track.pointCount, "point", "points")})",
                            style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.large
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Video and Photo as a recessed capsule segmented control with GPU-translated active indicator.
 */
@Composable
fun ModeSegmentedControl(
    currentMode: CaptureMode,
    enabled: Boolean,
    onModeSelected: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
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
        enabled = enabled,
        role = Role.RadioButton,
        minHeight = 46.dp,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Tactile physical shutter button: scales subtly on press via [rememberPressScale] and morphs
 * on the GPU draw layer between round recording trigger, square stop button, and photo capture
 * button. While the camera is asked to start, stop or take a photo, it shows Material's loading
 * indicator and takes no tap.
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
    modifier: Modifier = Modifier,
    size: Dp = 86.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val reducedMotion = rememberReducedMotion()
    val pressScale by rememberPressScale(interactionSource, targetScale = 0.95f)

    val ringStroke = (size * 0.041f).coerceIn(2.5.dp, 3.5.dp)
    val innerIdleSize = size * 0.65f
    val innerRecSize = size * 0.33f
    val innerRecCorner = (size * 0.095f).coerceIn(4.dp, 8.dp)
    val innerIdleCorner = innerIdleSize / 2f

    val morphSpec = if (reducedMotion) {
        snap<Dp>()
    } else {
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 450f)
    }
    val innerCornerRadiusState = animateDpAsState(
        targetValue = if (isRecording) innerRecCorner else innerIdleCorner,
        animationSpec = morphSpec,
        label = "innerCornerRadius"
    )
    val innerSizeState = animateDpAsState(
        targetValue = if (isRecording) innerRecSize else innerIdleSize,
        animationSpec = morphSpec,
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
        else -> MaterialTheme.colorScheme.onSurface
    }
    val photoBorderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.08f))
            .border(ringStroke, ringColor, CircleShape)
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
                modifier = Modifier.size(innerIdleSize),
                color = if (isVideo) MaterialTheme.statusColors.recording else MaterialTheme.colorScheme.onSurface
            )
        } else {
            Box(
                modifier = Modifier
                    .size(innerIdleSize)
                    .drawBehind {
                        val side = innerSizeState.value.toPx()
                        val radius = innerCornerRadiusState.value.toPx()
                        val topLeft = Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f)
                        val rectSize = Size(side, side)
                        val corner = CornerRadius(radius, radius)
                        drawRoundRect(
                            color = innerColor,
                            topLeft = topLeft,
                            size = rectSize,
                            cornerRadius = corner
                        )
                        if (!isVideo && enabled) {
                            drawRoundRect(
                                color = photoBorderColor,
                                topLeft = topLeft,
                                size = rectSize,
                                cornerRadius = corner,
                                style = Stroke(width = 0.5.dp.toPx())
                            )
                        }
                    }
            )
        }
    }
}

