package com.open360.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.network.CameraWifiStatus
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.camera.WakeUpIdDialog
import com.open360.ui.camera.WakeUpSetupDialog
import com.open360.ui.camera.topBarSubtitle
import com.open360.ui.footage.FootageScreen
import com.open360.ui.footage.FootageTab
import com.open360.ui.footage.FootageViewModel
import com.open360.ui.settings.AddSensorDialog
import com.open360.ui.settings.SettingsScreen
import com.open360.ui.theme.statusColors
import java.util.Locale

enum class MainNavTab(val title: String) {
    REMOTE("Remote"),
    FOOTAGE("Footage")
}

/**
 * The app's main screens: the camera remote screen and the footage viewer/importer screen.
 * The set-up for Wake Up and the wake-up ID are dialogs shown over whichever screen is active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Open360App(
    cameraViewModel: CameraViewModel,
    footageViewModel: FootageViewModel,
    onWakeUpClick: () -> Unit = {}
) {
    val uiState by cameraViewModel.uiState.collectAsState()
    val ble = uiState.bleState
    val server = uiState.serverState
    val footageUiState by footageViewModel.uiState.collectAsState()
    var currentTab by rememberSaveable { mutableStateOf(MainNavTab.REMOTE) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Keeps the screen scroll positions and state intact while switching
    val screens = rememberSaveableStateHolder()

    val sensors by cameraViewModel.sportsSensors.state.collectAsState()
    var addSensorOpen by rememberSaveable { mutableStateOf(false) }

    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake

    val dotState = CameraStatusText.statusDotState(
        isRecording = uiState.isRecording,
        isConnected = uiState.isConnected,
        isAsleep = isAsleep,
        isAdvertising = server.isAdvertising,
        bleScanning = ble.connectionStatus == BleConnectionStatus.SCANNING,
        bleConnecting = ble.connectionStatus == BleConnectionStatus.CONNECTING
    )

    val deviceTitle = CameraStatusText.topBarTitle(
        serverConnected = server.isCameraConnected,
        serverSerial = server.cameraSerial,
        bleConnected = ble.connectionStatus == BleConnectionStatus.CONNECTED,
        bleDeviceName = ble.deviceName,
        isAdvertising = server.isAdvertising
    )

    val deviceSubtitle = CameraStatusText.topBarSubtitle(
        isRecording = uiState.isRecording,
        recordingDurationSeconds = uiState.recordingDurationSeconds,
        isFootageTab = currentTab == MainNavTab.FOOTAGE,
        footageTab = footageUiState.currentTab,
        wifiStatus = footageUiState.wifiStatus,
        isAsleep = isAsleep,
        isConnected = uiState.isConnected,
        isAdvertising = server.isAdvertising,
        bleScanning = ble.connectionStatus == BleConnectionStatus.SCANNING,
        bleConnecting = ble.connectionStatus == BleConnectionStatus.CONNECTING
    )

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
            onReconnectSensor = { cameraViewModel.sportsSensors.reconnect(it) },
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
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (dotState) {
                                CameraStatusText.StatusDotState.DISCONNECTED -> {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                                shape = CircleShape
                                            )
                                    )
                                }
                                else -> {
                                    val statusDotColor = when (dotState) {
                                        CameraStatusText.StatusDotState.RECORDING -> MaterialTheme.statusColors.recording
                                        CameraStatusText.StatusDotState.CONNECTED -> MaterialTheme.statusColors.ready
                                        CameraStatusText.StatusDotState.STANDBY -> MaterialTheme.statusColors.waiting
                                        CameraStatusText.StatusDotState.CONNECTING -> MaterialTheme.colorScheme.primary
                                        CameraStatusText.StatusDotState.DISCONNECTED -> Color.Transparent
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(statusDotColor)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = deviceTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = deviceSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isRecording) MaterialTheme.statusColors.recording else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (uiState.isRecording) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    },
                    actions = {
                        if (currentTab == MainNavTab.FOOTAGE) {
                            IconButton(onClick = { footageViewModel.refreshCurrentTab() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh"
                                )
                            }
                        }
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
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
