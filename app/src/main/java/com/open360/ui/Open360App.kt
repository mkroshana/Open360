package com.open360.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.ble.ClassicSppCameraClient
import com.open360.core.connection.ConnectionPreferences
import com.open360.core.connection.ConnectionSettings
import com.open360.core.connection.DirectCameraControlBridge
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.CaptureMode
import com.open360.core.usb.CameraUsbClient
import com.open360.ui.camera.CameraButton
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraStatusText
import com.open360.ui.camera.CameraStatusTopBar
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.camera.ConnectionMethodsSheet
import com.open360.ui.camera.UnifiedRecordingBar
import com.open360.ui.camera.WakeUpIdDialog
import com.open360.ui.camera.WakeUpSetupDialog
import com.open360.ui.camera.topBarSubtitle
import com.open360.ui.footage.FootageScreen
import com.open360.ui.footage.FootageViewModel
import com.open360.ui.liveview.LiveViewFullscreenDialog
import com.open360.ui.liveview.LiveViewScreen
import com.open360.ui.liveview.LiveViewStatus
import com.open360.ui.liveview.LiveViewViewModel
import com.open360.ui.permissions.PermissionDisclosureDialog
import com.open360.ui.settings.AddSensorDialog
import com.open360.ui.settings.SettingsScreen
import com.open360.ui.theme.AppThemeMode

enum class MainNavTab(val title: String) {
    REMOTE("Remote"),
    LIVE_VIEW("Live View"),
    FOOTAGE("Footage"),
    SETTINGS("Settings")
}

/**
 * The app's main screens: the camera remote, live view, footage viewer/importer, and settings tabs.
 * The set-up for Wake Up and the wake-up ID are dialogs shown over whichever screen is active.
 */
