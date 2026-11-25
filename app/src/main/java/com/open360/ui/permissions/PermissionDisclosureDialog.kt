package com.open360.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.ui.theme.BlurBehindDialog

/**
 * Prominent pre-permission disclosure explaining why Open360 requests location (including
 * background GPS tracking while connected with the screen off), Bluetooth / Nearby Wi-Fi, and
 * notifications before Android's runtime permission dialogs appear.
 */
@Composable
fun PermissionDisclosureDialog(
    onContinue: () -> Unit,
    onNotNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Permissions & Privacy") },
        text = {
            BlurBehindDialog()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Open360 needs the following permissions to link with your Insta360 camera and record GPS telemetry:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DisclosureRow(
                    icon = Icons.Default.LocationOn,
                    title = "Location & Background GPS Tracking",
                    description = "Reads GPS coordinates, speed, and altitude to embed into camera recordings and save local GPX/FIT tracks. When Background GPS Tracking is enabled, a foreground location service keeps recording while connected to your camera even with the screen off."
                )

                DisclosureRow(
                    icon = Icons.Default.Bluetooth,
                    title = "Bluetooth & Nearby Wi-Fi",
                    description = "Finds and wakes your Insta360 camera, connects Bluetooth sports sensors, and joins camera Wi-Fi networks for Live View and clip downloads."
                )

                DisclosureRow(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    description = "Shows the active foreground status notification when Background GPS Tracking is running."
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "All data stays on your device and camera; nothing is uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text("Not Now")
            }
        }
    )
}

@Composable
private fun DisclosureRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
