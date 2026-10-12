package com.open360.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.gps.GpxTrackState
import com.open360.core.model.CaptureMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onWakeUpClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val ble = uiState.bleState
    val server = uiState.serverState
    val gps = uiState.gpsState
    val haptic = LocalHapticFeedback.current

    val deviceTitle = when {
        server.isCameraConnected -> {
            server.cameraSerial?.let { "Insta360 ($it)" } ?: "Insta360 GO 3S"
        }
        ble.connectionStatus == BleConnectionStatus.CONNECTED -> {
            ble.deviceName ?: "Insta360 GO 3S"
        }
        server.isAdvertising -> {
            "Insta360 GPS Remote"
        }
        else -> "Not Connected"
    }

    val deviceSubtitle = when {
        uiState.isConnected -> "Connected"
        server.isAdvertising -> "Broadcasting"
        else -> "Disconnected"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Open360 Remote",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$deviceTitle — $deviceSubtitle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (uiState.isConnected) {
                                viewModel.disconnectBle()
                            } else {
                                viewModel.connectBle()
                            }
                        }
                    ) {
                        val icon = when {
                            uiState.isConnected -> Icons.Default.BluetoothConnected
                            server.isAdvertising || ble.connectionStatus == BleConnectionStatus.SCANNING -> Icons.Default.Sync
                            else -> Icons.Default.Bluetooth
                        }
                        Icon(icon, contentDescription = "Bluetooth Status")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                onWakeUp = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.wakeUpCamera()
                },
                onWakeScreen = { viewModel.wakeScreen() },
                onPowerOff = { viewModel.powerOff() },
                onConnectToggle = {
                    if (uiState.isConnected) {
                        viewModel.disconnectBle()
                    } else {
                        viewModel.connectBle()
                    }
                }
            )

            // Live GPS Telemetry Card
            val lastGpsSentTime = if (server.lastGpsSentTimeMs > 0) server.lastGpsSentTimeMs else ble.lastGpsSentTimeMs
            GpsTelemetryCard(
                gps = gps,
                isSpeedMph = uiState.isSpeedMph,
                isRecording = uiState.isRecording,
                isCameraConnected = uiState.isConnected,
                joinedLateSeconds = ble.joinedRecordingSeconds,
                lastGpsSentTimeMs = lastGpsSentTime,
                track = uiState.trackState,
                onToggleSpeedUnit = { viewModel.toggleSpeedUnit() }
            )

            // Primary Camera Shutter & Mode Controller
            CameraControlCard(
                currentMode = uiState.currentMode,
                isRecording = uiState.isRecording,
                isStarting = uiState.bleState.isCaptureStarting,
                isStopping = uiState.bleState.isCaptureStopping,
                durationSeconds = uiState.recordingDurationSeconds,
                isConnected = uiState.isConnected,
                isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake,
                onModeSelected = { viewModel.setMode(it) },
                onCycleMode = { viewModel.cycleMode() },
                onShutterClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.onShutterClicked()
                }
            )

            // Background GPS Tracking Card
            BackgroundTrackingCard(
                enabled = uiState.keepGpsActiveInBackground,
                onToggle = { viewModel.toggleKeepGpsInBackground() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun CameraStatusCard(
    uiState: RemoteUiState,
    onWakeUp: () -> Unit,
    onWakeScreen: () -> Unit,
    onPowerOff: () -> Unit,
    onConnectToggle: () -> Unit
) {
    val ble = uiState.bleState
    val server = uiState.serverState

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Camera Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Readouts: Mode string or Battery Level
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (server.cameraDisplayString != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = server.cameraDisplayString,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    if (ble.batteryPercent != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (ble.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                contentDescription = "Battery Level",
                                modifier = Modifier.size(20.dp),
                                tint = if (ble.batteryPercent > 20) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "${ble.batteryPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connection state badge
            // The camera's Bluetooth answers while the camera itself is switched off
            val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake

            val statusColor = when {
                server.isCameraConnected -> Color(0xFF2E7D32)
                isAsleep -> Color(0xFFE65100)
                ble.connectionStatus == BleConnectionStatus.CONNECTED -> Color(0xFF2E7D32)
                server.isAdvertising || ble.connectionStatus == BleConnectionStatus.SCANNING -> Color(0xFFE65100)
                else -> MaterialTheme.colorScheme.outline
            }

            val statusText = when {
                server.isCameraConnected -> "GPS Remote Connected (10 Hz NMEA)"
                isAsleep -> "Camera Is Off"
                ble.connectionStatus == BleConnectionStatus.CONNECTED -> "Connected"
                server.isAdvertising -> "Broadcasting as Insta360 GPS Remote"
                ble.connectionStatus == BleConnectionStatus.SCANNING -> "Scanning for Camera..."
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
                    .clip(RoundedCornerShape(8.dp))
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

            // What to do when the camera cannot be used yet
            if (!uiState.isConnected || isAsleep) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Instructions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isAsleep) {
                            "Its Bluetooth is connected but the camera is not answering. Press the camera's button to switch it on."
                        } else {
                            "Turn the camera on and keep it nearby. Open360 connects to it directly."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (ble.connectionStatus == BleConnectionStatus.CONNECTED) {
                CameraDetailRows(ble = ble)
            }

            AnimatedVisibility(visible = server.lastError != null || ble.lastError != null) {
                Text(
                    text = server.lastError ?: ble.lastError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onWakeUp,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Wake Up",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Wake Up")
                }

                OutlinedButton(
                    onClick = onConnectToggle,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (uiState.isConnected) "Disconnect" else "Connect")
                }
            }

            if (uiState.isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onWakeScreen,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Wake Screen",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Screen")
                    }

                    OutlinedButton(
                        onClick = onPowerOff,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Power,
                            contentDescription = "Power Off",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Power Off")
                    }
                }
            }
        }
    }
}

