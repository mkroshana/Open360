package com.open360.core.connection

import android.content.Context
import android.util.Log
import com.open360.core.ble.CameraMode
import com.open360.core.ble.ClassicSppCameraClient
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.network.CameraWifiConnector
import com.open360.core.network.CameraWifiResolver
import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.CameraTransportClient
import com.open360.core.socket.CameraSocketClient
import com.open360.core.usb.CameraUsbClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates direct non-BLE camera links (`USB_C_DIRECT`, `WIFI_SOFTAP`, `WIFI_STATION`,
 * `BLUETOOTH_CLASSIC_SPP`) using [CameraTransportClient] so every Insta360 connection method can
 * control capture, query identity/status, and share its link with Live View.
 */
data class DirectConnectionState(
    val transportMode: CameraTransportMode? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val deviceLabel: String? = null,
    val cameraModel: Insta360CameraModel? = null,
    val reportedSerial: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val isRecording: Boolean = false,
    val isTakingPhoto: Boolean = false,
    val isCaptureStarting: Boolean = false,
    val isCaptureStopping: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val subModes: Go2BleProtocol.SubModes? = null,
    val captureSecondsLeft: Int? = null,
    val hostIp: String? = null,
    val lastError: String? = null
)

class DirectCameraControlBridge private constructor(
    private val context: Context?,
    private val wifiConnector: CameraWifiConnector?,
    private val connectionPreferences: ConnectionPreferences?,
    ioDispatcher: CoroutineDispatcher
) {
    constructor(
        context: Context,
        wifiConnector: CameraWifiConnector,
        connectionPreferences: ConnectionPreferences
    ) : this(context, wifiConnector, connectionPreferences, Dispatchers.IO)

    internal constructor(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(null, null, null, ioDispatcher)

    companion object {
        private const val TAG = "DirectControlBridge"
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow(DirectConnectionState())
    val state: StateFlow<DirectConnectionState> = _state.asStateFlow()

    @Volatile
    private var activeClient: CameraTransportClient? = null
    private var connectJob: Job? = null
    private var notifJob: Job? = null
    private var connWatchJob: Job? = null
    private var recordingWatchdogJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var commandChannel: Channel<suspend () -> Unit>? = null
    private var commandWorkerJob: Job? = null
    private val subModeLock = Any()
    private var subModeGeneration = 0L
    private var latestRequestedSubModeGen = 0L
    private var lastAppliedSubModeGen = 0L
    private var latestRequestedSubModes: Go2BleProtocol.SubModes? = null
    private var lastPhotoSubMode = 0
    private var lastVideoSubMode = 0

    fun activeTransportClient(): CameraTransportClient? = activeClient

    internal suspend fun attachConnectedClientForTesting(
        client: CameraTransportClient,
        mode: CameraTransportMode = CameraTransportMode.WIFI_SOFTAP,
        fallbackModel: Insta360CameraModel? = Insta360CameraModel.GO_3S
    ) {
        synchronized(subModeLock) {
            activeClient = client
            startCommandQueueLocked()
        }
        _state.value = DirectConnectionState(
            transportMode = mode,
            isConnected = true,
            cameraModel = fallbackModel
        )
        observeClient(client)
        queryInitialDetails(client, fallbackModel)
    }

    fun connect(
        mode: CameraTransportMode,
        bleWifiSsid: String? = null,
        bleWifiPassword: String? = null,
        usbDeviceName: String? = null
    ) {
        val ctx = context ?: error("Context required for connect")
        val connector = wifiConnector ?: error("CameraWifiConnector required for connect")
        val prefs = connectionPreferences ?: error("ConnectionPreferences required for connect")
        connectJob?.cancel()
        disconnect()
        val settings = prefs.settings.value
        val fallbackModel = settings.selectedModelOverride

        connectJob = scope.launch {
            _state.value = DirectConnectionState(
                transportMode = mode,
                isConnecting = true,
                cameraModel = fallbackModel
            )

            try {
                val client: CameraTransportClient
                val resolvedLabel: String
                var resolvedHost: String? = null

                when (mode) {
                    CameraTransportMode.USB_C_DIRECT -> {
                        val attached = CameraUsbClient.inspectAttachedDevices(ctx)
                        val matching = attached.firstOrNull { usbDeviceName == null || it.device.deviceName == usbDeviceName }
                        resolvedLabel = matching?.displayName ?: "Insta360 USB-C"
                        client = CameraUsbClient(ctx, targetDeviceName = usbDeviceName)
                    }

                    CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> {
                        val targetAddress = settings.classicSppAddress.trim().ifEmpty {
                            ClassicSppCameraClient.listBondedDevices(ctx)
                                .firstOrNull { it.model != null }
                                ?.address
                                ?: ClassicSppCameraClient.listBondedDevices(ctx).firstOrNull()?.address
                                ?: ""
                        }
                        if (targetAddress.isEmpty()) {
                            throw IllegalStateException("No paired Bluetooth Classic device found. Select or pair ONE X in Bluetooth settings.")
                        }
                        val bonded = ClassicSppCameraClient.listBondedDevices(ctx).firstOrNull { it.address == targetAddress }
                        resolvedLabel = bonded?.name ?: targetAddress
                        client = ClassicSppCameraClient(ctx, targetAddress)
                    }

                    CameraTransportMode.WIFI_SOFTAP -> {
                        val targetSsid = settings.manualWifiSsid.takeIf { it.isNotBlank() } ?: bleWifiSsid
                        val existing = connector.checkAndBindExistingCameraNetwork(targetSsid = targetSsid)
                        if (!existing) {
                            val creds = CameraWifiResolver.resolveCredentials(
                                manualSsid = settings.manualWifiSsid,
                                manualPassword = settings.manualWifiPassword,
                                bleSsid = bleWifiSsid,
                                blePassword = bleWifiPassword,
                                model = fallbackModel
                            ) ?: throw IllegalStateException("Camera Wi-Fi SSID unknown. Connect BLE once or enter SSID & password in Connection Methods.")

                            connector.ensureConnected(creds.ssid, creds.password).getOrElse { err ->
                                throw IllegalStateException("Could not join ${creds.ssid}: ${err.message ?: "check phone Wi-Fi"}")
                            }
                            Log.i(TAG, "Connected and bound to SoftAP network ${creds.ssid}")
                        }
                        val endpoint = CameraWifiResolver.resolveEndpoint(
                            transportMode = CameraTransportMode.WIFI_SOFTAP,
                            discoveredGatewayIp = connector.getBoundGatewayIp()
                        )
                        resolvedHost = endpoint.host
                        resolvedLabel = settings.manualWifiSsid.ifBlank { bleWifiSsid ?: "Camera AP (${endpoint.host})" }
                        client = CameraSocketClient(
                            host = endpoint.host,
                            port = endpoint.socketPort,
                            network = connector.boundNetwork
                        )
                    }

                    CameraTransportMode.WIFI_STATION -> {
                        connector.checkAndBindExistingCameraNetwork(allowStationMode = true)
                        val endpoint = CameraWifiResolver.resolveEndpoint(
                            transportMode = CameraTransportMode.WIFI_STATION,
                            customHostIp = settings.stationHostIp,
                            discoveredGatewayIp = connector.getBoundGatewayIp()
                        )
                        resolvedHost = endpoint.host
                        resolvedLabel = "Station (${endpoint.host})"
                        client = CameraSocketClient(
                            host = endpoint.host,
                            port = endpoint.socketPort,
                            network = connector.boundNetwork
                        )
                    }

                    CameraTransportMode.BLE_DIRECT,
                    CameraTransportMode.GPS_REMOTE_SERVER -> {
                        throw IllegalArgumentException("$mode is handled by the BLE / GPS Remote manager")
                    }
                }

                client.connect(scope)
                synchronized(subModeLock) {
                    activeClient = client
                    startCommandQueueLocked()
                }

                _state.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        deviceLabel = resolvedLabel,
                        hostIp = resolvedHost,
                        lastError = null
                    )
                }

                observeClient(client)
                queryInitialDetails(client, fallbackModel)
            } catch (e: Exception) {
                Log.w(TAG, "Direct connect ($mode) failed: ${e.message}")
                _state.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        lastError = e.message ?: "Connection failed"
                    )
                }
            }
        }
    }

    private fun startCommandQueueLocked() {
        commandWorkerJob?.cancel()
        commandChannel?.close()
        val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        commandChannel = channel
        commandWorkerJob = scope.launch {
            for (op in channel) {
                op()
            }
        }
    }

    private fun enqueueCommand(client: CameraTransportClient, block: suspend () -> Unit) {
        val ch = synchronized(subModeLock) {
            if (activeClient !== client) return
            commandChannel
        }
        ch?.trySend(block)
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        _state.update { it.copy(recordingDurationSeconds = 0L) }
        recordingTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _state.update { state ->
                    if (state.isRecording) {
                        state.copy(recordingDurationSeconds = state.recordingDurationSeconds + 1L)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _state.update { it.copy(recordingDurationSeconds = 0L) }
    }

    private fun observeClient(client: CameraTransportClient) {
        notifJob?.cancel()
        notifJob = scope.launch {
            client.notifications.collect { notif ->
                when (notif) {
                    is CameraNotification.BatteryUpdate -> {
                        synchronized(subModeLock) {
                            if (activeClient !== client) return@synchronized
                            _state.update {
                                it.copy(batteryPercent = notif.percentage, isCharging = notif.isCharging)
                            }
                        }
                    }
                    is CameraNotification.ShutterStateUpdate -> {
                        synchronized(subModeLock) {
                            if (activeClient !== client) return@synchronized
                            _state.update { it.copy(isRecording = notif.isRecording) }
                            if (notif.isRecording && recordingTimerJob == null) {
                                startRecordingTimer()
                            } else if (!notif.isRecording && recordingTimerJob != null) {
                                stopRecordingTimer()
                            }
                        }
                    }
                    is CameraNotification.RawNotification -> {
                        when (notif.messageCode) {
                            Go2BleProtocol.NOTIF_CURRENT_CAPTURE_STATUS -> {
                                val cs = Go2BleProtocol.parseCaptureState(notif.payload)
                                val rec = Go2BleProtocol.isRecordingCaptureState(cs)
                                synchronized(subModeLock) {
                                    if (activeClient !== client) return@synchronized
                                    _state.update { it.copy(isRecording = rec) }
                                    if (rec && recordingTimerJob == null) {
                                        startRecordingTimer()
                                    } else if (!rec && recordingTimerJob != null) {
                                        stopRecordingTimer()
                                    }
                                }
                            }
                            Go2BleProtocol.NOTIF_BATTERY_UPDATE -> {
                                Go2BleProtocol.parseBatteryUpdateNotification(notif.payload)?.let { b ->
                                    synchronized(subModeLock) {
                                        if (activeClient !== client) return@synchronized
                                        _state.update {
                                            it.copy(batteryPercent = b.levelPercent, isCharging = b.onExternalPower)
                                        }
                                    }
                                }
                            }
                            Go2BleProtocol.NOTIF_SUB_MODE_CHANGE -> {
                                Go2BleProtocol.parseSubModeNotification(notif.payload)?.let { subModes ->
                                    onAuthoritativeSubModes(client, subModes)
                                }
                            }
                            Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT -> {
                                Go2BleProtocol.parseCaptureTimeLeft(notif.payload)?.let { seconds ->
                                    synchronized(subModeLock) {
                                        if (activeClient !== client) return@synchronized
                                        _state.update { it.copy(captureSecondsLeft = seconds) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        connWatchJob?.cancel()
        connWatchJob = scope.launch {
            client.isConnected.collectLatest { connected ->
                if (!connected) {
                    val droppedCurrent = synchronized(subModeLock) {
                        if (activeClient !== client) {
                            false
                        } else {
                            clearConnectionLocked()
                            true
                        }
                    }
                    if (droppedCurrent) {
                        recordingWatchdogJob?.cancel()
                    }
                }
            }
        }
    }

    private suspend fun queryInitialDetails(
        client: CameraTransportClient,
        fallbackModel: Insta360CameraModel?
    ) {
        val initialGen = synchronized(subModeLock) { subModeGeneration }
        val idReply = runCatching {
            client.request(Go2BleProtocol.CMD_GET_OPTIONS, Go2BleProtocol.buildDeviceIdentityRequest(), timeoutMs = 2500L)
        }.getOrNull()
        val identity = idReply?.takeIf { it.messageCode == 200 }?.let { Go2BleProtocol.parseDeviceIdentityReply(it.body) }
        val resolved = Insta360CameraModel.resolveByIdentity(
            deviceType = identity?.cameraType,
            deviceName = _state.value.deviceLabel,
            serialNumber = identity?.serialNumber
        ) ?: fallbackModel

        val statusReply = runCatching {
            client.request(Go2BleProtocol.CMD_GET_OPTIONS, Go2BleProtocol.buildStatusOptionsRequest(), timeoutMs = 2500L)
        }.getOrNull()
        val status = statusReply?.takeIf { it.messageCode == 200 }?.let {
            Go2BleProtocol.parseStatusOptionsReply(it.body)
        }
        synchronized(subModeLock) {
            if (activeClient !== client) return
            status?.subModes?.let { subModes ->
                if (subModeGeneration == initialGen) {
                    val gen = ++subModeGeneration
                    lastAppliedSubModeGen = gen
                    latestRequestedSubModeGen = gen
                    latestRequestedSubModes = null
                    applySubModesLocked(subModes)
                }
            }
            _state.update { state ->
                state.copy(
                    cameraModel = resolved ?: state.cameraModel,
                    reportedSerial = identity?.serialNumber ?: state.reportedSerial,
                    batteryPercent = status?.battery?.levelPercent ?: state.batteryPercent,
                    isCharging = status?.battery?.onExternalPower ?: state.isCharging,
                    captureSecondsLeft = status?.captureSecondsLeft ?: state.captureSecondsLeft
                )
            }
        }
    }

    fun takePhoto(mode: CameraMode = CameraMode.PHOTO) {
        val client = activeClient ?: return
        enqueueCommand(client) {
            if (activeClient !== client) return@enqueueCommand
            _state.update { it.copy(isTakingPhoto = true) }
            try {
                client.request(mode.startCommand, mode.startPayload())
            } catch (e: Exception) {
                Log.w(TAG, "Direct takePhoto (${mode.label}) failed: ${e.message}")
            } finally {
                synchronized(subModeLock) {
                    if (activeClient === client) {
                        _state.update { it.copy(isTakingPhoto = false) }
                    }
                }
            }
        }
    }

    fun startRecording(mode: CameraMode = CameraMode.VIDEO) {
        val client = activeClient ?: return
        enqueueCommand(client) {
            if (activeClient !== client) return@enqueueCommand
            _state.update { it.copy(isCaptureStarting = true) }
            var started = false
            try {
                val reply = client.request(mode.startCommand, mode.startPayload())
                started = reply?.messageCode == 200
                if (reply != null && reply.messageCode != 200) {
                    Log.w(TAG, "Direct startRecording (${mode.label}) failed with status ${reply.messageCode}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct startRecording (${mode.label}) failed: ${e.message}")
            } finally {
                synchronized(subModeLock) {
                    if (activeClient === client) {
                        _state.update {
                            it.copy(
                                isCaptureStarting = false,
                                isRecording = if (started) true else it.isRecording
                            )
                        }
                        if (started && recordingTimerJob == null) {
                            startRecordingTimer()
                        }
                    }
                }
            }
        }
    }

    fun stopRecording(mode: CameraMode = CameraMode.VIDEO) {
        val client = activeClient ?: return
        enqueueCommand(client) {
            if (activeClient !== client) return@enqueueCommand
            _state.update { it.copy(isCaptureStopping = true) }
            var stopped = false
            try {
                val cmd = mode.stopCommand ?: Go2BleProtocol.CMD_STOP_CAPTURE
                val reply = client.request(cmd, mode.stopPayload())
                stopped = reply == null || reply.messageCode == 200
            } catch (e: Exception) {
                Log.w(TAG, "Direct stopRecording failed: ${e.message}")
            } finally {
                synchronized(subModeLock) {
                    if (activeClient === client) {
                        _state.update {
                            it.copy(
                                isCaptureStopping = false,
                                isRecording = if (stopped) false else it.isRecording
                            )
                        }
                        if (stopped) {
                            stopRecordingTimer()
                        }
                    }
                }
            }
        }
    }

    fun setCaptureGroup(photo: Boolean) {
        val subMode = synchronized(subModeLock) {
            if (photo) lastPhotoSubMode else lastVideoSubMode
        }
        sendSubMode(photo, subMode)
    }

    fun setCameraMode(mode: CameraMode) {
        sendSubMode(mode.isPhoto, mode.subMode)
    }

    private fun sendSubMode(photo: Boolean, subMode: Int) {
        val targetSubModes = if (photo) {
            Go2BleProtocol.SubModes(photo = subMode, video = Go2BleProtocol.SUB_MODE_NONE)
        } else {
            Go2BleProtocol.SubModes(photo = Go2BleProtocol.SUB_MODE_NONE, video = subMode)
        }
        val (client, requestGen) = synchronized(subModeLock) {
            val active = activeClient ?: return
            val gen = ++subModeGeneration
            latestRequestedSubModeGen = gen
            latestRequestedSubModes = targetSubModes
            active to gen
        }
        enqueueCommand(client) {
            val shouldSkip = synchronized(subModeLock) {
                activeClient !== client || requestGen < latestRequestedSubModeGen
            }
            if (shouldSkip) return@enqueueCommand
            val reply = runCatching {
                client.request(
                    Go2BleProtocol.CMD_SET_OPTIONS,
                    Go2BleProtocol.buildSetSubModePayload(photo, subMode),
                    timeoutMs = 2500L
                )
            }.getOrNull()
            synchronized(subModeLock) {
                if (activeClient !== client) return@synchronized
                if (reply?.messageCode == 200) {
                    if (requestGen >= lastAppliedSubModeGen && requestGen == latestRequestedSubModeGen) {
                        lastAppliedSubModeGen = requestGen
                        latestRequestedSubModes = null
                        applySubModesLocked(targetSubModes)
                    }
                } else {
                    if (reply != null) {
                        Log.w(TAG, "Direct sub-mode switch ($photo, $subMode) failed with status ${reply.messageCode}")
                    }
                    if (requestGen == latestRequestedSubModeGen) {
                        latestRequestedSubModeGen = lastAppliedSubModeGen
                        latestRequestedSubModes = null
                    }
                }
            }
        }
    }

    private fun onAuthoritativeSubModes(client: CameraTransportClient, subModes: Go2BleProtocol.SubModes) {
        synchronized(subModeLock) {
            if (activeClient !== client) return
            val hasPendingNewer = latestRequestedSubModeGen > lastAppliedSubModeGen
            if (hasPendingNewer && subModes != latestRequestedSubModes) {
                return
            }
            val gen = ++subModeGeneration
            lastAppliedSubModeGen = gen
            latestRequestedSubModeGen = gen
            latestRequestedSubModes = null
            applySubModesLocked(subModes)
        }
    }

    private fun applySubModesLocked(subModes: Go2BleProtocol.SubModes) {
        if (subModes.photo in 0 until Go2BleProtocol.SUB_MODE_NONE) {
            lastPhotoSubMode = subModes.photo
        }
        if (subModes.video in 0 until Go2BleProtocol.SUB_MODE_NONE) {
            lastVideoSubMode = subModes.video
        }
        _state.update { it.copy(subModes = subModes) }
    }

    private fun clearConnectionLocked() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        commandWorkerJob?.cancel()
        commandWorkerJob = null
        commandChannel?.close()
        commandChannel = null
        activeClient = null
        subModeGeneration++
        lastAppliedSubModeGen = subModeGeneration
        latestRequestedSubModeGen = subModeGeneration
        latestRequestedSubModes = null
        lastPhotoSubMode = 0
        lastVideoSubMode = 0
        _state.update {
            it.copy(
                isConnecting = false,
                isConnected = false,
                isRecording = false,
                isTakingPhoto = false,
                isCaptureStarting = false,
                isCaptureStopping = false,
                recordingDurationSeconds = 0L,
                subModes = null,
                captureSecondsLeft = null
            )
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        notifJob?.cancel()
        connWatchJob?.cancel()
        recordingWatchdogJob?.cancel()
        val clientToClose = synchronized(subModeLock) {
            val existing = activeClient
            clearConnectionLocked()
            existing
        }
        clientToClose?.disconnect()
    }
}