@Composable
fun Open360App(
    cameraViewModel: CameraViewModel,
    footageViewModel: FootageViewModel,
    liveViewViewModel: LiveViewViewModel,
    connectionPreferences: ConnectionPreferences? = null,
    directBridge: DirectCameraControlBridge? = null,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    onSetThemeMode: (AppThemeMode) -> Unit = {},
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onEnsureCameraPermissions: ((() -> Unit) -> Unit) = { it() },
    showPermissionDisclosure: Boolean = false,
    onAcceptPermissionDisclosure: () -> Unit = {},
    onDismissPermissionDisclosure: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by cameraViewModel.uiState.collectAsState()
    val ble = uiState.bleState
    val server = uiState.serverState
    val footageUiState by footageViewModel.uiState.collectAsState()
    val liveViewState by liveViewViewModel.uiState.collectAsState()
    val connectionSettings by (connectionPreferences?.settings?.collectAsState() ?: remember { mutableStateOf(ConnectionSettings()) })
    val directState by (directBridge?.state?.collectAsState()
        ?: remember { mutableStateOf(com.open360.core.connection.DirectConnectionState()) })
    var currentTab by rememberSaveable { mutableStateOf(MainNavTab.REMOTE) }
    var connectionSheetOpen by rememberSaveable { mutableStateOf(false) }
    var connectionSheetTab by rememberSaveable { mutableIntStateOf(0) }
    var directCameraMode by rememberSaveable { mutableStateOf(CameraMode.VIDEO) }
    var attachedUsbDevices by remember { mutableStateOf<List<CameraUsbClient.AttachedUsbDevice>>(emptyList()) }
    var bondedSppDevices by remember { mutableStateOf<List<ClassicSppCameraClient.BondedDeviceInfo>>(emptyList()) }
    // Keeps the screen scroll positions and state intact while switching
    val screens = rememberSaveableStateHolder()

    fun refreshHardwareInventories() {
        attachedUsbDevices = CameraUsbClient.inspectAttachedDevices(context)
        bondedSppDevices = ClassicSppCameraClient.listBondedDevices(context)
    }

    LaunchedEffect(connectionSheetOpen) {
        if (connectionSheetOpen) refreshHardwareInventories()
    }

    LaunchedEffect(currentTab) {
        if (currentTab != MainNavTab.LIVE_VIEW) {
            liveViewViewModel.stopLiveView()
        }
    }

    val sensors by cameraViewModel.sportsSensors.state.collectAsState()
    var addSensorOpen by rememberSaveable { mutableStateOf(false) }
    var savedSerialSuffix by remember(ble.deviceName, currentTab) {
        mutableStateOf(cameraViewModel.bleManager.getSavedSerialSuffix().ifBlank { null })
    }

    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake
    val useDirectControl = directState.isConnected && (!uiState.isConnected || isAsleep)
    val effectiveConnected = uiState.isConnected || directState.isConnected
    val effectiveRecording = uiState.isRecording || directState.isRecording
    val effectiveRecordingDurationSeconds = if (uiState.isRecording) {
        uiState.recordingDurationSeconds
    } else {
        directState.recordingDurationSeconds
    }
    val effectiveIsStarting = ble.isCaptureStarting || directState.isCaptureStarting
    val effectiveIsStopping = ble.isCaptureStopping || directState.isCaptureStopping
    val effectiveIsTakingPhoto = ble.isTakingPhoto || directState.isTakingPhoto
    val isReady = (uiState.isConnected && !isAsleep) || directState.isConnected
    val bleScanning = ble.connectionStatus == BleConnectionStatus.SCANNING
    val bleConnecting = ble.connectionStatus == BleConnectionStatus.CONNECTING
    val resolvedModel = ble.resolvedModel ?: directState.cameraModel ?: connectionSettings.selectedModelOverride
    val directResolvedMode = directState.subModes?.let { CameraMode.of(it) }
    LaunchedEffect(useDirectControl, directState.subModes) {
        if (useDirectControl) {
            directState.subModes?.let { subModes ->
                CameraMode.of(subModes)?.let { directCameraMode = it }
                cameraViewModel.setMode(if (subModes.isPhoto) CaptureMode.PHOTO else CaptureMode.VIDEO)
            }
        }
    }
    val effectiveCameraMode = if (useDirectControl) {
        directResolvedMode?.takeIf { it.isPhoto == (uiState.currentMode == CaptureMode.PHOTO) }
            ?: directCameraMode.takeIf { it.isPhoto == (uiState.currentMode == CaptureMode.PHOTO) }
            ?: CameraMode.fromCaptureMode(uiState.currentMode)
    } else {
        ble.details.subModes?.let { CameraMode.of(it) }
            ?: if (directState.isConnected) {
                directResolvedMode?.takeIf { it.isPhoto == (uiState.currentMode == CaptureMode.PHOTO) }
                    ?: directCameraMode.takeIf { it.isPhoto == (uiState.currentMode == CaptureMode.PHOTO) }
                    ?: CameraMode.fromCaptureMode(uiState.currentMode)
            } else {
                null
            }
    }

    val handleShutterClick: () -> Unit = {
        if (useDirectControl) {
            val activeDirectMode = effectiveCameraMode ?: directCameraMode
            val singleShot = !activeDirectMode.isContinuous
            if (directState.isCaptureStarting || directState.isCaptureStopping) {
                // Waiting for command confirmation
            } else if (directState.isRecording) {
                directBridge?.stopRecording(activeDirectMode)
            } else if (singleShot) {
                directBridge?.takePhoto(activeDirectMode)
            } else {
                directBridge?.startRecording(activeDirectMode)
            }
        } else {
            cameraViewModel.onShutterClicked()
        }
    }

    val handleModeSelected: (CaptureMode) -> Unit = { mode ->
        cameraViewModel.setMode(mode)
        if (useDirectControl) {
            directBridge?.setCaptureGroup(mode == CaptureMode.PHOTO)
        }
    }

    val handleCameraModeSelected: (CameraMode) -> Unit = { mode ->
        if (useDirectControl) {
            directCameraMode = mode
            cameraViewModel.setMode(if (mode.isPhoto) CaptureMode.PHOTO else CaptureMode.VIDEO)
            directBridge?.setCameraMode(mode)
        } else {
            cameraViewModel.setCameraMode(mode)
        }
    }

    val dotState = CameraStatusText.statusDotState(
        isRecording = effectiveRecording,
        isConnected = effectiveConnected,
        isAsleep = isAsleep && !directState.isConnected,
        isAdvertising = server.isAdvertising,
        bleScanning = bleScanning,
        bleConnecting = bleConnecting || directState.isConnecting
    )

    val deviceTitle = CameraStatusText.topBarTitle(
        serverConnected = server.isCameraConnected,
        serverSerial = server.cameraSerial,
        bleConnected = ble.connectionStatus == BleConnectionStatus.CONNECTED,
        bleDeviceName = ble.deviceName,
        isAdvertising = server.isAdvertising,
        bleScanning = bleScanning,
        bleConnecting = bleConnecting,
        savedSerialSuffix = savedSerialSuffix,
        resolvedModel = resolvedModel,
        directConnected = directState.isConnected,
        directConnecting = directState.isConnecting,
        directLabel = directState.deviceLabel,
        transportMode = connectionSettings.preferredTransport
    )

    val deviceSubtitle = CameraStatusText.topBarSubtitle(
        isRecording = effectiveRecording,
        recordingDurationSeconds = effectiveRecordingDurationSeconds,
        isFootageTab = currentTab == MainNavTab.FOOTAGE,
        footageTab = footageUiState.currentTab,
        wifiStatus = footageUiState.wifiStatus,
        isAsleep = isAsleep && !directState.isConnected,
        isConnected = effectiveConnected,
        isAdvertising = server.isAdvertising,
        bleScanning = bleScanning,
        bleConnecting = bleConnecting || directState.isConnecting
    )

    val connectDirectTransport: (CameraTransportMode) -> Unit = { mode ->
        when (mode) {
            CameraTransportMode.BLE_DIRECT -> onEnsureCameraPermissions { cameraViewModel.connectBle() }
            CameraTransportMode.GPS_REMOTE_SERVER -> {
                connectionPreferences?.setGpsRemoteServerEnabled(true)
                onEnsureCameraPermissions { cameraViewModel.startGpsRemoteServer() }
            }
            CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> onEnsureCameraPermissions {
                directBridge?.connect(mode)
            }
            else -> {
                val savedWifi = cameraViewModel.bleManager.getSavedWifiInfo()
                directBridge?.connect(
                    mode = mode,
                    bleWifiSsid = savedWifi?.ssid,
                    bleWifiPassword = savedWifi?.password
                )
            }
        }
    }

    val subtitleText = when {
        effectiveRecording -> deviceSubtitle
        currentTab == MainNavTab.LIVE_VIEW -> when {
            liveViewState.hasFirstFrame -> "LIVE • ${liveViewState.fps} fps"
            liveViewState.status == LiveViewStatus.CONNECTING_WIFI ||
                liveViewState.status == LiveViewStatus.CONNECTING_SOCKET ||
                liveViewState.status == LiveViewStatus.STARTING_STREAM ||
                liveViewState.status == LiveViewStatus.STREAMING ->
                liveViewState.statusMessage ?: "Connecting..."
            else -> deviceSubtitle
        }
        else -> deviceSubtitle
    }

    val topBarButton = when {
        uiState.linkMove != null -> null
        useDirectControl -> CameraButton.DISCONNECT
        directState.isConnecting && !uiState.isConnected -> CameraButton.STOP
        else -> CameraButton.of(ble, uiState.isConnected)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CameraStatusTopBar(
                title = deviceTitle,
                subtitle = subtitleText,
                isRecording = effectiveRecording,
                dotState = dotState,
                ble = ble,
                isReady = isReady,
                isAsleep = isAsleep && !directState.isConnected,
                cameraButton = topBarButton,
                showQuickConnectButton = currentTab != MainNavTab.REMOTE,
                showRefreshAction = currentTab == MainNavTab.FOOTAGE,
                resolvedModel = resolvedModel,
                transportMode = connectionSettings.preferredTransport,
                directState = directState,
                onOpenConnectionMethods = {
                    connectionSheetTab = 0
                    connectionSheetOpen = true
                },
                onCameraButton = { button ->
                    when {
                        (useDirectControl || (directState.isConnecting && !uiState.isConnected)) &&
                            (button == CameraButton.DISCONNECT || button == CameraButton.STOP) -> {
                            directBridge?.disconnect()
                        }
                        button == CameraButton.CONNECT && !uiState.isConnected && !directState.isConnected &&
                            connectionSettings.preferredTransport != CameraTransportMode.BLE_DIRECT -> {
                            connectDirectTransport(connectionSettings.preferredTransport)
                        }
                        button == CameraButton.CONNECT || button == CameraButton.WAKE_UP -> {
                            onEnsureCameraPermissions { cameraViewModel.onCameraButton(button) }
                        }
                        else -> cameraViewModel.onCameraButton(button)
                    }
                },
                onRefresh = { footageViewModel.refreshCurrentTab() }
            )
        },
        bottomBar = {
            Column {
                UnifiedRecordingBar(
                    currentMode = uiState.currentMode,
                    isRecording = effectiveRecording,
                    isStarting = effectiveIsStarting,
                    isStopping = effectiveIsStopping,
                    isTakingPhoto = effectiveIsTakingPhoto,
                    durationSeconds = effectiveRecordingDurationSeconds,
                    isConnected = effectiveConnected,
                    isAsleep = isAsleep && !directState.isConnected,
                    isMovingLink = uiState.linkMove != null,
                    recordingModeName = CameraStatusText.recordingMode(ble.details.recordingState),
                    cameraMode = effectiveCameraMode,
                    onModeSelected = handleModeSelected,
                    onCameraModeSelected = handleCameraModeSelected,
                    onShutterClick = handleShutterClick
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                // Material 3 Expressive's navigation bar, shorter than the one before it
                ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                ShortNavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(
                            text = "Remote",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = currentTab == MainNavTab.REMOTE,
                    onClick = { currentTab = MainNavTab.REMOTE }
                )
                ShortNavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(
                            text = "Live View",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = currentTab == MainNavTab.LIVE_VIEW,
                    onClick = { currentTab = MainNavTab.LIVE_VIEW }
                )
                ShortNavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(
                            text = "Footage",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = currentTab == MainNavTab.FOOTAGE,
                    onClick = { currentTab = MainNavTab.FOOTAGE }
                )
                ShortNavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null
                        )
                    },
                    label = {
                        Text(
                            text = "Settings",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = currentTab == MainNavTab.SETTINGS,
                    onClick = { currentTab = MainNavTab.SETTINGS }
                )
                }
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
                            onEnsureCameraPermissions = onEnsureCameraPermissions,
                            connectionSettings = connectionSettings,
                            directState = directState,
                            onOpenConnectionMethods = {
                                connectionSheetTab = 0
                                connectionSheetOpen = true
                            },
                            onConnectDirect = connectDirectTransport,
                            onDisconnectDirect = { directBridge?.disconnect() },
                            onToggleGpsRemoteServer = { enabled ->
                                connectionPreferences?.setGpsRemoteServerEnabled(enabled)
                                if (enabled) {
                                    onEnsureCameraPermissions { cameraViewModel.startGpsRemoteServer() }
                                } else {
                                    cameraViewModel.stopGpsRemoteServer()
                                }
                            }
                        )
                    }
                }
                MainNavTab.LIVE_VIEW -> {
                    screens.SaveableStateProvider("live_view") {
                        LiveViewScreen(
                            cameraViewModel = cameraViewModel,
                            liveViewViewModel = liveViewViewModel,
                            directState = directState
                        )
                    }
                }
                MainNavTab.FOOTAGE -> {
                    screens.SaveableStateProvider("footage") {
                        FootageScreen(
                            viewModel = footageViewModel,
                            isSpeedMph = uiState.isSpeedMph
                        )
                    }
                }
                MainNavTab.SETTINGS -> {
                    screens.SaveableStateProvider("settings") {
                        SettingsScreen(
                            hasWakeUpId = uiState.wakeUpId != null,
                            wakeUpRegistration = ble.wakeUpRegistration,
                            keepGpsActiveInBackground = uiState.keepGpsActiveInBackground,
                            isSpeedMph = uiState.isSpeedMph,
                            cameraSerial = savedSerialSuffix,
                            themeMode = themeMode,
                            dynamicColor = dynamicColor,
                            onSetThemeMode = onSetThemeMode,
                            onToggleDynamicColor = onToggleDynamicColor,
                            connectionSettings = connectionSettings,
                            serverState = server,
                            resolvedModel = resolvedModel,
                            onOpenConnectionMethods = { tabIndex ->
                                connectionSheetTab = tabIndex
                                connectionSheetOpen = true
                            },
                            onToggleGpsRemoteServer = { enabled ->
                                connectionPreferences?.setGpsRemoteServerEnabled(enabled)
                                if (enabled) {
                                    onEnsureCameraPermissions { cameraViewModel.startGpsRemoteServer() }
                                } else {
                                    cameraViewModel.stopGpsRemoteServer()
                                }
                            },
                            onSetUpWakeUp = { cameraViewModel.openWakeUpSetup() },
                            onWakeUpId = { cameraViewModel.openWakeUpId() },
                            onSaveCameraSerial = { serial ->
                                cameraViewModel.bleManager.saveSerialSuffix(serial)
                                savedSerialSuffix = cameraViewModel.bleManager.getSavedSerialSuffix().ifBlank { null }
                            },
                            onToggleBackgroundGps = { cameraViewModel.toggleKeepGpsInBackground() },
                            onSetSpeedMph = { cameraViewModel.setSpeedMph(it) },
                            sensors = sensors,
                            onAddSensor = {
                                addSensorOpen = true
                                // Links to saved sensors too, in case Bluetooth permission came after the app started
                                cameraViewModel.sportsSensors.start()
                                cameraViewModel.sportsSensors.startScan()
                            },
                            onReconnectSensor = { cameraViewModel.sportsSensors.reconnect(it) },
                            onRemoveSensor = { cameraViewModel.sportsSensors.remove(it) }
                        )
                    }
                }
            }
        }
    }

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

    if (liveViewState.isFullscreen) {
        val context = LocalContext.current
        LiveViewFullscreenDialog(
            title = deviceTitle,
            state = liveViewState,
            batteryPercent = ble.batteryPercent ?: directState.batteryPercent,
            isCharging = if (ble.batteryPercent != null) ble.isCharging else directState.isCharging,
            currentMode = uiState.currentMode,
            isRecording = effectiveRecording,
            isStarting = effectiveIsStarting,
            isStopping = effectiveIsStopping,
            isTakingPhoto = effectiveIsTakingPhoto,
            recordingDurationSeconds = effectiveRecordingDurationSeconds,
            isConnected = effectiveConnected,
            isAsleep = isAsleep && !directState.isConnected,
            isMovingLink = uiState.linkMove != null,
            onModeSelected = handleModeSelected,
            cameraMode = effectiveCameraMode,
            onCameraModeSelected = handleCameraModeSelected,
            canSelectMode = !effectiveRecording && !effectiveIsStarting && !effectiveIsStopping && !effectiveIsTakingPhoto,
            onStop = { liveViewViewModel.stopLiveView() },
            onSelectResolution = { liveViewViewModel.selectResolution(it) },
            onRetry = { liveViewViewModel.startLiveView() },
            onOpenWifiSettings = { liveViewViewModel.openWifiSettings(context) },
            onShutterClick = handleShutterClick,
            onSurfaceAvailable = { liveViewViewModel.attachSurface(it) },
            onSurfaceDestroyed = { liveViewViewModel.detachSurface(it) },
            onDismiss = { liveViewViewModel.closeFullscreen() },
            canSelectResolution = (resolvedModel?.supportsVariableLivePreviewResolution
                ?: liveViewState.supportsResolutionSelection) && !liveViewState.resolutionLockedByCamera
        )
    }

    if (connectionSheetOpen) {
        ConnectionMethodsSheet(
            settings = connectionSettings,
            ble = ble,
            serverState = server,
            directState = directState,
            attachedUsbDevices = attachedUsbDevices,
            bondedSppDevices = bondedSppDevices,
            initialTabIndex = connectionSheetTab,
            onDismiss = { connectionSheetOpen = false },
            onRefreshHardware = { refreshHardwareInventories() },
            onSelectTransport = { mode -> connectionPreferences?.setPreferredTransport(mode) },
            onConnectTransport = connectDirectTransport,
            onDisconnectBle = { cameraViewModel.disconnectBle() },
            onDisconnectDirect = { directBridge?.disconnect() },
            onToggleGpsRemoteServer = { enabled ->
                connectionPreferences?.setGpsRemoteServerEnabled(enabled)
                if (enabled) {
                    onEnsureCameraPermissions { cameraViewModel.startGpsRemoteServer() }
                } else {
                    cameraViewModel.stopGpsRemoteServer()
                }
            },
            onSaveWifiCredentials = { ssid, pass ->
                connectionPreferences?.setManualWifiCredentials(ssid, pass)
            },
            onSaveStationHostIp = { ip ->
                connectionPreferences?.setStationHostIp(ip)
            },
            onSelectClassicSppAddress = { addr ->
                connectionPreferences?.setClassicSppAddress(addr)
            },
            onSelectModelOverride = { model ->
                connectionPreferences?.setSelectedModelOverride(model)
            }
        )
    }

    if (showPermissionDisclosure) {
        PermissionDisclosureDialog(
            onContinue = onAcceptPermissionDisclosure,
            onNotNow = onDismissPermissionDisclosure
        )
    }
}
