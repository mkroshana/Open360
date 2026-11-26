package com.open360.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.open360.wear.ui.theme.WearTabularFigures

private val AmbientIvory = Color(0xFFF0EBE1)
private val AmbientStone = Color(0xFFA39E94)

/**
 * The remote while the watch's screen is dimmed: pure black OLED burn-in-safe layout with
 * tabular figures and warm editorial ivory / stone tones that update at most once a minute.
 */
@Composable
fun AmbientRemote(viewModel: CameraViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    val cameraMode = ble.details.subModes?.let { CameraMode.of(it) }
    val showMode = uiState.isRecording || (ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (uiState.linkMove != null) RemoteText.MOVING_LINK
            else RemoteText.ambientStatus(ble, uiState.isRecording, uiState.recordingDurationSeconds),
            style = MaterialTheme.typography.titleMedium.merge(WearTabularFigures),
            color = AmbientIvory,
            textAlign = TextAlign.Center
        )
        if (showMode) {
            Text(
                text = RemoteText.shownMode(uiState.currentMode, cameraMode)?.label ?: uiState.currentMode.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = AmbientStone
            )
        }
        ble.batteryPercent?.let {
            Text(
                text = "Camera $it%",
                style = MaterialTheme.typography.bodySmall.merge(WearTabularFigures),
                color = AmbientStone
            )
        }
    }
}
