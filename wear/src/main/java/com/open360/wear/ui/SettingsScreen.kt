package com.open360.wear.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.open360.core.ble.WakeUpRegistration
import com.open360.core.sensors.SensorKind
import com.open360.core.sensors.SensorStatus
import com.open360.core.sensors.SportsSensorManager
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.BuildConfig
import com.open360.wear.SpeedUnit
import com.open360.wear.WatchHeartRate
import com.open360.wear.WatchHeartRateState
import com.open360.wear.WearThemePreferences
import com.open360.wear.ui.theme.WearMetricPill
import com.open360.wear.ui.theme.WearPillSegmentOption
import com.open360.wear.ui.theme.WearPillSegmentedControl
import com.open360.wear.ui.theme.WearTabularFigures
import com.open360.wear.ui.theme.isWearDynamicColorSupported
import com.open360.wear.ui.theme.wearEditorialOutlinedButtonColors
import com.open360.wear.ui.theme.wearEditorialSwitchColors
import com.open360.wear.ui.theme.wearEditorialTonalButtonColors
import com.open360.wear.ui.theme.wearStatusColors

/**
 * The watch's settings: where Wake Up stands with the ID from the phone, whether GPS keeps going
 * with the screen off, speed units, sports sensors, watch-face colors, and about info.
 */
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    heartRate: WatchHeartRate,
    speedUnit: SpeedUnit,
    themePreferences: WearThemePreferences,
    onAddSensor: () -> Unit,
    onOpenSensor: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sensors by viewModel.sportsSensors.state.collectAsStateWithLifecycle()
    val watchHeartRate by heartRate.state.collectAsStateWithLifecycle()
    val inMph by speedUnit.inMph.collectAsStateWithLifecycle()
    val dynamicColor by themePreferences.dynamicColor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val supportsWatchFaceColors = remember(context) { isWearDynamicColorSupported(context) }
    val askForBodySensors = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        heartRate.onPermissionChanged()
    }
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val registration = uiState.bleState.wakeUpRegistration
    var wakeUpInfoOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    val tonalColors = wearEditorialTonalButtonColors()
    val switchColors = wearEditorialSwitchColors()
    val outlinedColors = wearEditorialOutlinedButtonColors()

    ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Settings") }
            }
            item {
                val hasId = uiState.wakeUpId != null
                FilledTonalButton(
                    onClick = { wakeUpInfoOpen = true },
                    colors = tonalColors,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
                    secondaryLabel = {
                        Text(
                            text = wakeUpStatus(hasId, registration),
                            color = if (hasId && CameraStatusText.wakeUpRegistrationIsProblem(registration)) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    label = { Text("Wake Up") }
                )
            }
            item {
                SwitchButton(
                    checked = uiState.keepGpsActiveInBackground,
                    onCheckedChange = { viewModel.toggleKeepGpsInBackground() },
                    colors = switchColors,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    secondaryLabel = { Text("With the screen off") },
                    label = { Text("Background GPS") }
                )
            }
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Speed Units") }
            }
            item {
                WearPillSegmentedControl(
                    options = listOf(
                        WearPillSegmentOption("km/h"),
                        WearPillSegmentOption("mph")
                    ),
                    selectedIndex = if (inMph) 1 else 0,
                    onSelect = { index -> speedUnit.set(index == 1) },
                    minHeight = 42.dp,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .transformedHeight(this, transformationSpec)
                )
            }
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Sports Sensors") }
            }
            item {
                Note(
                    text = "Saved in each recording's FIT file"
                )
            }
            if (watchHeartRate.available) {
                item {
                    SwitchButton(
                        checked = watchHeartRate.enabled && watchHeartRate.permitted,
                        onCheckedChange = { on ->
                            heartRate.setEnabled(on)
                            if (on && !watchHeartRate.permitted) askForBodySensors.launch(Manifest.permission.BODY_SENSORS)
                        },
                        colors = switchColors,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                        secondaryLabel = {
                            Text(
                                text = watchHeartRateStatus(watchHeartRate),
                                style = LocalTextStyle.current.merge(WearTabularFigures)
                            )
                        },
                        label = { Text("Watch Heart Rate") }
                    )
                }
            }
            sensors.saved.forEach { status ->
                item(key = "sensor_${status.device.address}") {
                    FilledTonalButton(
                        onClick = { onOpenSensor(status.device.address) },
                        colors = tonalColors,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(iconFor(status.device.kinds), contentDescription = null) },
                        secondaryLabel = {
                            Text(
                                text = sensorStatus(status),
                                style = LocalTextStyle.current.merge(WearTabularFigures),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        label = {
                            Text(
                                text = status.device.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onAddSensor,
                    colors = outlinedColors,
                    border = ButtonDefaults.outlinedButtonBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = { Text("Add Sensor") }
                )
            }
            if (supportsWatchFaceColors) {
                item {
                    ListHeader(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) { Text("Appearance") }
                }
                item {
                    SwitchButton(
                        checked = dynamicColor,
                        onCheckedChange = { themePreferences.setDynamicColor(it) },
                        colors = switchColors,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        secondaryLabel = { Text("Tint from watch face") },
                        label = { Text("Watch Face Colors") }
                    )
                }
            }
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("About") }
            }
            item {
                FilledTonalButton(
                    onClick = { aboutOpen = true },
                    colors = tonalColors,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    secondaryLabel = {
                        Text(
                            text = "Unofficial • v${BuildConfig.VERSION_NAME}",
                            style = LocalTextStyle.current.merge(WearTabularFigures)
                        )
                    },
                    label = { Text("About Open360") }
                )
            }
        }
    }

    AlertDialog(
        visible = wakeUpInfoOpen,
        onDismissRequest = { wakeUpInfoOpen = false },
        title = { Text("Wake Up") },
        text = {
            Text(
                if (uiState.wakeUpId != null) "Connect switches the camera on when it is off. The watch uses the wake-up ID from Open360 on your phone."
                else "Set up Wake Up in Open360 on your phone. The watch takes the same wake-up ID, and Connect then switches the camera on."
            )
        },
        edgeButton = { AlertDialogDefaults.EdgeButton(onClick = { wakeUpInfoOpen = false }) }
    )

    AlertDialog(
        visible = aboutOpen,
        onDismissRequest = { aboutOpen = false },
        title = { Text("About Open360") },
        text = {
            Text(
                "Unofficial open-source companion app (v${BuildConfig.VERSION_NAME}). Not affiliated with or endorsed by Insta360 or Arashi Vision Inc.\n\nAll camera control, GPS, and sensor data stay on your devices and camera; nothing is uploaded."
            )
        },
        edgeButton = { AlertDialogDefaults.EdgeButton(onClick = { aboutOpen = false }) }
    )
}

