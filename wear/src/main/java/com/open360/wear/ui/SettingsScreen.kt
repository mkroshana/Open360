package com.open360.wear.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.open360.core.sensors.SensorKind
import com.open360.core.sensors.SensorStatus
import com.open360.core.sensors.SportsSensorManager
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.SpeedUnit
import com.open360.wear.WatchHeartRate
import com.open360.wear.WatchHeartRateState

/**
 * The watch's settings: where Wake Up stands with the ID from the phone, whether GPS keeps going
 * with the screen off, and the Bluetooth sports sensors whose readings go into each FIT file.
 */
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    heartRate: WatchHeartRate,
    speedUnit: SpeedUnit,
    onAddSensor: () -> Unit,
    onOpenSensor: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sensors by viewModel.sportsSensors.state.collectAsStateWithLifecycle()
    val watchHeartRate by heartRate.state.collectAsStateWithLifecycle()
    val inMph by speedUnit.inMph.collectAsStateWithLifecycle()
    val askForBodySensors = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        heartRate.onPermissionChanged()
    }
    val listState = rememberTransformingLazyColumnState()
    // Items narrow and shrink as they reach the round screen's top and bottom edges
    val transformationSpec = rememberTransformationSpec()
    val registration = uiState.bleState.wakeUpRegistration

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Settings") }
            }
            item {
                val hasId = uiState.wakeUpId != null
                val status = CameraStatusText.wakeUpSetupStatus(hasId, registration)
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(text = "Wake Up", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = when {
                            !hasId -> "Not set up. Set up Wake Up in Open360 on your phone, and the watch takes the same ID."
                            status != null -> "$status. ID from your phone."
                            else -> "ID from your phone."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasId && CameraStatusText.wakeUpRegistrationIsProblem(registration)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            item {
                SwitchButton(
                    checked = uiState.keepGpsActiveInBackground,
                    onCheckedChange = { viewModel.toggleKeepGpsInBackground() },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    secondaryLabel = { Text("Keeps sending GPS with the screen off") },
                    label = { Text("Background GPS") }
                )
            }
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Speed Units") }
            }
            listOf(false to "km/h", true to "mph").forEach { (mph, label) ->
                item {
                    RadioButton(
                        selected = inMph == mph,
                        onSelect = { speedUnit.set(mph) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(label) }
                    )
                }
            }
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Sports Sensors") }
            }
            if (watchHeartRate.available) {
                item {
                    SwitchButton(
                        checked = watchHeartRate.enabled && watchHeartRate.permitted,
                        onCheckedChange = { on ->
                            heartRate.setEnabled(on)
                            if (on && !watchHeartRate.permitted) askForBodySensors.launch(Manifest.permission.BODY_SENSORS)
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                        secondaryLabel = { Text(watchHeartRateStatus(watchHeartRate)) },
                        label = { Text("Watch Heart Rate") }
                    )
                }
            }
            sensors.saved.forEach { status ->
                item {
                    FilledTonalButton(
                        onClick = { onOpenSensor(status.device.address) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(iconFor(status.device.kinds), contentDescription = null) },
                        secondaryLabel = { Text(sensorStatus(status)) },
                        label = { Text(status.device.name) }
                    )
                }
            }
            item {
                Button(
                    onClick = onAddSensor,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = { Text("Add Sensor") }
                )
            }
        }
    }
}

/** Looks for heart rate, cadence and power sensors while open; tapping one saves it. */
@Composable
fun AddSensorScreen(sensors: SportsSensorManager, onDone: () -> Unit) {
    val state by sensors.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    // Items narrow and shrink as they reach the round screen's top and bottom edges
    val transformationSpec = rememberTransformationSpec()
    DisposableEffect(Unit) {
        sensors.start()
        sensors.startScan()
        onDispose { sensors.stopScan() }
    }
    val saved = state.saved.map { it.device.address }.toSet()
    val found = state.found.filterNot { it.address in saved }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Add a Sensor") }
            }
            item {
                Text(
                    text = "Switch the sensor on and keep it nearby.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            found.forEach { device ->
                item {
                    FilledTonalButton(
                        onClick = {
                            sensors.add(device)
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(iconFor(device.kinds), contentDescription = null) },
                        secondaryLabel = { Text(kindsText(device.kinds)) },
                        label = { Text(device.name) }
                    )
                }
            }
            if (state.scanning) {
                item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
            } else {
                if (found.isEmpty()) {
                    item {
                        Text(
                            text = "No sensors found",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { sensors.startScan() },
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
fun SensorScreen(sensors: SportsSensorManager, address: String, onDone: () -> Unit) {
    val state by sensors.state.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    // Items narrow and shrink as they reach the round screen's top and bottom edges
    val transformationSpec = rememberTransformationSpec()
    val status = state.saved.firstOrNull { it.device.address == address }

    ScreenScaffold(scrollState = listState) { contentPadding ->
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
                Text(
                    text = sensorStatus(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (!status.connected && !status.reconnecting) {
                item {
                    Button(
                        onClick = { sensors.reconnect(address) },
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
    !state.permitted -> "Allow body sensors to save it with each recording"
    !state.enabled -> "Off"
    state.bpm != null -> "${state.bpm} bpm, saved with each recording"
    state.reading -> "Reading, no heart rate yet"
    else -> "Read while the camera is on, saved with each recording"
}

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
