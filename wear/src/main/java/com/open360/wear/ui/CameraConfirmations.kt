package com.open360.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.confirmationDialogCurvedText
import com.open360.core.ble.BleConnectionStatus
import com.open360.ui.camera.CameraViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Something the camera did, or failed to do, to confirm on the watch. */
private data class Confirmation(
    val text: String,
    val success: Boolean,
    val isConnectionFailure: Boolean = false
)

// Long enough to read while riding, short enough not to stand in front of the remote
private const val CONFIRMATION_MS = 2500L

// The camera names the saved clip about 0.6 s after it reports the recording stopped
private const val WAIT_FOR_SAVED_CLIP_MS = 2500L

/**
 * Confirms what the camera has done, as the camera reports it rather than when it is asked: a
 * recording starting, a clip saved with its length, a photo taken or refused, and the link lost
 * while recording. Each comes with the watch's confirm or reject haptic.
 */
@Composable
fun CameraConfirmations(viewModel: CameraViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ble = uiState.bleState
    var shown by remember { mutableStateOf<Confirmation?>(null) }
    // Kept after a dialog is dismissed, so text & icon stay steady while it animates away
    var text by remember { mutableStateOf("") }
    var isConnectionFailure by remember { mutableStateOf(true) }
    fun show(confirmation: Confirmation) {
        text = confirmation.text
        isConnectionFailure = confirmation.isConnectionFailure
        shown = confirmation
    }

    // What was last seen, so only changes from now on are confirmed
    var wasRecording by remember { mutableStateOf(uiState.isRecording) }
    var photosTaken by remember { mutableIntStateOf(ble.photosTaken) }
    var photosFailed by remember { mutableIntStateOf(ble.photosFailed) }

    LaunchedEffect(uiState.isRecording) {
        val recording = uiState.isRecording
        if (recording == wasRecording) return@LaunchedEffect
        wasRecording = recording
        if (recording) {
            show(Confirmation("Recording", success = true))
            return@LaunchedEffect
        }
        if (viewModel.uiState.value.bleState.connectionStatus != BleConnectionStatus.CONNECTED) {
            show(Confirmation("Camera Link Lost", success = false, isConnectionFailure = true))
            return@LaunchedEffect
        }
        val saved = withTimeoutOrNull(WAIT_FOR_SAVED_CLIP_MS) {
            viewModel.uiState.first { it.bleState.lastVideoFile != null }
        }
        show(Confirmation(RemoteText.saved(saved?.bleState?.lastVideoSeconds, named = saved != null), success = true))
    }
    LaunchedEffect(ble.photosTaken, ble.photosFailed) {
        if (ble.photosTaken > photosTaken) show(Confirmation("Photo Taken", success = true))
        if (ble.photosFailed > photosFailed) {
            show(Confirmation("Photo Not Taken", success = false, isConnectionFailure = false))
        }
        photosTaken = ble.photosTaken
        photosFailed = ble.photosFailed
    }

    val style = ConfirmationDialogDefaults.curvedTextStyle
    SuccessConfirmationDialog(
        visible = shown?.success == true,
        onDismissRequest = { shown = null },
        curvedText = { confirmationDialogCurvedText(text, style) },
        durationMillis = CONFIRMATION_MS
    )
    FailureConfirmationDialog(
        visible = shown?.success == false,
        onDismissRequest = { shown = null },
        curvedText = { confirmationDialogCurvedText(text, style) },
        durationMillis = CONFIRMATION_MS
    ) {
        if (isConnectionFailure) {
            ConfirmationDialogDefaults.ConnectionFailureIcon()
        } else {
            ConfirmationDialogDefaults.GenericFailureIcon()
        }
    }
}