/** Looks for heart rate, cadence and power sensors while open; tapping one saves it. */
@Composable
fun AddSensorScreen(
    sensors: SportsSensorManager,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by sensors.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    DisposableEffect(Unit) {
        sensors.start()
        sensors.startScan()
        onDispose { sensors.stopScan() }
    }
    val saved = state.saved.map { it.device.address }.toSet()
    val found = state.found.filterNot { it.address in saved }
    val tonalColors = wearEditorialTonalButtonColors()
    val outlinedColors = wearEditorialOutlinedButtonColors()

    ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Add a Sensor") }
            }
            item {
                Note(
                    text = "Switch the sensor on and keep it nearby."
                )
            }
            found.forEach { device ->
                item(key = "found_${device.address}") {
                    FilledTonalButton(
                        onClick = {
                            sensors.add(device)
                            onDone()
                        },
                        colors = tonalColors,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(iconFor(device.kinds), contentDescription = null) },
                        secondaryLabel = {
                            Text(
                                text = kindsText(device.kinds),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        label = {
                            Text(
                                text = device.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
            if (state.scanning) {
                item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
            } else {
                if (found.isEmpty()) {
                    item {
                        Note(
                            text = "No sensors found"
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { sensors.startScan() },
                        colors = outlinedColors,
                        border = ButtonDefaults.outlinedButtonBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        label = { Text("Look Again") }
                    )
                }
            }
        }
    }
}

/** One saved sensor: what it is sending, Reconnect when it is not linked, and Remove. */
@Composable
fun SensorScreen(
    sensors: SportsSensorManager,
    address: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by sensors.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val status = state.saved.firstOrNull { it.device.address == address }
    val tonalColors = wearEditorialTonalButtonColors()
    val outlinedColors = wearEditorialOutlinedButtonColors()

    ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            if (status == null) {
                item {
                    ListHeader(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) { Text("Sensor Removed") }
                }
                return@TransformingLazyColumn
            }
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text(status.device.name) }
            }
            item {
                val dotColor = when {
                    status.connected -> MaterialTheme.wearStatusColors.ready
                    status.reconnecting -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                WearMetricPill(
                    text = sensorStatus(status),
                    dotColor = dotColor,
                    textStyle = MaterialTheme.typography.bodySmall,
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                )
            }
            if (!status.connected && !status.reconnecting) {
                item {
                    FilledTonalButton(
                        onClick = { sensors.reconnect(address) },
                        colors = tonalColors,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        label = { Text("Reconnect") }
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        sensors.remove(address)
                        onDone()
                    },
                    colors = outlinedColors,
                    border = ButtonDefaults.outlinedButtonBorder(
                        enabled = true,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    label = { Text("Remove") }
                )
            }
        }
    }
}

/** Where reading the watch's own heart rate stands, for its switch. */
private fun watchHeartRateStatus(state: WatchHeartRateState): String = when {
    !state.permitted -> "Needs body sensors"
    !state.enabled -> "Off"
    state.bpm != null -> "${state.bpm} bpm"
    state.reading -> "Reading"
    else -> "While the camera is on"
}

/** Where Wake Up stands, in a few words: the phone's ID and what the camera said about it. */
private fun wakeUpStatus(hasId: Boolean, registration: WakeUpRegistration): String =
    if (!hasId) "Set Up on Your Phone"
    else CameraStatusText.wakeUpSetupStatus(true, registration) ?: "ID From Your Phone"

private fun iconFor(kinds: Set<SensorKind>): ImageVector = when {
    SensorKind.HEART_RATE in kinds -> Icons.Filled.MonitorHeart
    SensorKind.POWER in kinds -> Icons.Filled.Bolt
    else -> Icons.Filled.PedalBike
}

private fun kindsText(kinds: Set<SensorKind>): String =
    listOfNotNull(
        "Heart rate".takeIf { SensorKind.HEART_RATE in kinds },
        "Cadence".takeIf { SensorKind.CADENCE in kinds },
        "Power".takeIf { SensorKind.POWER in kinds }
    ).joinToString(", ")

/** "Linked: 92 bpm, 85 rpm", "Linked, no readings yet", "Reconnecting" or "Not linked", as on the phone. */
private fun sensorStatus(status: SensorStatus): String {
    if (status.reconnecting) return "Reconnecting"
    if (!status.connected) return "Not linked"
    val values = listOfNotNull(
        status.heartRateBpm?.let { "$it bpm" },
        status.cadenceRpm?.let { "$it rpm" },
        status.powerWatts?.let { "$it W" }
    )
    return if (values.isEmpty()) "Linked, no readings yet" else "Linked: ${values.joinToString(", ")}"
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 2.dp)
    )
}
