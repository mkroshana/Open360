package com.open360.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.ui.camera.CameraViewModel

/**
 * The camera's modes. Once a camera that is on has reported its mode, all ten are offered and
 * choosing one switches the camera; before that only Video or Photo, which a camera that is
 * off is switched to when it next answers. Held while a recording runs, as on the phone.
 */
@Composable
fun ModesScreen(viewModel: CameraViewModel, onDone: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val listState = rememberTransformingLazyColumnState()
    val cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake
    val cameraMode = if (cameraOn) ble.details.subModes?.let { CameraMode.of(it) } else null
    val locked = uiState.isRecording || ble.isCaptureStarting || ble.isCaptureStopping

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text("Mode") } }
            if (locked) {
                item {
                    Text(
                        text = "Held while recording",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (cameraMode == null) {
                CaptureMode.entries.forEach { choice ->
                    item {
                        RadioButton(
                            selected = uiState.currentMode == choice,
                            onSelect = {
                                viewModel.setMode(choice)
                                onDone()
                            },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(choice.displayName) }
                        )
                    }
                }
            } else {
                listOf(false, true).forEach { photo ->
                    item { ListHeader { Text(if (photo) "Photo" else "Video") } }
                    CameraMode.group(photo).forEach { mode ->
                        item {
                            RadioButton(
                                selected = mode == cameraMode,
                                onSelect = {
                                    viewModel.setCameraMode(mode)
                                    onDone()
                                },
                                enabled = !locked,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }
        }
    }
}
