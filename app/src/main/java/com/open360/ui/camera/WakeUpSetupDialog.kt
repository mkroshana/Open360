package com.open360.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.WakeUpRegistration
import com.open360.ui.theme.BlurBehindDialog

private enum class StepState { PENDING, CURRENT, DONE }

/**
 * Takes the user through getting the camera to allow Open360 to wake it: switch the camera on,
 * allow the new app on the camera's own screen, ready. Once started the steps move on by
 * themselves as the camera reports. Closing it does not take back a question the camera is
 * already asking.
 */
@Composable
fun WakeUpSetupDialog(
    connectionStatus: BleConnectionStatus,
    cameraOn: Boolean,
    registration: WakeUpRegistration,
    onStart: () -> Unit,
    onClose: () -> Unit
) {
    var started by rememberSaveable { mutableStateOf(false) }
    val step = WakeUpSetup.step(cameraOn, registration)
    val ready = step == WakeUpSetupStep.READY
    val failed = started && WakeUpSetup.failed(cameraOn, registration)

    // Once started, the camera is asked as soon as it is on and looked for again if the link drops
    LaunchedEffect(started, cameraOn, connectionStatus, registration) {
        if (started && !ready &&
            (WakeUpSetup.shouldAsk(cameraOn, registration) || connectionStatus == BleConnectionStatus.DISCONNECTED)
        ) {
            onStart()
        }
    }

    fun stateOf(of: WakeUpSetupStep): StepState = when {
        ready -> StepState.DONE
        !started -> StepState.PENDING
        of.ordinal < step.ordinal -> StepState.DONE
        of == step -> StepState.CURRENT
        else -> StepState.PENDING
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Set Up Wake Up") },
        text = {
            BlurBehindDialog()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Open360 can switch the camera on from the phone once the camera has allowed this app. This is done once.",
                    style = MaterialTheme.typography.bodyMedium
                )
                SetupStepRow(
                    number = 1,
                    title = "Switch the Camera On",
                    detail = "Press the camera's button and keep it near the phone.",
                    state = stateOf(WakeUpSetupStep.SWITCH_CAMERA_ON),
                    status = when (connectionStatus) {
                        BleConnectionStatus.CONNECTED -> "Found, Waiting for It to Switch On"
                        BleConnectionStatus.CONNECTING -> "Connecting..."
                        else -> "Looking for the Camera..."
                    }
                )
                SetupStepRow(
                    number = 2,
                    title = "Allow Open360 on the Camera",
                    detail = "The camera asks whether to connect to a new app. Allow it on the camera's screen.",
                    state = stateOf(WakeUpSetupStep.ALLOW_ON_CAMERA),
                    status = when {
                        failed -> CameraStatusText.wakeUpRegistration(registration).orEmpty()
                        registration == WakeUpRegistration.CONFIRM_ON_CAMERA -> "Waiting for You to Allow It"
                        else -> "Asking the Camera..."
                    },
                    statusIsProblem = failed
                )
                SetupStepRow(
                    number = 3,
                    title = "Wake Up Is Ready",
                    detail = "The Wake Up button switches this camera on when it is off.",
                    state = stateOf(WakeUpSetupStep.READY),
                    status = ""
                )
            }
        },
        confirmButton = {
            when {
                ready -> TextButton(onClick = onClose) { Text("Done") }
                !started -> TextButton(
                    onClick = {
                        started = true
                        onStart()
                    }
                ) { Text("Start") }
                failed -> TextButton(onClick = onStart) { Text("Try Again") }
            }
        },
        dismissButton = {
            if (!ready) {
                TextButton(onClick = onClose) { Text(if (started) "Close" else "Not Now") }
            }
        }
    )
}

/**
 * One step: its number, or a tick once it is done, with its title and what to do. The step the
 * set-up is at also says what it is waiting for.
 */
// LoadingIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupStepRow(
    number: Int,
    title: String,
    detail: String,
    state: StepState,
    status: String,
    statusIsProblem: Boolean = false
) {
    val reached = state != StepState.PENDING
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (state == StepState.DONE) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (state == StepState.CURRENT) FontWeight.SemiBold else FontWeight.Medium,
                color = if (reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state == StepState.CURRENT && status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!statusIsProblem) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (statusIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
