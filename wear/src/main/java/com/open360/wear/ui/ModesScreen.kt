package com.open360.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.core.ui.Insta360Icons
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.ui.theme.wearEditorialRadioColors

/** Orders the Photo and Video mode groups so the active group appears first. */
internal fun orderedModeGroups(activeIsPhoto: Boolean): List<Boolean> =
    if (activeIsPhoto) listOf(true, false) else listOf(false, true)

/**
 * The camera's modes. Once a camera that is on has reported its mode, all ten are offered and
 * choosing one switches the camera; before that only Video or Photo, which a camera that is
 * off is switched to when it next answers. Held while a recording or photo capture runs.
 */
@Composable
fun ModesScreen(
    viewModel: CameraViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake
    val cameraMode = if (cameraOn) ble.details.subModes?.let { CameraMode.of(it) } else null
    val locked = uiState.isRecording || ble.isCaptureStarting || ble.isCaptureStopping ||
        ble.isTakingPhoto || uiState.linkMove != null
    val radioColors = wearEditorialRadioColors()

    ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Mode") }
            }
            if (locked) {
                item {
                    Note(
                        text = if (uiState.linkMove != null) "Held while moving link" else "Held while recording"
                    )
                }
            }
            if (cameraMode == null) {
                item {
                    Note(
                        text = "Switch the camera on to see all ten modes"
                    )
                }
                CaptureMode.entries.forEach { choice ->
                    item(key = "capture_${choice.name}") {
                        RadioButton(
                            selected = uiState.currentMode == choice,
                            onSelect = {
                                viewModel.setMode(choice)
                                onDone()
                            },
                            enabled = !locked,
                            colors = radioColors,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            icon = {
                                Icon(
                                    painter = painterResource(Insta360Icons.captureModeDrawableRes(choice)),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = choice.displayName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            } else {
                orderedModeGroups(cameraMode.isPhoto).forEach { photo ->
                    item {
                        ListHeader(
                            modifier = Modifier.transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) { Text(if (photo) "Photo" else "Video") }
                    }
                    CameraMode.group(photo).forEach { mode ->
                        item(key = "mode_${mode.name}") {
                            RadioButton(
                                selected = mode == cameraMode,
                                onSelect = {
                                    viewModel.setCameraMode(mode)
                                    onDone()
                                },
                                enabled = !locked,
                                colors = radioColors,
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                                icon = {
                                    Icon(
                                        painter = painterResource(Insta360Icons.modeDrawableRes(mode)),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = mode.label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 4.dp)
    )
}
