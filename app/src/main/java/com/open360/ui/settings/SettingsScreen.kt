package com.open360.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onReconnectSensor: (String) -> Unit,
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
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Wake Up")
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                        val setupStatus = CameraStatusText.wakeUpSetupStatus(hasWakeUpId, wakeUpRegistration)
                        SettingsRow(
                            title = "Set Up Wake Up",
                            detail = setupStatus ?: "Lets the Wake Up button switch your camera on when it is off",
                            detailIsProblem = setupStatus != null && CameraStatusText.wakeUpRegistrationIsProblem(wakeUpRegistration),
                            onClick = onSetUpWakeUp
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingsRow(
                            title = "Wake-Up ID",
                            detail = "The ID your camera knows this app by",
                            onClick = onWakeUpId
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("GPS")
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                        ListItem(
                            headlineContent = { Text("Background GPS Tracking") },
                            supportingContent = {
                                Text(
                                    text = "Keeps sending GPS to the camera when the screen is off",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = { Switch(checked = keepGpsActiveInBackground, onCheckedChange = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.toggleable(
                                value = keepGpsActiveInBackground,
                                role = Role.Switch,
                                onValueChange = { onToggleBackgroundGps() }
                            )
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Sports Sensors")
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                        SportsSensorsSection(
                            sensors = sensors,
                            onAddSensor = onAddSensor,
                            onReconnectSensor = onReconnectSensor,
                            onRemoveSensor = onRemoveSensor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp)
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
