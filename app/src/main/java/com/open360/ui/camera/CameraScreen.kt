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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timelapse
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
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.model.CaptureMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onWakeUpClick: () -> Unit = {},
    onOpenWifiSettingsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val ble = uiState.bleState
    val gps = uiState.gpsState
    val haptic = LocalHapticFeedback.current

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
                            text = ble.deviceName ?: "Insta360 GO 3S",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (ble.connectionStatus == BleConnectionStatus.CONNECTED) {
                        IconButton(onClick = { viewModel.queryBattery() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Battery")
                        }
                    }
                    IconButton(
                        onClick = {
                            if (ble.connectionStatus == BleConnectionStatus.CONNECTED) {
                                viewModel.disconnectBle()
                            } else {
                                viewModel.connectBle()
                            }
                        }
                    ) {
                        val icon = when (ble.connectionStatus) {
                            BleConnectionStatus.CONNECTED -> Icons.Default.BluetoothConnected
                            BleConnectionStatus.SCANNING,
                            BleConnectionStatus.CONNECTING -> Icons.Default.Sync
                            BleConnectionStatus.DISCONNECTED -> Icons.Default.Bluetooth
                        }
                        Icon(icon, contentDescription = "Bluetooth Status")
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
            // Camera Status Card
            CameraStatusCard(
                bleState = ble,
                onWakeUp = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.wakeUpCamera()
                },
                onWakeScreen = { viewModel.wakeScreen() },
                onPowerOff = { viewModel.powerOff() },
                onConnectToggle = {
                    if (ble.connectionStatus == BleConnectionStatus.CONNECTED) {
                        viewModel.disconnectBle()
                    } else {
                        viewModel.connectBle()
                    }
                }
            )

            // Live GPS Telemetry Card
            GpsTelemetryCard(
                gps = gps,
                isSpeedMph = uiState.isSpeedMph,
                isRecording = ble.isRecording,
                lastGpsSentTimeMs = ble.lastGpsSentTimeMs,
                onToggleSpeedUnit = { viewModel.toggleSpeedUnit() }
            )

            // Primary Camera Shutter & Mode Controller
            CameraControlCard(
                currentMode = uiState.currentMode,
                isRecording = ble.isRecording,
                durationSeconds = uiState.recordingDurationSeconds,
                isConnected = ble.connectionStatus == BleConnectionStatus.CONNECTED,
                onModeSelected = { viewModel.setMode(it) },
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
    bleState: com.open360.core.ble.BleCameraState,
    onWakeUp: () -> Unit,
    onWakeScreen: () -> Unit,
    onPowerOff: () -> Unit,
    onConnectToggle: () -> Unit
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
                    text = "Camera Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Battery status readout
                if (bleState.batteryPercent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (bleState.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = "Battery Level",
                            modifier = Modifier.size(20.dp),
                            tint = if (bleState.batteryPercent > 20) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "${bleState.batteryPercent}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connection state badge
            val statusColor = when (bleState.connectionStatus) {
                BleConnectionStatus.CONNECTED -> Color(0xFF2E7D32)
                BleConnectionStatus.CONNECTING,
                BleConnectionStatus.SCANNING -> Color(0xFFE65100)
                BleConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
            }

            val statusText = when (bleState.connectionStatus) {
                BleConnectionStatus.CONNECTED -> "Connected"
                BleConnectionStatus.CONNECTING -> "Connecting..."
                BleConnectionStatus.SCANNING -> "Scanning for Camera..."
                BleConnectionStatus.DISCONNECTED -> "Disconnected"
            }

            val statusIcon = when (bleState.connectionStatus) {
                BleConnectionStatus.CONNECTED -> Icons.Default.CheckCircle
                BleConnectionStatus.CONNECTING,
                BleConnectionStatus.SCANNING -> Icons.Default.Sync
                BleConnectionStatus.DISCONNECTED -> Icons.Default.BluetoothDisabled
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

            AnimatedVisibility(visible = bleState.lastError != null) {
                Text(
                    text = bleState.lastError ?: "",
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
                    Text(text = if (bleState.connectionStatus == BleConnectionStatus.CONNECTED) "Disconnect" else "Scan")
                }
            }

            if (bleState.connectionStatus == BleConnectionStatus.CONNECTED) {
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

@Composable
fun GpsTelemetryCard(
    gps: com.open360.core.gps.GpsTelemetry,
    isSpeedMph: Boolean,
    isRecording: Boolean,
    lastGpsSentTimeMs: Long,
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
                val speedValue = if (isSpeedMph) gps.speedMph else gps.speedKmh
                val unitText = if (isSpeedMph) "mph" else "km/h"

                Text(
                    text = String.format(Locale.US, "%.1f", speedValue),
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
                    value = String.format(Locale.US, "%.1f m", gps.altitudeMeters),
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricItem(
                    icon = Icons.Default.Speed,
                    label = "Accuracy",
                    value = String.format(Locale.US, "±%.1f m", gps.accuracyMeters),
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricItem(
                    icon = Icons.Default.LocationOn,
                    label = "Heading",
                    value = String.format(Locale.US, "%.0f°", gps.bearingDegrees),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live stream injection badge
            val streamActive = isRecording && gps.hasFix
            val badgeBg = if (streamActive) Color(0xFF2E7D32).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
            val badgeColor = if (streamActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant

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
                        text = if (streamActive) "Injecting Telemetry to Video (1 Hz)" else "Telemetry Stream Ready",
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
    durationSeconds: Long,
    isConnected: Boolean,
    onModeSelected: (CaptureMode) -> Unit,
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
            Text(
                text = "Camera Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Mode Selection Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterChip(
                    selected = currentMode == CaptureMode.VIDEO,
                    onClick = { onModeSelected(CaptureMode.VIDEO) },
                    label = { Text("Video") },
                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = currentMode == CaptureMode.PHOTO,
                    onClick = { onModeSelected(CaptureMode.PHOTO) },
                    label = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = currentMode == CaptureMode.TIMELAPSE,
                    onClick = { onModeSelected(CaptureMode.TIMELAPSE) },
                    label = { Text("Timelapse") },
                    leadingIcon = { Icon(Icons.Default.Timelapse, contentDescription = null, modifier = Modifier.size(18.dp)) }
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
                    .clickable(enabled = isConnected) { onShutterClick() },
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

            val actionLabel = if (isRecording) {
                "Stop Recording"
            } else if (currentMode == CaptureMode.PHOTO) {
                "Take Photo"
            } else {
                "Start Recording"
            }

            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (!isConnected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )

            if (!isConnected) {
                Text(
                    text = "Connect camera to control",
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
