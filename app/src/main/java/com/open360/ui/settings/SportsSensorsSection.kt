package com.open360.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.open360.core.sensors.SensorDevice
import com.open360.core.sensors.SensorKind
import com.open360.core.sensors.SensorStatus
import com.open360.core.sensors.SportsSensorsState
import com.open360.ui.theme.BlurBehindDialog

/**
 * The saved sports sensors, each with what it is sending now, and the way to add one. Their
 * readings go into each recording's FIT file, which Insta360 Studio can show.
 */
@Composable
fun SportsSensorsSection(
    sensors: SportsSensorsState,
    onAddSensor: () -> Unit,
    onReconnectSensor: (String) -> Unit,
    onRemoveSensor: (String) -> Unit
) {
    // The saved sensors and Add Sensor, as one segmented list
    val count = sensors.saved.size + 1
    sensors.saved.forEachIndexed { index, status ->
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
            leadingContent = { Icon(iconFor(status.device.kinds), contentDescription = null) },
            supportingContent = {
                Text(
                    text = sensorStatusText(status),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!status.connected && !status.reconnecting) {
                        TextButton(onClick = { onReconnectSensor(status.device.address) }) { Text("Reconnect") }
                    }
                    IconButton(onClick = { onRemoveSensor(status.device.address) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${status.device.name}")
                    }
                }
            }
        ) {
            Text(status.device.name)
        }
    }
    SegmentedListItem(
        onClick = onAddSensor,
        shapes = ListItemDefaults.segmentedShapes(index = count - 1, count = count),
        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
        supportingContent = {
            Text(
                text = "Heart rate, cadence or power, saved with each recording for Insta360 Studio",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Text("Add Sensor")
    }
}

/** Looks for sensors and lists what it finds; tapping one saves it. */
// LinearWavyProgressIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddSensorDialog(
    sensors: SportsSensorsState,
    onScan: () -> Unit,
    onPick: (SensorDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val saved = sensors.saved.map { it.device.address }.toSet()
    val found = sensors.found.filterNot { it.address in saved }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a Sensor") },
        text = {
            BlurBehindDialog()
            Column {
                Text(
                    text = "Switch the sensor on. On a Pixel Watch, open Connected Fitness in the watch's quick settings and tap Connect, so it shares your heart rate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (sensors.scanning) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                if (found.isEmpty()) {
                    Text(
                        text = if (sensors.scanning) "Looking for sensors" else "No sensors found",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                for (device in found) {
                    ListItem(
                        onClick = { onPick(device) },
                        leadingContent = { Icon(iconFor(device.kinds), contentDescription = null) },
                        supportingContent = { Text(kindsText(device.kinds)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    ) {
                        Text(device.name)
                    }
                }
            }
        },
        confirmButton = {
            if (!sensors.scanning) {
                TextButton(onClick = onScan) { Text("Look Again") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun iconFor(kinds: Set<SensorKind>): ImageVector = when {
    SensorKind.HEART_RATE in kinds -> Icons.Default.MonitorHeart
    SensorKind.POWER in kinds -> Icons.Default.Bolt
    else -> Icons.Default.PedalBike
}

private fun kindsText(kinds: Set<SensorKind>): String =
    listOfNotNull(
        "Heart rate".takeIf { SensorKind.HEART_RATE in kinds },
        "Cadence".takeIf { SensorKind.CADENCE in kinds },
        "Power".takeIf { SensorKind.POWER in kinds }
    ).joinToString(", ")

/** "Linked: 92 bpm, 85 rpm", "Linked, no readings yet", "Reconnecting" or "Not linked". */
internal fun sensorStatusText(status: SensorStatus): String {
    if (status.reconnecting) return "Reconnecting. Switch it on and keep it nearby."
    if (!status.connected) return "Not linked. Switch it on and keep it nearby."
    val values = listOfNotNull(
        status.heartRateBpm?.let { "$it bpm" },
        status.cadenceRpm?.let { "$it rpm" },
        status.powerWatts?.let { "$it W" }
    )
    return if (values.isEmpty()) "Linked, no readings yet" else "Linked: ${values.joinToString(", ")}"
}
