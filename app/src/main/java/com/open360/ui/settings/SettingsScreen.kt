package com.open360.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.core.ble.WakeUpRegistration
import com.open360.ui.camera.CameraStatusText

/**
 * The settings: what is set up once and then left alone. For now that is Wake Up, with its
 * set-up and the ID behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    hasWakeUpId: Boolean,
    wakeUpRegistration: WakeUpRegistration,
    onSetUpWakeUp: () -> Unit,
    onWakeUpId: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
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
            Text(
                text = "Wake Up",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
            )

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
        }
    }
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
