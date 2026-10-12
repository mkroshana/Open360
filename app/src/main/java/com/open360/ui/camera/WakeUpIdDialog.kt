package com.open360.ui.camera

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.ble.WakeUpRegistration
import java.util.UUID

/**
 * Shows the wake-up ID, the one the camera knows this app by, with what the camera says about
 * it, and lets it be copied to another phone or replaced. The set-up needs none of this, so it
 * is kept off the main screen.
 */
@Composable
fun WakeUpIdDialog(
    id: String?,
    registration: WakeUpRegistration,
    onSave: (String) -> Boolean,
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }

    if (editing) {
        WakeUpIdEditDialog(
            current = id,
            onDismiss = { editing = false },
            onSave = { entered ->
                if (onSave(entered)) editing = false
            }
        )
        return
    }

    val registrationText = if (id != null) CameraStatusText.wakeUpRegistration(registration) else null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Wake-Up ID") },
        text = {
            Column {
                Text(
                    text = "The camera knows this app by this ID, and when it is off it only wakes for an ID it has allowed. The set-up makes one for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = id ?: "None Yet",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (id != null) FontFamily.Monospace else FontFamily.Default,
                    color = if (id != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (registrationText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = registrationText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (CameraStatusText.wakeUpRegistrationIsProblem(registration)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
        dismissButton = {
            Row {
                if (id != null) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(id)) }) { Text("Copy") }
                }
                TextButton(onClick = { editing = true }) { Text(if (id == null) "Enter an ID" else "Change") }
            }
        }
    )
}

@Composable
private fun WakeUpIdEditDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current ?: "") }
    val entered = text.trim()
    val valid = Go2BleProtocol.isValidAuthorizationId(entered)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Enter a Wake-Up ID" else "Change the Wake-Up ID") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    isError = entered.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            if (entered.isEmpty() || valid) {
                                "Paste the ID from a phone the camera already wakes for, or make a new one. The camera asks to allow a new one."
                            } else {
                                "An ID is 36 characters in five groups, like 0f8fad5b-d9cb-469f-a165-70867728950e."
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { text = UUID.randomUUID().toString() }) {
                    Text("Make a New ID")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(entered) }, enabled = valid) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
