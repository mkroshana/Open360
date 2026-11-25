package com.open360.ui.camera

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.ClassicSppCameraClient
import com.open360.core.ble.InstaGpsRemoteServerState
import com.open360.core.connection.ConnectionSettings
import com.open360.core.connection.DirectConnectionState
import com.open360.core.model.BleWakeStrategy
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.network.CameraWifiResolver
import com.open360.core.ui.Insta360Icons
import com.open360.core.usb.CameraUsbClient
import com.open360.core.usb.Insta360UsbConfig
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.PillSegmentOption
import com.open360.ui.theme.PillSegmentedControl
import com.open360.ui.theme.rememberReducedMotion

/**
 * Interactive Connection Methods & 20-camera Catalog sheet. Allows switching between all 5
 * Insta360 transport architectures (BLE Direct, USB-C OTG, Wi-Fi Camera AP, Wi-Fi Station/LAN,
 * GPS Remote Server, and Bluetooth Classic SPP) or inspecting/pinning any supported camera model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionMethodsSheet(
    settings: ConnectionSettings,
    ble: BleCameraState,
    serverState: InstaGpsRemoteServerState,
    directState: DirectConnectionState,
    attachedUsbDevices: List<CameraUsbClient.AttachedUsbDevice>,
    bondedSppDevices: List<ClassicSppCameraClient.BondedDeviceInfo>,
    initialTabIndex: Int = 0,
    onDismiss: () -> Unit,
    onRefreshHardware: () -> Unit,
    onSelectTransport: (CameraTransportMode) -> Unit,
    onConnectTransport: (CameraTransportMode) -> Unit,
    onDisconnectDirect: () -> Unit,
    onToggleGpsRemoteServer: (Boolean) -> Unit,
    onSaveWifiCredentials: (ssid: String, password: String) -> Unit,
    onSaveStationHostIp: (String) -> Unit,
    onSelectClassicSppAddress: (String) -> Unit,
    onSelectModelOverride: (Insta360CameraModel?) -> Unit,
    onDisconnectBle: () -> Unit = {}
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var selectedTab by rememberSaveable(initialTabIndex) { mutableIntStateOf(initialTabIndex.coerceIn(0, 1)) }
    val haptic = LocalHapticFeedback.current
    val resolvedModel = ble.resolvedModel ?: directState.cameraModel ?: settings.selectedModelOverride

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connection & Cameras",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = resolvedModel?.let { "Unofficial companion • ${it.displayName}" }
                            ?: "Unofficial companion • Auto-detect across ${Insta360CameraModel.entries.size} cameras",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefreshHardware) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan attached USB and Bluetooth devices"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PillSegmentedControl(
                options = listOf(
                    PillSegmentOption(label = "Transports"),
                    PillSegmentOption(label = "Cameras (${Insta360CameraModel.entries.size})")
                ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedTab == 0) {
                TransportsListContent(
                    settings = settings,
                    ble = ble,
                    serverState = serverState,
                    directState = directState,
                    attachedUsbDevices = attachedUsbDevices,
                    bondedSppDevices = bondedSppDevices,
                    onSelectTransport = { mode ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectTransport(mode)
                    },
                    onConnectTransport = onConnectTransport,
                    onDisconnectBle = onDisconnectBle,
                    onDisconnectDirect = onDisconnectDirect,
                    onToggleGpsRemoteServer = onToggleGpsRemoteServer,
                    onSaveWifiCredentials = onSaveWifiCredentials,
                    onSaveStationHostIp = onSaveStationHostIp,
                    onSelectClassicSppAddress = onSelectClassicSppAddress
                )
            } else {
                SupportedCamerasCatalogContent(
                    selectedOverride = settings.selectedModelOverride,
                    detectedModel = ble.resolvedModel ?: directState.cameraModel,
                    onSelectModel = { model ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectModelOverride(model)
                    }
                )
            }
        }
    }
}

@Composable
private fun TransportsListContent(
    settings: ConnectionSettings,
    ble: BleCameraState,
    serverState: InstaGpsRemoteServerState,
    directState: DirectConnectionState,
    attachedUsbDevices: List<CameraUsbClient.AttachedUsbDevice>,
    bondedSppDevices: List<ClassicSppCameraClient.BondedDeviceInfo>,
    onSelectTransport: (CameraTransportMode) -> Unit,
    onConnectTransport: (CameraTransportMode) -> Unit,
    onDisconnectBle: () -> Unit,
    onDisconnectDirect: () -> Unit,
    onToggleGpsRemoteServer: (Boolean) -> Unit,
    onSaveWifiCredentials: (ssid: String, password: String) -> Unit,
    onSaveStationHostIp: (String) -> Unit,
    onSelectClassicSppAddress: (String) -> Unit
) {
    var ssidDraft by remember(settings.manualWifiSsid) { mutableStateOf(settings.manualWifiSsid) }
    var passDraft by remember(settings.manualWifiPassword) { mutableStateOf(settings.manualWifiPassword) }
    var showWifiPassword by remember { mutableStateOf(false) }
    var wifiSaved by remember(ssidDraft, passDraft) { mutableStateOf(false) }
    var stationIpDraft by remember(settings.stationHostIp) { mutableStateOf(settings.stationHostIp) }
    var stationIpSaved by remember(stationIpDraft) { mutableStateOf(false) }
    val wifiCredsDirty = ssidDraft != settings.manualWifiSsid || passDraft != settings.manualWifiPassword
    val stationIpDirty = stationIpDraft.trim() != settings.stationHostIp

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(CameraTransportMode.entries, key = { it.name }) { mode ->
            val isSelected = settings.preferredTransport == mode
            val isThisDirectConnected = directState.isConnected && directState.transportMode == mode
            val isThisDirectConnecting = directState.isConnecting && directState.transportMode == mode

            TransportOptionCard(
                mode = mode,
                selected = isSelected,
                statusText = when (mode) {
                    CameraTransportMode.BLE_DIRECT -> when (ble.connectionStatus) {
                        BleConnectionStatus.CONNECTED -> "Connected (${ble.deviceName ?: "BLE"})"
                        BleConnectionStatus.CONNECTING -> "Connecting..."
                        BleConnectionStatus.SCANNING -> "Scanning BLE..."
                        BleConnectionStatus.DISCONNECTED -> "BE80 / CE80 / AE00 + 3 wake paths"
                    }
                    CameraTransportMode.USB_C_DIRECT -> when {
                        isThisDirectConnected -> "Connected (${directState.deviceLabel ?: "USB-C"})"
                        attachedUsbDevices.any { it.role == Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL } ->
                            attachedUsbDevices.first { it.role == Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL }.displayName
                        attachedUsbDevices.any { it.role == Insta360UsbConfig.UsbDeviceRole.QUICK_READER_STORAGE } ->
                            "Quick Reader attached (storage only)"
                        else -> "Plug camera via USB-C OTG"
                    }
                    CameraTransportMode.WIFI_SOFTAP -> when {
                        isThisDirectConnected -> "Connected (${directState.hostIp ?: CameraWifiResolver.SOFTAP_DEFAULT_HOST})"
                        settings.manualWifiSsid.isNotBlank() -> "SSID: ${settings.manualWifiSsid}"
                        else -> "192.168.42.1:6666 • BLE or \"88888888\" fallback"
                    }
                    CameraTransportMode.WIFI_STATION -> when {
                        isThisDirectConnected -> "Connected (${directState.hostIp ?: settings.stationHostIp})"
                        else -> "Host ${settings.stationHostIp}:6666"
                    }
                    CameraTransportMode.GPS_REMOTE_SERVER -> when {
                        serverState.isCameraConnected -> "Connected (${serverState.cameraSerial ?: "Camera linked"})"
                        serverState.isAdvertising -> "Advertising CE80 / D0FF..."
                        else -> "Off"
                    }
                    CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> when {
                        isThisDirectConnected -> "Connected (${directState.deviceLabel ?: "SPP"})"
                        settings.classicSppAddress.isNotBlank() -> "Target: ${settings.classicSppAddress}"
                        else -> "${bondedSppDevices.size} paired Classic BT device(s)"
                    }
                },
                onSelect = { onSelectTransport(mode) }
            ) {
                when (mode) {
                    CameraTransportMode.BLE_DIRECT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (ble.connectionStatus) {
                                BleConnectionStatus.CONNECTED -> {
                                    OutlinedButton(
                                        onClick = onDisconnectBle,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Disconnect BLE",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                BleConnectionStatus.SCANNING -> {
                                    OutlinedButton(
                                        onClick = onDisconnectBle,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Stop Scanning",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                BleConnectionStatus.CONNECTING -> {
                                    OutlinedButton(
                                        onClick = onDisconnectBle,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Cancel Connection",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                BleConnectionStatus.DISCONNECTED -> {
                                    FilledTonalButton(
                                        onClick = {
                                            onSelectTransport(mode)
                                            onConnectTransport(mode)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Scan & Connect BLE",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    CameraTransportMode.USB_C_DIRECT -> {
                        if (attachedUsbDevices.isNotEmpty()) {
                            attachedUsbDevices.forEach { dev ->
                                Text(
                                    text = "• ${dev.displayName} (${String.format("%04X:%04X", dev.device.vendorId, dev.device.productId)}) — ${usbRoleLabel(dev.role)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isThisDirectConnected) {
                                OutlinedButton(onClick = onDisconnectDirect) {
                                    Text("Disconnect USB", maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    enabled = !isThisDirectConnecting,
                                    onClick = {
                                        onSelectTransport(mode)
                                        onConnectTransport(mode)
                                    }
                                ) {
                                    Text(
                                        text = if (isThisDirectConnecting) "Opening..." else "Connect USB-C",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    CameraTransportMode.WIFI_SOFTAP -> {
                        OutlinedTextField(
                            value = ssidDraft,
                            onValueChange = { ssidDraft = it },
                            label = { Text("Camera Wi-Fi SSID (optional)") },
                            placeholder = { Text("Uses BLE-reported SSID if blank") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passDraft,
                            onValueChange = { passDraft = it },
                            label = { Text("Wi-Fi Password (optional)") },
                            placeholder = { Text("Defaults to BLE password or 88888888") },
                            singleLine = true,
                            visualTransformation = if (showWifiPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                                    Icon(
                                        imageVector = if (showWifiPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showWifiPassword) "Hide password" else "Show password"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                enabled = wifiCredsDirty,
                                onClick = {
                                    onSaveWifiCredentials(ssidDraft, passDraft)
                                    wifiSaved = true
                                }
                            ) {
                                Text(
                                    text = if (wifiSaved && !wifiCredsDirty) "Saved" else "Save Creds",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (isThisDirectConnected) {
                                OutlinedButton(onClick = onDisconnectDirect) {
                                    Text("Disconnect AP", maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    enabled = !isThisDirectConnecting,
                                    onClick = {
                                        onSaveWifiCredentials(ssidDraft, passDraft)
                                        wifiSaved = true
                                        onSelectTransport(mode)
                                        onConnectTransport(mode)
                                    }
                                ) {
                                    Text(
                                        text = if (isThisDirectConnecting) "Joining..." else "Connect AP",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    CameraTransportMode.WIFI_STATION -> {
                        OutlinedTextField(
                            value = stationIpDraft,
                            onValueChange = { stationIpDraft = it },
                            label = { Text("Camera Station / LAN IP") },
                            placeholder = { Text(CameraWifiResolver.STATION_DEFAULT_HOST) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "HTTP media downloads work on standard camera/hotspot gateways (192.168.42.1, 192.168.49.1, 192.168.43.1), while TCP port 6666 control and Live View work on any LAN IP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                enabled = stationIpDirty,
                                onClick = {
                                    onSaveStationHostIp(stationIpDraft)
                                    stationIpSaved = true
                                }
                            ) {
                                Text(
                                    text = if (stationIpSaved && !stationIpDirty) "Saved" else "Save IP",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (isThisDirectConnected) {
                                OutlinedButton(onClick = onDisconnectDirect) {
                                    Text("Disconnect", maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    enabled = !isThisDirectConnecting,
                                    onClick = {
                                        onSaveStationHostIp(stationIpDraft)
                                        stationIpSaved = true
                                        onSelectTransport(mode)
                                        onConnectTransport(mode)
                                    }
                                ) {
                                    Text(
                                        text = if (isThisDirectConnecting) "Connecting..." else "Connect LAN",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    CameraTransportMode.GPS_REMOTE_SERVER -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (serverState.isAdvertising) "Remote GATT Server active" else "Enable GPS Remote Server",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = serverState.isAdvertising || serverState.isCameraConnected,
                                onCheckedChange = onToggleGpsRemoteServer
                            )
                        }
                    }

                    CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> {
                        if (bondedSppDevices.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                bondedSppDevices.take(5).forEach { dev ->
                                    val chosen = settings.classicSppAddress.equals(dev.address, ignoreCase = true)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (chosen) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        contentColor = if (chosen) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectClassicSppAddress(dev.address) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = dev.name,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = dev.address,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (chosen) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                            }
                                            if (chosen) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (isThisDirectConnected) {
                            OutlinedButton(onClick = onDisconnectDirect) {
                                Text("Disconnect SPP", maxLines = 1, softWrap = false)
                            }
                        } else {
                            Button(
                                enabled = !isThisDirectConnecting &&
                                    (settings.classicSppAddress.isNotBlank() || bondedSppDevices.isNotEmpty()),
                                onClick = {
                                    onSelectTransport(mode)
                                    onConnectTransport(mode)
                                }
                            ) {
                                Text(
                                    text = if (isThisDirectConnecting) "Connecting..." else "Connect SPP",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                val cardError = when {
                    mode == CameraTransportMode.BLE_DIRECT && !ble.lastError.isNullOrBlank() -> ble.lastError
                    directState.transportMode == mode && !directState.lastError.isNullOrBlank() -> directState.lastError
                    else -> null
                }
                if (isSelected && cardError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = cardError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportOptionCard(
    mode: CameraTransportMode,
    selected: Boolean,
    statusText: String,
    onSelect: () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (reducedMotion) Modifier
                else Modifier.animateContentSize(animationSpec = tween(180, easing = EaseOutCubic))
            )
            .clickable(onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = transportIcon(mode),
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        CapabilityTag(
                            text = if (mode.isExperimental) "Experimental" else "Verified"
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (selected) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = mode.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                expandedContent()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportedCamerasCatalogContent(
    selectedOverride: Insta360CameraModel?,
    detectedModel: Insta360CameraModel?,
    onSelectModel: (Insta360CameraModel?) -> Unit
) {
    val grouped = remember { Insta360CameraModel.entries.groupBy { it.family } }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            val autoSelected = selectedOverride == null
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (autoSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                contentColor = if (autoSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                border = BorderStroke(
                    1.dp,
                    if (autoSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectModel(null) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-detect camera model",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = detectedModel?.let { "Currently detected: ${it.displayName}" }
                                ?: "Matches BLE name, iBeacon prefix, USB VID/PID, or Option 15/48",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (autoSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (autoSelected) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }
        }

        grouped.forEach { (family, models) ->
            item(key = "header_$family") {
                Text(
                    text = family,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(models, key = { it.configId }) { model ->
                val isSelected = selectedOverride == model
                val isDetected = detectedModel == model
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isDetected -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectModel(if (isSelected) null else model) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(Insta360Icons.cameraDrawableRes(model)),
                                contentDescription = model.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(2.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isDetected) {
                                    Text(
                                        text = "Linked",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CapabilityTag(
                                    text = if (model.isHardwareVerified) "Verified" else "Experimental",
                                    onSelectedCard = isSelected
                                )
                                val wakeLabel = when (model.wakeStrategy) {
                                    BleWakeStrategy.BLE_AUTH_ID -> "BLE Wake ID"
                                    BleWakeStrategy.GO2_FIXED_PACKET -> "GO2 Wake Pkt"
                                    BleWakeStrategy.IBEACON_ORBIT -> "iBeacon Wake"
                                }
                                CapabilityTag(wakeLabel, onSelectedCard = isSelected)
                                CapabilityTag("BLE", onSelectedCard = isSelected)
                                CapabilityTag("USB-C", onSelectedCard = isSelected)
                                if (model.supportsStationWifiMode) CapabilityTag("Station Wi-Fi", onSelectedCard = isSelected)
                                if (model.supportsClassicSpp) CapabilityTag("Classic SPP", onSelectedCard = isSelected)
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityTag(
    text: String,
    onSelectedCard: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (onSelectedCard) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (onSelectedCard) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun usbRoleLabel(role: Insta360UsbConfig.UsbDeviceRole): String = when (role) {
    Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL -> "Camera Control"
    Insta360UsbConfig.UsbDeviceRole.QUICK_READER_STORAGE -> "Quick Reader (Storage Only)"
    Insta360UsbConfig.UsbDeviceRole.UNSUPPORTED -> "Unsupported USB Device"
}

private fun transportIcon(mode: CameraTransportMode): ImageVector = when (mode) {
    CameraTransportMode.BLE_DIRECT -> Icons.Default.Bluetooth
    CameraTransportMode.USB_C_DIRECT -> Icons.Default.Usb
    CameraTransportMode.WIFI_SOFTAP -> Icons.Default.Wifi
    CameraTransportMode.WIFI_STATION -> Icons.Default.Router
    CameraTransportMode.GPS_REMOTE_SERVER -> Icons.Default.DeveloperBoard
    CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> Icons.Default.SettingsBluetooth
}
