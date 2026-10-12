package com.open360.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import java.util.UUID

/**
 * Shows the wake-up ID, the one a switched-off camera accepts a wake-up from, and lets it be
 * copied to another device or replaced.
 */
@Composable
fun WakeUpIdCard(
    id: String?,
    onSave: (String) -> Boolean
) {
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Wake-Up ID",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "A camera that is off only wakes for an ID it has authorized.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = id ?: "Not Set",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (id != null) FontFamily.Monospace else FontFamily.Default,
                color = if (id != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { id?.let { clipboard.setText(AnnotatedString(it)) } },
                    enabled = id != null
                ) {
                    Text("Copy")
                }
                OutlinedButton(onClick = { editing = true }) {
                    Text(if (id == null) "Set" else "Change")
                }
            }
        }
    }

    if (editing) {
        WakeUpIdDialog(
            current = id,
            onDismiss = { editing = false },
            onSave = { entered ->
                if (onSave(entered)) editing = false
            }
        )
    }
}

@Composable
private fun WakeUpIdDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current ?: "") }
    val entered = text.trim()
    val valid = Go2BleProtocol.isValidAuthorizationId(entered)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wake-Up ID") },
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
                                "Paste the ID from a device the camera already wakes for, or make a new one and register it."
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
