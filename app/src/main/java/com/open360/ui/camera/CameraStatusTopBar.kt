package com.open360.ui.camera

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.open360.core.connection.DirectConnectionState
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.ui.Insta360Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.CameraMode
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.rememberRecordingPulseAlpha
import com.open360.ui.theme.rememberReducedMotion
import com.open360.ui.theme.statusColors

/**
 * Persistent top camera status bar rendered across every main tab. Displays the active or saved
 * camera identity, connection state, quick link/refresh controls, and a live telemetry strip
 * for battery life, Action Pod status, storage space, time/photos left, and hardware warnings.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraStatusTopBar(
    title: String,
    subtitle: String,
    isRecording: Boolean,
    dotState: CameraStatusText.StatusDotState,
    ble: BleCameraState,
    isReady: Boolean,
    isAsleep: Boolean,
    cameraButton: CameraButton?,
    showQuickConnectButton: Boolean,
    showRefreshAction: Boolean,
    onCameraButton: (CameraButton) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    resolvedModel: Insta360CameraModel? = null,
    transportMode: CameraTransportMode = CameraTransportMode.BLE_DIRECT,
    directState: DirectConnectionState = DirectConnectionState(),
    onOpenConnectionMethods: (() -> Unit)? = null
) {
    val reducedMotion = rememberReducedMotion()
    val details = ble.details
    val effectiveSubModes = details.subModes ?: directState.subModes
    val effectiveCaptureSecondsLeft = details.captureSecondsLeft ?: directState.captureSecondsLeft
    val effectiveBattery = ble.batteryPercent ?: directState.batteryPercent
    val effectiveCharging = if (ble.batteryPercent != null) ble.isCharging else directState.isCharging
    val effectiveReady = isReady || directState.isConnected
    val activeTransport = (if (directState.isConnected) directState.transportMode else null) ?: transportMode
    val storageProblem = CameraStatusText.storageProblem(details.cardState)
    val hasTelemetry = CameraStatusText.hasTopStatus(
        batteryPercent = effectiveBattery,
        pod = details.pod,
        captureSecondsLeft = effectiveCaptureSecondsLeft,
        cardState = details.cardState,
        freeSpaceBytes = details.freeSpaceBytes,
        totalSpaceBytes = details.totalSpaceBytes,
        temperatureHigh = details.temperatureHigh,
        batteryLow = details.batteryLow
    )
    val showTelemetryRow = effectiveReady &&
        (hasTelemetry || effectiveSubModes != null || details.recordingState != null || directState.isConnected)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (onOpenConnectionMethods != null) {
                                Modifier
                                    .semantics { role = Role.Button }
                                    .clickable(onClick = onOpenConnectionMethods)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TopBarStatusDot(dotState = dotState, reducedMotion = reducedMotion)

                    if (resolvedModel != null) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(Insta360Icons.cameraDrawableRes(resolvedModel)),
                                contentDescription = resolvedModel.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(1.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (onOpenConnectionMethods != null) {
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = "Connection methods and cameras",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        val subtitleColor = when {
                            isRecording -> MaterialTheme.statusColors.recording
                            isAsleep -> MaterialTheme.statusColors.waiting
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                            color = subtitleColor,
                            fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showRefreshAction) {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh"
                            )
                        }
                    }

                    if (cameraButton != null) {
                        when {
                            cameraButton == CameraButton.DISCONNECT && showRefreshAction -> {
                                IconButton(onClick = { onCameraButton(cameraButton) }) {
                                    Icon(
                                        imageVector = disconnectIcon(activeTransport),
                                        contentDescription = "Disconnect"
                                    )
                                }
                            }
                            cameraButton == CameraButton.DISCONNECT -> {
                                OutlinedButton(
                                    onClick = { onCameraButton(cameraButton) },
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    modifier = Modifier.heightIn(min = 34.dp)
                                ) {
                                    Icon(
                                        imageVector = disconnectIcon(activeTransport),
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Disconnect",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            showQuickConnectButton && cameraButton == CameraButton.STOP -> {
                                OutlinedButton(
                                    onClick = { onCameraButton(cameraButton) },
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    modifier = Modifier.heightIn(min = 34.dp)
                                ) {
                                    Text(
                                        text = cameraButton.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            showQuickConnectButton -> {
                                Button(
                                    onClick = { onCameraButton(cameraButton) },
                                    shapes = ButtonDefaults.shapes(),
                                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                                    modifier = Modifier.heightIn(min = 34.dp)
                                ) {
                                    Text(
                                        text = cameraButton.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showTelemetryRow,
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
                val chipsScrollState = rememberScrollState()
                val surfaceBg = MaterialTheme.colorScheme.surfaceContainer

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipsScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val directTransport = directState.transportMode
                        if (directState.isConnected && directTransport != null) {
                            TopBarMetricChip(
                                icon = topBarTransportIcon(directTransport),
                                text = directTransport.shortLabel
                            )
                        }

                        if (details.temperatureHigh) {
                            TopBarMetricChip(
                                icon = Icons.Default.Warning,
                                text = "Temp High",
                                isProblem = true
                            )
                        }
                        if (details.batteryLow) {
                            TopBarMetricChip(
                                icon = Icons.Default.Warning,
                                text = "Battery Low",
                                isProblem = true
                            )
                        }
                        if (storageProblem != null) {
                            TopBarMetricChip(
                                iconRes = Insta360Icons.icSdError,
                                text = "Storage: $storageProblem",
                                isProblem = true
                            )
                        }

                        val batteryPercent = effectiveBattery
                        if (batteryPercent != null) {
                            val batteryLow = batteryPercent <= CameraStatusText.LOW_BATTERY_PERCENT
                            TopBarMetricChip(
                                icon = if (effectiveCharging) Icons.Default.BatteryChargingFull else null,
                                iconRes = if (!effectiveCharging) Insta360Icons.icBattery else null,
                                text = "$batteryPercent%",
                                iconTint = if (batteryLow) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.statusColors.ready
                                },
                                isProblem = batteryLow
                            )
                        }

                        details.pod?.let { pod ->
                            if (pod.connected) {
                                if (!pod.isOn) {
                                    TopBarMetricChip(
                                        icon = Icons.Default.Bolt,
                                        text = "Pod Off"
                                    )
                                } else {
                                    val podBattery = pod.battery
                                    if (podBattery != null) {
                                        val podLow = CameraStatusText.podIsLow(pod)
                                        val podTint = when {
                                            podLow -> MaterialTheme.colorScheme.error
                                            podBattery.onExternalPower -> MaterialTheme.statusColors.ready
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        TopBarMetricChip(
                                            icon = if (podBattery.onExternalPower) Icons.Default.BatteryChargingFull else Icons.Default.Bolt,
                                            text = "Pod ${podBattery.levelPercent}%",
                                            iconTint = podTint,
                                            isProblem = podLow
                                        )
                                    }
                                }
                            }
                        }

                        val freeSpaceBytes = details.freeSpaceBytes
                        val totalSpaceBytes = details.totalSpaceBytes
                        if (storageProblem == null && freeSpaceBytes != null && totalSpaceBytes != null) {
                            TopBarMetricChip(
                                iconRes = Insta360Icons.icSdOk,
                                text = CameraStatusText.storageSpaceCompact(freeSpaceBytes, totalSpaceBytes)
                            )
                        }

                        if (storageProblem == null && effectiveCaptureSecondsLeft != null) {
                            if (effectiveSubModes?.isPhoto == true) {
                                TopBarMetricChip(
                                    iconRes = Insta360Icons.captureModeDrawableRes(com.open360.core.model.CaptureMode.PHOTO),
                                    text = "${CameraStatusText.photosLeft(effectiveCaptureSecondsLeft)} photos"
                                )
                            } else {
                                TopBarMetricChip(
                                    icon = Icons.Default.Schedule,
                                    text = CameraStatusText.timeLeft(effectiveCaptureSecondsLeft)
                                )
                            }
                        }

                        val modeEnum = effectiveSubModes?.let { CameraMode.of(it) }
                        val activeMode = CameraStatusText.recordingMode(details.recordingState)
                            ?: modeEnum?.label
                        if (activeMode != null) {
                            TopBarMetricChip(
                                iconRes = modeEnum?.let { Insta360Icons.modeDrawableRes(it) }
                                    ?: if (effectiveSubModes?.isPhoto == true) {
                                        Insta360Icons.captureModeDrawableRes(com.open360.core.model.CaptureMode.PHOTO)
                                    } else {
                                        Insta360Icons.captureModeDrawableRes(com.open360.core.model.CaptureMode.VIDEO)
                                    },
                                text = activeMode
                            )
                        }
                    }

                    if (chipsScrollState.canScrollBackward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(20.dp)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(colors = listOf(surfaceBg, Color.Transparent))
                                )
                        )
                    }
                    if (chipsScrollState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(20.dp)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(colors = listOf(Color.Transparent, surfaceBg))
                                )
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun TopBarStatusDot(
    dotState: CameraStatusText.StatusDotState,
    reducedMotion: Boolean
) {
    if (dotState == CameraStatusText.StatusDotState.DISCONNECTED) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        )
        return
    }
    val targetDotColor = when (dotState) {
        CameraStatusText.StatusDotState.RECORDING -> MaterialTheme.statusColors.recording
        CameraStatusText.StatusDotState.CONNECTED -> MaterialTheme.statusColors.ready
        CameraStatusText.StatusDotState.STANDBY -> MaterialTheme.statusColors.waiting
        CameraStatusText.StatusDotState.CONNECTING -> MaterialTheme.colorScheme.primary
        CameraStatusText.StatusDotState.DISCONNECTED -> Color.Transparent
    }
    val animatedDotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = tween(durationMillis = if (reducedMotion) 150 else 180, easing = EaseOutCubic),
        label = "topBarDotColor"
    )
    val pulseAlpha = rememberRecordingPulseAlpha(
        active = dotState == CameraStatusText.StatusDotState.RECORDING
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .graphicsLayer { alpha = pulseAlpha.value }
            .clip(CircleShape)
            .background(animatedDotColor)
    )
}

@Composable
private fun TopBarMetricChip(
    text: String,
    icon: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
    iconTint: Color? = null,
    isProblem: Boolean = false
) {
    val bgColor = if (isProblem) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isProblem) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val resolvedIconTint = when {
        iconTint != null -> iconTint
        isProblem -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (isProblem) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                },
                shape = CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = resolvedIconTint,
                modifier = Modifier.size(14.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedIconTint,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.merge(TabularFigures),
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun topBarTransportIcon(mode: CameraTransportMode): ImageVector = when (mode) {
    CameraTransportMode.BLE_DIRECT -> Icons.Default.Bluetooth
    CameraTransportMode.USB_C_DIRECT -> Icons.Default.Usb
    CameraTransportMode.WIFI_SOFTAP -> Icons.Default.Wifi
    CameraTransportMode.WIFI_STATION -> Icons.Default.Router
    CameraTransportMode.GPS_REMOTE_SERVER -> Icons.Default.DeveloperBoard
    CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> Icons.Default.SettingsBluetooth
}

private fun disconnectIcon(mode: CameraTransportMode): ImageVector = when (mode) {
    CameraTransportMode.USB_C_DIRECT -> Icons.Default.UsbOff
    CameraTransportMode.WIFI_SOFTAP,
    CameraTransportMode.WIFI_STATION -> Icons.Default.WifiOff
    CameraTransportMode.BLE_DIRECT,
    CameraTransportMode.GPS_REMOTE_SERVER,
    CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> Icons.Default.BluetoothDisabled
}
