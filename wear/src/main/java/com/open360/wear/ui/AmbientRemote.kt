package com.open360.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.camera.RemoteUiState
import com.open360.wear.ui.theme.WearTabularFigures
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val AmbientIvory = Color(0xFFF0EBE1)
private val AmbientStone = Color(0xFFA39E94)

private data class AmbientDisplayState(
    val status: String,
    val modeLabel: String?,
    val batteryText: String?
)

private fun toAmbientDisplayState(uiState: RemoteUiState): AmbientDisplayState {
    val ble = uiState.bleState
    val cameraMode = ble.details.subModes?.let { CameraMode.of(it) }
    val showMode = uiState.isRecording ||
        (ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake)
    return AmbientDisplayState(
        status = if (uiState.linkMove != null) {
            RemoteText.MOVING_LINK
        } else {
            RemoteText.ambientStatus(ble, uiState.isRecording, uiState.recordingDurationSeconds)
        },
        modeLabel = if (showMode) {
            RemoteText.shownMode(uiState.currentMode, cameraMode)?.label ?: uiState.currentMode.displayName
        } else {
            null
        },
        batteryText = ble.batteryPercent?.let { "Camera $it%" }
    )
}

/**
 * The remote while the watch's screen is dimmed: pure black OLED burn-in-safe layout with
 * tabular figures and warm editorial ivory / stone tones that update at most once a minute.
 */
@Composable
fun AmbientRemote(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    burnInProtection: Boolean = false
) {
    val initialState = remember(viewModel) { toAmbientDisplayState(viewModel.uiState.value) }
    val displayFlow = remember(viewModel) {
        viewModel.uiState
            .map(::toAmbientDisplayState)
            .distinctUntilChanged()
    }
    val display by displayFlow.collectAsStateWithLifecycle(initialValue = initialState)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(if (burnInProtection) 16.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = display.status,
            style = MaterialTheme.typography.titleMedium.merge(WearTabularFigures),
            color = AmbientIvory,
            textAlign = TextAlign.Center
        )
        display.modeLabel?.let { modeLabel ->
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = AmbientStone
            )
        }
        display.batteryText?.let { batteryText ->
            Text(
                text = batteryText,
                style = MaterialTheme.typography.bodySmall.merge(WearTabularFigures),
                color = AmbientStone
            )
        }
    }
}
