package com.open360.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.core.ble.WakeUpRegistration
import com.open360.core.sensors.SportsSensorsState
import com.open360.ui.camera.CameraStatusText

/**
 * The settings: what is set once and then left alone. Wake Up, with its set-up and the ID behind
 * it, whether GPS keeps going with the display off, and the sports sensors.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onRemoveSensor: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        ) {
            SectionHeader("Wake Up")

            // Where the set-up stands when that is known, and otherwise what it is for
            val setupStatus = CameraStatusText.wakeUpSetupStatus(hasWakeUpId, wakeUpRegistration)
            SettingsRow(
                title = "Set Up Wake Up",
                detail = setupStatus ?: "Lets the Wake Up button switch your camera on when it is off",
                detailIsProblem = setupStatus != null && CameraStatusText.wakeUpRegistrationIsProblem(wakeUpRegistration),
                onClick = onSetUpWakeUp
            )
            SettingsRow(
                title = "Wake-Up ID",
                detail = "The ID your camera knows this app by",
                onClick = onWakeUpId
            )

            SectionHeader("GPS")
            ListItem(
                headlineContent = { Text("Background GPS Tracking") },
                supportingContent = {
                    Text(
                        text = "Keeps sending GPS to the camera when the screen is off",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                // The whole row is the switch, so the switch itself takes no taps of its own
                trailingContent = { Switch(checked = keepGpsActiveInBackground, onCheckedChange = null) },
                modifier = Modifier.toggleable(
                    value = keepGpsActiveInBackground,
                    role = Role.Switch,
                    onValueChange = { onToggleBackgroundGps() }
                )
            )

            SectionHeader("Sports Sensors")
            SportsSensorsSection(sensors = sensors, onAddSensor = onAddSensor, onRemoveSensor = onRemoveSensor)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    detailIsProblem: Boolean = false
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = detail,
                color = if (detailIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
