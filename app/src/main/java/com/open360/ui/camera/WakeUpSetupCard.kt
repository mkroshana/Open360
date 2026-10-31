package com.open360.ui.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.core.ble.WakeUpRegistration

/**
 * Leads to the set-up for Wake Up while it is still to be done, for someone who put it off when
 * it was first put forward. It can be dismissed; pressing Wake Up brings the set-up back.
 */
@Composable
fun WakeUpSetupCard(
    registration: WakeUpRegistration,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit
) {
    // What the camera is doing about it right now, or how the last try ended
    val status = when {
        registration == WakeUpRegistration.CONFIRM_ON_CAMERA ||
            CameraStatusText.wakeUpRegistrationIsProblem(registration) -> CameraStatusText.wakeUpRegistration(registration)
        else -> null
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Set Up Wake Up",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = "Let the Wake Up button switch your camera on when it is off. It takes a minute and is done once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (status != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (CameraStatusText.wakeUpRegistrationIsProblem(registration)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onSetUp,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text("Set Up")
                }
            }
        }
    }
}