/**
 * What the camera has reported about itself, one line each. A line appears once the camera has
 * sent its value.
 */
@Composable
private fun CameraDetailRows(ble: BleCameraState) {
    val details = ble.details
    val rows = ArrayList<StatusLine>()

    // Warnings the camera raises come first
    if (details.temperatureHigh) {
        rows.add(StatusLine("Temperature", "High", isProblem = true))
    }
    if (details.batteryLow) {
        rows.add(StatusLine("Camera Battery", "Low", isProblem = true))
    }

    CameraStatusText.recordingMode(details.recordingState)?.let { mode ->
        rows.add(StatusLine("Recording Mode", mode))
    }

    val storageProblem = CameraStatusText.storageProblem(details.cardState)
    if (storageProblem != null) {
        rows.add(StatusLine("Storage", storageProblem, isProblem = true))
    } else if (details.captureSecondsLeft != null) {
        rows.add(StatusLine("Recording Time Left", CameraStatusText.timeLeft(details.captureSecondsLeft)))
    }

    details.pod?.let { pod ->
        rows.add(StatusLine("Action Pod", CameraStatusText.pod(pod), isProblem = CameraStatusText.podIsLow(pod)))
    }

    if (ble.lastVideoFile != null) {
        val length = ble.lastVideoSeconds?.let { CameraStatusText.clipLength(it) } ?: "Saved"
        rows.add(StatusLine("Last Clip", length, note = ble.lastVideoFile))
    }

    if (rows.isEmpty()) return

    Spacer(modifier = Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { line ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = line.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = line.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (line.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (line.note != null) {
                    Text(
                        text = line.note,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    joinedLateSeconds: Int? = null,
    lastGpsSentTimeMs: Long,
    track: GpxTrackState = GpxTrackState(),
    onToggleSpeedUnit: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GPS Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Satellite fix pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (gps.hasFix) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        contentDescription = "Fix Status",
                        modifier = Modifier.size(16.dp),
                        tint = if (gps.hasFix) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = if (gps.hasFix) "${gps.satellitesInFix} Satellites" else "Acquiring Fix...",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (gps.hasFix) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Speed Gauge / Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                val speedDisplay = if (gps.hasFix) {
                    val speedValue = if (isSpeedMph) gps.speedMph else gps.speedKmh
                    String.format(Locale.US, "%.1f", speedValue)
                } else {
                    "—"
                }
                val unitText = if (isSpeedMph) "mph" else "km/h"

                Text(
                    text = speedDisplay,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { onToggleSpeedUnit() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = unitText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detailed telemetry grid
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(12.dp))

            // Live stream injection badge
            val streamActive = isCameraConnected && gps.hasFix
            val badgeBg = if (streamActive) Color(0xFF2E7D32).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
            val badgeColor = if (streamActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant

            val streamText = when {
                streamActive && isRecording && joinedLateSeconds != null ->
                    "Sending GPS, Joined $joinedLateSeconds s After the Start"
                streamActive && isRecording -> "Sending GPS to Camera (10 Hz)"
                streamActive -> "GPS Ready, Sent While Recording"
                isCameraConnected && !gps.hasFix -> "Waiting for GPS Fix"
                else -> "Telemetry Stream Ready"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
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
                        contentDescription = "Stream Status",
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
                        color = badgeColor
                    )
                }
            }

            if (track.fileName != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "GPX track: ${track.folder}/${track.fileName} (${track.pointCount} points)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (track.lastError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = track.lastError,
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

@Composable
fun CameraControlCard(
    currentMode: CaptureMode,
    isRecording: Boolean,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
    durationSeconds: Long,
    isConnected: Boolean,
    isAsleep: Boolean = false,
    onModeSelected: (CaptureMode) -> Unit,
    onCycleMode: () -> Unit,
    onShutterClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Camera Control",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                if (isConnected) {
                    OutlinedButton(
                        onClick = onCycleMode,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Cycle Mode",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Cycle Mode",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Mode Selection Chips (Video & Photo only)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilterChip(
                    selected = currentMode == CaptureMode.VIDEO,
                    onClick = { onModeSelected(CaptureMode.VIDEO) },
                    label = { Text("Video") },
                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                FilterChip(
                    selected = currentMode == CaptureMode.PHOTO,
                    onClick = { onModeSelected(CaptureMode.PHOTO) },
                    label = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Recording Timer Display (when active)
            AnimatedVisibility(visible = isRecording) {
                val mins = durationSeconds / 60
                val secs = durationSeconds % 60
                val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    val transition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "pulseAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = pulseAlpha))
                    )
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Red
                    )
                }
            }

            // Tactile Shutter Button
            val shutterSize = 84.dp
            val buttonColor = if (isRecording) {
                Color.Red
            } else if (currentMode == CaptureMode.PHOTO) {
                MaterialTheme.colorScheme.primary
            } else {
                Color(0xFFD32F2F)
            }

            Box(
                modifier = Modifier
                    .size(shutterSize)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.15f))
                    .border(3.dp, buttonColor, CircleShape)
                    .clickable(enabled = isConnected && !isAsleep) { onShutterClick() },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Red)
                    )
                } else if (currentMode == CaptureMode.PHOTO) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Take Photo",
                        tint = buttonColor,
                        modifier = Modifier.size(38.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32F2F))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val actionLabel = if (isStopping) {
                "Stopping..."
            } else if (isRecording) {
                "Stop Recording"
            } else if (isStarting) {
                "Starting..."
            } else if (currentMode == CaptureMode.PHOTO) {
                "Take Photo"
            } else {
                "Start Recording"
            }

            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (!isConnected || isAsleep) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )

            if (!isConnected || isAsleep) {
                Text(
                    text = if (isAsleep) "Switch the camera on to control it" else "Connect camera to control",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun BackgroundTrackingCard(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Background GPS Tracking",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Continues GPS injection when display is off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}
