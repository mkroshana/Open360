package com.open360.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.BuildConfig
import com.open360.core.ble.InstaGpsRemoteServerState
import com.open360.core.ble.WakeUpRegistration
import com.open360.core.connection.ConnectionSettings
import com.open360.core.model.Insta360CameraModel
import com.open360.core.sensors.SportsSensorsState
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.theme.AppThemeMode
import com.open360.ui.theme.BlurBehindDialog
import com.open360.ui.theme.PillSegmentOption
import com.open360.ui.theme.PillSegmentedControl
import java.util.Locale

/**
 * The settings: what is set once and then left alone. Wake Up, with its set-up and the ID behind
 * it, whether GPS keeps going with the display off, and the sports sensors.
 */
@Composable
fun SettingsScreen(
    hasWakeUpId: Boolean,
    wakeUpRegistration: WakeUpRegistration,
    keepGpsActiveInBackground: Boolean,
    onSetUpWakeUp: () -> Unit,
    onWakeUpId: () -> Unit,
    onToggleBackgroundGps: () -> Unit,
    sensors: SportsSensorsState,
    onAddSensor: () -> Unit,
    onReconnectSensor: (String) -> Unit,
    onRemoveSensor: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSpeedMph: Boolean = false,
    onSetSpeedMph: (Boolean) -> Unit = {},
    cameraSerial: String? = null,
    onSaveCameraSerial: (String) -> Unit = {},
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    onSetThemeMode: (AppThemeMode) -> Unit = {},
    onToggleDynamicColor: (Boolean) -> Unit = {},
    connectionSettings: ConnectionSettings = ConnectionSettings(),
    serverState: InstaGpsRemoteServerState = InstaGpsRemoteServerState(),
    resolvedModel: Insta360CameraModel? = null,
    onOpenConnectionMethods: (Int) -> Unit = {},
    onToggleGpsRemoteServer: (Boolean) -> Unit = {}
) {
    var serialDialogOpen by remember { mutableStateOf(false) }
    var aboutDialogOpen by remember { mutableStateOf(false) }
    var privacyDialogOpen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            SettingsSection("Connection & Cameras") {
                SettingsRow(
                    title = "Connection Methods",
                    detail = "Active: ${connectionSettings.preferredTransport.label}",
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
                    onClick = { onOpenConnectionMethods(0) }
                )
                val serverActive = serverState.isAdvertising || serverState.isCameraConnected
                SegmentedListItem(
                    checked = serverActive,
                    onCheckedChange = { onToggleGpsRemoteServer(it) },
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
                    colors = settingsSegmentColors(),
                    supportingContent = {
                        Text(
                            text = when {
                                serverState.isCameraConnected -> "Connected (${serverState.cameraSerial ?: "Camera linked"})"
                                serverState.isAdvertising -> "Advertising CE80 / D0FF service"
                                else -> "Emulate Insta360 GPS Preview Remote"
                            }
                        )
                    },
                    trailingContent = { Switch(checked = serverActive, onCheckedChange = null) }
                ) {
                    Text("GPS Preview Remote Server", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SettingsRow(
                    title = "Supported Cameras (${Insta360CameraModel.entries.size})",
                    detail = resolvedModel?.let { "Active: ${it.displayName}" }
                        ?: "Auto-detect across GO, X, ONE R/RS, Ace & Luna",
                    shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
                    onClick = { onOpenConnectionMethods(1) }
                )
            }

            SettingsSection("Wake Up") {
                val setupStatus = CameraStatusText.wakeUpSetupStatus(hasWakeUpId, wakeUpRegistration)
                SettingsRow(
                    title = "Set Up Wake Up",
                    detail = setupStatus ?: "Lets the Wake Up button switch your camera on when it is off",
                    detailIsProblem = setupStatus != null && CameraStatusText.wakeUpRegistrationIsProblem(wakeUpRegistration),
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
                    onClick = onSetUpWakeUp
                )
                SettingsRow(
                    title = "Wake-Up ID",
                    detail = "The ID your camera knows this app by",
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
                    onClick = onWakeUpId
                )
                SettingsRow(
                    title = "Camera Serial",
                    detail = cameraSerial?.let { "Saved suffix: $it" } ?: "Optional 6-character code from camera name (e.g. 8JY3HF)",
                    shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
                    onClick = { serialDialogOpen = true }
                )
            }

            SettingsSection("GPS") {
                SegmentedListItem(
                    checked = keepGpsActiveInBackground,
                    onCheckedChange = { onToggleBackgroundGps() },
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = settingsSegmentColors(),
                    supportingContent = { Text("Keeps sending GPS to the camera when the screen is off") },
                    trailingContent = { Switch(checked = keepGpsActiveInBackground, onCheckedChange = null) }
                ) {
                    Text("Background GPS Tracking", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                val speedControlWidth = (128f * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)).dp
                SegmentedListItem(
                    onClick = { onSetSpeedMph(!isSpeedMph) },
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    colors = settingsSegmentColors(),
                    supportingContent = { Text("Used for live speed and recorded tracks") },
                    trailingContent = {
                        PillSegmentedControl(
                            options = listOf(PillSegmentOption("km/h"), PillSegmentOption("mph")),
                            selectedIndex = if (isSpeedMph) 1 else 0,
                            onSelect = { index -> onSetSpeedMph(index == 1) },
                            minHeight = 36.dp,
                            textStyle = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(speedControlWidth)
                        )
                    }
                ) {
                    Text("Speed Units", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            SettingsSection("Sports Sensors") {
                SportsSensorsSection(
                    sensors = sensors,
                    onAddSensor = onAddSensor,
                    onReconnectSensor = onReconnectSensor,
                    onRemoveSensor = onRemoveSensor
                )
            }

            SettingsSection("Appearance") {
                val showDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                val appearanceCount = if (showDynamicColor) 2 else 1
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = appearanceCount),
                    colors = settingsSegmentColors(),
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Spacer(modifier = Modifier.height(2.dp))
                            PillSegmentedControl(
                                options = AppThemeMode.entries.map { PillSegmentOption(it.label) },
                                selectedIndex = themeMode.ordinal,
                                onSelect = { index -> onSetThemeMode(AppThemeMode.entries[index]) },
                                minHeight = 38.dp,
                                textStyle = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                ) {
                    Text("Theme", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (showDynamicColor) {
                    SegmentedListItem(
                        checked = dynamicColor,
                        onCheckedChange = { onToggleDynamicColor(!dynamicColor) },
                        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                        colors = settingsSegmentColors(),
                        supportingContent = {
                            Text("Tint the app from your Android wallpaper instead of Open360's editorial palette")
                        },
                        trailingContent = {
                            Switch(checked = dynamicColor, onCheckedChange = null)
                        }
                    ) {
                        Text("Wallpaper Colors", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            SettingsSection("About Open360") {
                SettingsRow(
                    title = "Open360 v${BuildConfig.VERSION_NAME} (${BuildConfig.DISTRIBUTION_CHANNEL.uppercase(Locale.US)})",
                    detail = "Unofficial companion app • Not affiliated with Insta360 / Arashi Vision",
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
                    onClick = { aboutDialogOpen = true }
                )
                SettingsRow(
                    title = "Privacy Policy",
                    detail = "On-device only • No analytics, ads, or cloud tracking",
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
                    onClick = { privacyDialogOpen = true }
                )
                SettingsRow(
                    title = "Open-Source License",
                    detail = "Apache License 2.0 • GitHub Repository",
                    shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
                    onClick = { runCatching { uriHandler.openUri("https://github.com/mkroshana/Open360") } }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
    }

    if (aboutDialogOpen) {
        AlertDialog(
            onDismissRequest = { aboutDialogOpen = false },
            title = { Text("About Open360") },
            text = {
                BlurBehindDialog()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Open360 v${BuildConfig.VERSION_NAME} (${BuildConfig.DISTRIBUTION_CHANNEL.uppercase(Locale.US)})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Open360 is an unofficial, independent open-source companion app for Insta360 cameras. It is not affiliated with, endorsed by, or sponsored by Insta360 or Arashi Vision Inc.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Released under the Apache License 2.0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { aboutDialogOpen = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (privacyDialogOpen) {
        AlertDialog(
            onDismissRequest = { privacyDialogOpen = false },
            title = { Text("Privacy Policy") },
            text = {
                BlurBehindDialog()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Open360 processes GPS telemetry, Bluetooth/Wi-Fi camera connections, sports sensor readings, and downloaded media strictly on your device and camera.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "No analytics, advertising SDKs, telemetry, or cloud uploads are used.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        privacyDialogOpen = false
                        runCatching {
                            uriHandler.openUri("https://github.com/mkroshana/Open360/blob/main/PRIVACY.md")
                        }
                    }
                ) {
                    Text("View Full Policy")
                }
            },
            dismissButton = {
                TextButton(onClick = { privacyDialogOpen = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (serialDialogOpen) {
        var draft by remember(cameraSerial) { mutableStateOf(cameraSerial.orEmpty()) }
        val normalized = draft.trim().uppercase(Locale.US)
        AlertDialog(
            onDismissRequest = { serialDialogOpen = false },
            title = { Text("Camera Serial Suffix") },
            text = {
                BlurBehindDialog()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter the 6-character code at the end of your camera's Bluetooth name (for example 8JY3HF from GO 3S 8JY3HF).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { input ->
                            draft = input.uppercase(Locale.US)
                                .filter { it.isLetterOrDigit() }
                                .take(6)
                        },
                        singleLine = true,
                        label = { Text("Serial Suffix") },
                        placeholder = { Text("e.g. 8JY3HF") },
                        supportingText = { Text("${normalized.length}/6 characters") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalized.isNotEmpty(),
                    onClick = {
                        onSaveCameraSerial(normalized)
                        serialDialogOpen = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    if (cameraSerial != null) {
                        TextButton(
                            onClick = {
                                onSaveCameraSerial("")
                                serialDialogOpen = false
                            }
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { serialDialogOpen = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
internal fun settingsSegmentColors(): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
    selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

/** A titled group of rows, as Material 3 Expressive's segmented list: joined, with small gaps. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    detail: String,
    shapes: ListItemShapes,
    onClick: () -> Unit,
    detailIsProblem: Boolean = false
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsSegmentColors(),
        supportingContent = {
            Text(
                text = detail,
                color = if (detailIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
