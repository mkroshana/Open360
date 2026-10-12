package com.open360.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.open360.core.ble.BleConnectionStatus
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.camera.WakeUpIdDialog
import com.open360.ui.camera.WakeUpSetupDialog
import com.open360.ui.settings.SettingsScreen

/**
 * The app's screens: the camera screen and, from its settings button, the settings. The set-up
 * for Wake Up and the wake-up ID are dialogs, shown over whichever screen is up.
 */
@Composable
fun Open360App(
    viewModel: CameraViewModel,
    onWakeUpClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val ble = uiState.bleState
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Keeps the camera screen where it was scrolled to while the settings are up
    val screens = rememberSaveableStateHolder()

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(
            hasWakeUpId = uiState.wakeUpId != null,
            wakeUpRegistration = ble.wakeUpRegistration,
            onSetUpWakeUp = { viewModel.openWakeUpSetup() },
            onWakeUpId = { viewModel.openWakeUpId() },
            onBack = { settingsOpen = false }
        )
    } else {
        screens.SaveableStateProvider("camera") {
            CameraScreen(
                viewModel = viewModel,
                onWakeUpClick = onWakeUpClick,
                onOpenSettings = { settingsOpen = true }
            )
        }
    }

    if (uiState.wakeUpIdOpen) {
        WakeUpIdDialog(
            id = uiState.wakeUpId,
            registration = ble.wakeUpRegistration,
            onSave = { viewModel.saveWakeUpId(it) },
            onClose = { viewModel.closeWakeUpId() }
        )
    }

    if (uiState.wakeUpSetupOpen) {
        WakeUpSetupDialog(
            connectionStatus = ble.connectionStatus,
            cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake,
            registration = ble.wakeUpRegistration,
            onStart = { viewModel.startWakeUpSetup() },
            onClose = { viewModel.closeWakeUpSetup() }
        )
    }
}
