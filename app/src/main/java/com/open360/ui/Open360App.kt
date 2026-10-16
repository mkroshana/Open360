package com.open360.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.open360.core.ble.BleConnectionStatus
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.camera.WakeUpIdDialog
import com.open360.ui.camera.WakeUpSetupDialog
import com.open360.ui.footage.FootageScreen
import com.open360.ui.footage.FootageViewModel
import com.open360.ui.settings.AddSensorDialog
import com.open360.ui.settings.SettingsScreen

enum class MainNavTab(val title: String) {
    REMOTE("Remote"),
    FOOTAGE("Footage")
}

/**
 * The app's main screens: the camera remote screen and the footage viewer/importer screen.
 * The set-up for Wake Up and the wake-up ID are dialogs shown over whichever screen is active.
 */
@Composable
fun Open360App(
    cameraViewModel: CameraViewModel,
    footageViewModel: FootageViewModel,
    onWakeUpClick: () -> Unit = {}
) {
    val uiState by cameraViewModel.uiState.collectAsState()
    val ble = uiState.bleState
    var currentTab by rememberSaveable { mutableStateOf(MainNavTab.REMOTE) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Keeps the screen scroll positions and state intact while switching
    val screens = rememberSaveableStateHolder()

    val sensors by cameraViewModel.sportsSensors.state.collectAsState()
    var addSensorOpen by rememberSaveable { mutableStateOf(false) }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(
            hasWakeUpId = uiState.wakeUpId != null,
            wakeUpRegistration = ble.wakeUpRegistration,
            keepGpsActiveInBackground = uiState.keepGpsActiveInBackground,
            onSetUpWakeUp = { cameraViewModel.openWakeUpSetup() },
            onWakeUpId = { cameraViewModel.openWakeUpId() },
            onToggleBackgroundGps = { cameraViewModel.toggleKeepGpsInBackground() },
            sensors = sensors,
            onAddSensor = {
                addSensorOpen = true
                // Links to saved sensors too, in case Bluetooth permission came after the app started
                cameraViewModel.sportsSensors.start()
                cameraViewModel.sportsSensors.startScan()
            },
            onRemoveSensor = { cameraViewModel.sportsSensors.remove(it) },
            onBack = { settingsOpen = false }
        )
        if (addSensorOpen) {
            AddSensorDialog(
                sensors = sensors,
                onScan = { cameraViewModel.sportsSensors.startScan() },
                onPick = {
                    cameraViewModel.sportsSensors.add(it)
                    cameraViewModel.sportsSensors.stopScan()
                    addSensorOpen = false
                },
                onDismiss = {
                    cameraViewModel.sportsSensors.stopScan()
                    addSensorOpen = false
                }
            )
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Remote"
                            )
                        },
                        label = { Text("Remote") },
                        selected = currentTab == MainNavTab.REMOTE,
                        onClick = { currentTab = MainNavTab.REMOTE }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = "Footage"
                            )
                        },
                        label = { Text("Footage") },
                        selected = currentTab == MainNavTab.FOOTAGE,
                        onClick = { currentTab = MainNavTab.FOOTAGE }
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (currentTab) {
                    MainNavTab.REMOTE -> {
                        screens.SaveableStateProvider("camera") {
                            CameraScreen(
                                viewModel = cameraViewModel,
                                onWakeUpClick = onWakeUpClick,
                                onOpenSettings = { settingsOpen = true }
                            )
                        }
                    }
                    MainNavTab.FOOTAGE -> {
                        screens.SaveableStateProvider("footage") {
                            FootageScreen(
                                viewModel = footageViewModel,
                                bleState = ble
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.wakeUpIdOpen) {
        WakeUpIdDialog(
            id = uiState.wakeUpId,
            registration = ble.wakeUpRegistration,
            onSave = { cameraViewModel.saveWakeUpId(it) },
            onClose = { cameraViewModel.closeWakeUpId() }
        )
    }

    if (uiState.wakeUpSetupOpen) {
        WakeUpSetupDialog(
            connectionStatus = ble.connectionStatus,
            cameraOn = ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake,
            registration = ble.wakeUpRegistration,
            onStart = { cameraViewModel.startWakeUpSetup() },
            onClose = { cameraViewModel.closeWakeUpSetup() }
        )
    }
}
