package com.open360.ui.liveview

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.ClassicSppCameraClient
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.connection.ConnectionPreferences
import com.open360.core.connection.DirectCameraControlBridge
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.network.CameraWifiConnector
import com.open360.core.network.CameraWifiResolver
import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.CameraTransportClient
import com.open360.core.protocol.LiveStreamProtocol
import com.open360.core.protocol.LiveStreamProtocol.PreviewResolution
import com.open360.core.protocol.LiveStreamProtocol.StartLiveStreamConfig
import com.open360.core.protocol.LiveStreamProtocol.StreamKind
import com.open360.core.protocol.NalStreamParser.VideoCodec
import com.open360.core.socket.CameraSocketClient
import com.open360.core.stream.LiveStreamDecoder
import com.open360.core.usb.CameraUsbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class LiveViewStatus {
    IDLE,
    CONNECTING_WIFI,
    CONNECTING_SOCKET,
    STARTING_STREAM,
    STREAMING,
    ERROR
}

data class LiveViewUiState(
    val status: LiveViewStatus = LiveViewStatus.IDLE,
    val isFullscreen: Boolean = false,
    val resolution: PreviewResolution = PreviewResolution.RES_640_480_P30,
    val supportsResolutionSelection: Boolean = true,
    val resolutionLockedByCamera: Boolean = false,
    val videoWidth: Int = 640,
    val videoHeight: Int = 480,
    val rotationDegrees: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val codecLabel: String = "H.264",
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val hasFirstFrame: Boolean = false
)

/**
 * Coordinates camera Wi-Fi binding, port-6666 Live View commands (`SetAccessCameraFileState`,
 * `StartLiveStream`, `RequestStreamingIframe`, `StopLiveStream`), and hardware `MediaCodec` decoding.
 */
class LiveViewViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "LiveViewViewModel"
        const val SOCKET_KEEPALIVE_INTERVAL_MS = 1000L
        const val FRESH_WIFI_SETTLE_DELAY_MS = 1500L
        const val SOCKET_CONNECT_RETRY_DELAY_MS = 1000L
        const val MAX_SOCKET_CONNECT_ATTEMPTS = 2
        const val COMMAND_TIMEOUT_MS = 5000L
        const val STOP_COMMAND_TIMEOUT_MS = 600L
        const val RESOLUTION_SWITCH_DRAIN_DELAY_MS = 150L
        const val ACCESS_STATE_START_TIMEOUT_MS = 1500L
        const val ACCESS_STATE_STOP_TIMEOUT_MS = 1000L
        const val IFRAME_COOLDOWN_MS = 1200L
        const val STALL_IFRAME_THRESHOLD_MS = 1500L
        const val STALL_RESTART_THRESHOLD_MS = 4500L
        const val MAX_STREAM_RECOVERIES = 2
    }

    var cameraLink: GpsRemoteBleManager? = null
    var wifiConnector: CameraWifiConnector? = null
    var connectionPreferences: ConnectionPreferences? = null
    var directBridge: DirectCameraControlBridge? = null
    @Volatile
    internal var socketClientOverrideForTesting: CameraTransportClient? = null

    private val fallbackWifiConnector by lazy { CameraWifiConnector(application) }

    private val _uiState = MutableStateFlow(LiveViewUiState())
    val uiState: StateFlow<LiveViewUiState> = _uiState.asStateFlow()

    private val renderedFramesInWindow = AtomicInteger(0)
    private val bytesInWindow = AtomicLong(0L)
    private val lastVideoPacketAtMs = AtomicLong(0L)
    private val lastIframeRequestAtMs = AtomicLong(0L)

    @Volatile
    private var activeSurface: Surface? = null

    @Volatile
    private var socketClient: CameraTransportClient? = null
    @Volatile
    private var ownsSocketClient: Boolean = true
    @Volatile
    private var ownedSocketScope: CoroutineScope? = null
    @Volatile
    private var activePostureAdjustDegrees: Int = 0
    private val liveViewAccessStateActive = AtomicBoolean(false)
    private val acceptVideoPackets = AtomicBoolean(false)
    @Volatile
    private var teardownJob: Job? = null

    private var sessionJob: Job? = null
    private var watchdogJob: Job? = null
    private var resolutionSwitchJob: Job? = null

    private val decoder = LiveStreamDecoder(
        onVideoFormatChanged = { width, height, codec ->
            handleVideoFormatChanged(width, height, codec)
        },
        onFrameRendered = { _ ->
            renderedFramesInWindow.incrementAndGet()
            if (!_uiState.value.hasFirstFrame) {
                _uiState.update { state ->
                    if (state.status == LiveViewStatus.STARTING_STREAM || state.status == LiveViewStatus.STREAMING) {
                        state.copy(
                            status = LiveViewStatus.STREAMING,
                            hasFirstFrame = true,
                            statusMessage = null
                        )
                    } else {
                        state
                    }
                }
            }
        },
        onKeyFrameNeeded = {
            requestIframeThrottled()
        },
        onDecoderError = { message ->
            failSession(message)
        }
    )

    internal fun handleVideoFormatChanged(width: Int, height: Int, codec: VideoCodec) {
        _uiState.update { state ->
            var updatedResolution = state.resolution
            var lockedByCamera = state.resolutionLockedByCamera
            if ((state.status == LiveViewStatus.STREAMING || state.status == LiveViewStatus.STARTING_STREAM) &&
                state.resolution != PreviewResolution.RES_640_480_P30 &&
                width < state.resolution.width * 3 / 4 &&
                height < state.resolution.height * 3 / 4
            ) {
                if (width <= 640 && height <= 480) {
                    updatedResolution = PreviewResolution.RES_640_480_P30
                    lockedByCamera = true
                } else if (state.resolution == PreviewResolution.RES_1920_1080_P30 &&
                    width <= 1280 &&
                    height <= 720
                ) {
                    updatedResolution = PreviewResolution.RES_1280_720_P30
                }
            }
            state.copy(
                resolution = updatedResolution,
                resolutionLockedByCamera = lockedByCamera,
                videoWidth = width,
                videoHeight = height,
                codecLabel = codec.label
            )
        }
    }

    fun hasSavedWifiCredentials(): Boolean = cameraLink?.getSavedWifiInfo() != null

    fun attachSurface(surface: Surface) {
        activeSurface = surface
        decoder.setSurface(surface)
    }

    fun detachSurface(surface: Surface) {
        // Ignore stale detach callbacks after another viewport (e.g. fullscreen) took over.
        if (activeSurface === surface) {
            activeSurface = null
            decoder.setSurface(null)
        }
    }

    private fun resolveCameraModel(reportedType: String? = null): Insta360CameraModel? {
        reportedType?.let { type ->
            Insta360CameraModel.resolveByIdentity(deviceType = type)?.let { return it }
        }
        return cameraLink?.state?.value?.resolvedModel
            ?: directBridge?.state?.value?.cameraModel
            ?: connectionPreferences?.settings?.value?.selectedModelOverride
    }

    fun startLiveView() {
        val model = resolveCameraModel()
        val supportsVariableResolution = model?.supportsVariableLivePreviewResolution ?: true
        activePostureAdjustDegrees = model?.previewPostureAdjustDegrees ?: 0
        val currentResolution = if (supportsVariableResolution) {
            _uiState.value.resolution
        } else {
            PreviewResolution.RES_640_480_P30
        }
        acceptVideoPackets.set(false)
        cancelActiveJobs()
        teardownSocketAsync()
        val previousTeardown = teardownJob
        decoder.stop()
        resetCounters()

        _uiState.update {
            it.copy(
                status = LiveViewStatus.CONNECTING_WIFI,
                resolution = currentResolution,
                supportsResolutionSelection = supportsVariableResolution,
                resolutionLockedByCamera = false,
                videoWidth = currentResolution.width,
                videoHeight = currentResolution.height,
                rotationDegrees = 0,
                fps = 0,
                bitrateKbps = 0,
                hasFirstFrame = false,
                errorMessage = null,
                statusMessage = "Connecting to camera Wi-Fi..."
            )
        }

        sessionJob = viewModelScope.launch {
            previousTeardown?.join()
            val settings = connectionPreferences?.settings?.value
            val preferredTransport = settings?.preferredTransport ?: CameraTransportMode.BLE_DIRECT
            val existingDirect = directBridge?.activeTransportClient()
            val testOverride = socketClientOverrideForTesting

            val client: CameraTransportClient
            val streamHostLabel: String

            if (testOverride != null) {
                client = testOverride
                ownsSocketClient = false
                streamHostLabel = "test transport"
                _uiState.update {
                    it.copy(
                        status = LiveViewStatus.CONNECTING_SOCKET,
                        statusMessage = "Starting preview over $streamHostLabel..."
                    )
                }
            } else if (existingDirect != null) {
                client = existingDirect
                ownsSocketClient = false
                streamHostLabel = directBridge?.state?.value?.transportMode?.label ?: "direct session"
                _uiState.update {
                    it.copy(
                        status = LiveViewStatus.CONNECTING_SOCKET,
                        statusMessage = "Starting preview over $streamHostLabel..."
                    )
                }
            } else when (preferredTransport) {
                CameraTransportMode.USB_C_DIRECT -> {
                    ownsSocketClient = true
                    streamHostLabel = "USB-C OTG"
                    _uiState.update {
                        it.copy(
                            status = LiveViewStatus.CONNECTING_SOCKET,
                            statusMessage = "Opening USB-C camera tunnel..."
                        )
                    }
                    client = CameraUsbClient(getApplication())
                }

                CameraTransportMode.BLUETOOTH_CLASSIC_SPP -> {
                    val sppAddress = settings?.classicSppAddress?.trim().orEmpty().ifEmpty {
                        ClassicSppCameraClient.listBondedDevices(getApplication())
                            .firstOrNull { it.model != null }?.address
                            ?: ClassicSppCameraClient.listBondedDevices(getApplication()).firstOrNull()?.address
                            ?: ""
                    }
                    if (sppAddress.isEmpty()) {
                        failSession("Select or pair your Classic Bluetooth camera (e.g. ONE X) in Connection Methods.")
                        return@launch
                    }
                    ownsSocketClient = true
                    streamHostLabel = "Bluetooth Classic ($sppAddress)"
                    _uiState.update {
                        it.copy(
                            status = LiveViewStatus.CONNECTING_SOCKET,
                            statusMessage = "Opening Bluetooth Classic tunnel..."
                        )
                    }
                    client = ClassicSppCameraClient(getApplication(), sppAddress)
                }

                else -> {
                    ownsSocketClient = true
                    val connector = wifiConnector ?: fallbackWifiConnector
                    val isStation = preferredTransport == CameraTransportMode.WIFI_STATION
                    val savedWifi = cameraLink?.getSavedWifiInfo()
                    val candidateSsid = settings?.manualWifiSsid?.takeIf { it.isNotBlank() } ?: savedWifi?.ssid

                    if (!connector.checkAndBindExistingCameraNetwork(allowStationMode = isStation, targetSsid = candidateSsid) && !isStation) {
                        val bleInfo = cameraLink?.requestWifiInfo() ?: savedWifi
                        val creds = CameraWifiResolver.resolveCredentials(
                            manualSsid = settings?.manualWifiSsid,
                            manualPassword = settings?.manualWifiPassword,
                            bleSsid = bleInfo?.ssid,
                            blePassword = bleInfo?.password,
                            model = cameraLink?.state?.value?.resolvedModel ?: settings?.selectedModelOverride
                        )
                        if (creds == null) {
                            failSession(
                                "Connect over Bluetooth first or enter Wi-Fi SSID/password in Connection Methods."
                            )
                            return@launch
                        }

                        val wifiResult = connector.ensureConnected(creds.ssid, creds.password)
                        if (wifiResult.isFailure) {
                            failSession("Camera Wi-Fi (${creds.ssid}) not reached. Make sure the camera is on.")
                            return@launch
                        }
                    }

                    val freshElapsed = connector.lastFreshConnectElapsedMs
                    if (freshElapsed > 0L) {
                        val sinceConnect = SystemClock.elapsedRealtime() - freshElapsed
                        if (sinceConnect in 0 until FRESH_WIFI_SETTLE_DELAY_MS) {
                            delay(FRESH_WIFI_SETTLE_DELAY_MS - sinceConnect)
                        }
                    }

                    val endpoint = CameraWifiResolver.resolveEndpoint(
                        transportMode = preferredTransport,
                        customHostIp = settings?.stationHostIp,
                        discoveredGatewayIp = connector.getBoundGatewayIp()
                    )
                    streamHostLabel = "${endpoint.host}:${endpoint.socketPort}"
                    Log.i(
                        TAG,
                        "Opening Live View socket on $streamHostLabel (transport=$preferredTransport, boundNetwork=${connector.boundNetwork}, gateway=${connector.getBoundGatewayIp()})"
                    )
                    _uiState.update {
                        it.copy(
                            status = LiveViewStatus.CONNECTING_SOCKET,
                            statusMessage = "Connecting to camera stream on ${endpoint.host}..."
                        )
                    }
                    client = CameraSocketClient(
                        host = endpoint.host,
                        port = endpoint.socketPort,
                        keepAliveIntervalMs = SOCKET_KEEPALIVE_INTERVAL_MS,
                        network = connector.boundNetwork
                    )
                }
            }

            val loggedFirstPrimary = AtomicBoolean(false)
            val loggedFirstSecondary = AtomicBoolean(false)
            val loggedOtherKinds = ConcurrentHashMap.newKeySet<StreamKind>()
            client.onStreamPacket = { packet ->
                when (packet.kind) {
                    StreamKind.VIDEO_PRIMARY -> {
                        if (packet.data.isNotEmpty() && acceptVideoPackets.get()) {
                            if (loggedFirstPrimary.compareAndSet(false, true)) {
                                Log.i(
                                    TAG,
                                    "First VIDEO_PRIMARY packet: type=0x%02x bytes=${packet.data.size} tsMs=${packet.timestampMs}"
                                        .format(packet.streamType)
                                )
                            }
                            lastVideoPacketAtMs.set(System.currentTimeMillis())
                            bytesInWindow.addAndGet(packet.data.size.toLong())
                            decoder.submitVideoPacket(packet.timestampMs, packet.data)
                        }
                    }
                    StreamKind.VIDEO_SECONDARY -> {
                        if (loggedFirstSecondary.compareAndSet(false, true) && !loggedFirstPrimary.get()) {
                            Log.i(
                                TAG,
                                "Received VIDEO_SECONDARY while VIDEO_PRIMARY absent: type=0x%02x bytes=${packet.data.size} tsMs=${packet.timestampMs}"
                                    .format(packet.streamType)
                            )
                        }
                    }
                    else -> {
                        if (loggedOtherKinds.add(packet.kind)) {
                            Log.d(
                                TAG,
                                "Received stream packet kind=${packet.kind} (0x%02x) bytes=${packet.data.size}"
                                    .format(packet.streamType)
                            )
                        }
                    }
                }
            }
            socketClient = client

            if (ownsSocketClient) {
                val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                ownedSocketScope = clientScope
                var lastConnectError: Exception? = null
                var connectedOk = false
                for (attempt in 1..MAX_SOCKET_CONNECT_ATTEMPTS) {
                    try {
                        client.connect(clientScope)
                        connectedOk = true
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            if (ownedSocketScope === clientScope) ownedSocketScope = null
                            client.disconnect()
                            clientScope.cancel()
                            throw e
                        }
                        lastConnectError = e
                        Log.w(
                            TAG,
                            "Socket connect attempt $attempt/$MAX_SOCKET_CONNECT_ATTEMPTS failed on $streamHostLabel: ${e.message}"
                        )
                        client.disconnect()
                        if (attempt < MAX_SOCKET_CONNECT_ATTEMPTS && e is IOException) {
                            delay(SOCKET_CONNECT_RETRY_DELAY_MS)
                        } else {
                            break
                        }
                    }
                }
                if (!connectedOk) {
                    if (ownedSocketScope === clientScope) ownedSocketScope = null
                    clientScope.cancel()
                    if (socketClient === client) {
                        client.onStreamPacket = null
                        socketClient = null
                    }
                    failSession(
                        "Could not open camera stream on $streamHostLabel (${lastConnectError?.message ?: "connection failed"}).",
                        sendStopStream = false
                    )
                    return@launch
                }

                cameraLink?.getWakeAuthorizationId()?.let { authId ->
                    val authReply = withTimeoutOrNull(2500L) {
                        client.request(
                            Go2BleProtocol.CMD_CHECK_AUTHORIZATION,
                            Go2BleProtocol.buildCheckAuthorizationPayload(authId),
                            timeoutMs = 2500L
                        )
                    }
                    Log.i(TAG, "Socket CheckAuthorization reply: code=${authReply?.messageCode}")
                }
            }

            launch {
                var sawConnected = false
                client.isConnected.collect { connected ->
                    if (connected) {
                        sawConnected = true
                    } else if (sawConnected && socketClient === client) {
                        failSession("Camera stream disconnected.")
                    }
                }
            }

            launch {
                client.notifications.collect { notif ->
                    if (notif !is CameraNotification.RawNotification) return@collect
                    when (notif.messageCode) {
                        LiveStreamProtocol.NOTIF_LIVEVIEW_BEGIN_ROTATE -> {
                            val hex = notif.payload.joinToString("") { "%02x".format(it) }
                            Log.i(TAG, "RX LIVEVIEW_BEGIN_ROTATE (0x201B) bytes=${notif.payload.size} hex=$hex")
                            val deg = Go2BleProtocol.parseLiveviewRotateNotification(
                                notif.payload,
                                adjustDegrees = activePostureAdjustDegrees
                            )
                            if (deg != null) {
                                _uiState.update { it.copy(rotationDegrees = deg) }
                            } else if (notif.payload.isEmpty()) {
                                launch { queryCameraPosture(client) }
                            }
                        }

                        Go2BleProtocol.NOTIF_SUB_MODE_CHANGE -> {
                            Go2BleProtocol.parseSubModeNotification(notif.payload)?.let { subModes ->
                                Log.i(
                                    TAG,
                                    "Live View socket camera mode: photo sub-mode ${subModes.photo}, video sub-mode ${subModes.video}"
                                )
                                val ble = cameraLink
                                if (ble?.state?.value?.connectionStatus == BleConnectionStatus.CONNECTED) {
                                    ble.updateSubModesFromExternalTransport(subModes)
                                }
                            }
                        }

                        Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT -> {
                            Go2BleProtocol.parseCaptureTimeLeft(notif.payload)?.let { secondsLeft ->
                                val ble = cameraLink
                                if (ble?.state?.value?.connectionStatus == BleConnectionStatus.CONNECTED) {
                                    ble.updateCaptureSecondsLeftFromExternalTransport(secondsLeft)
                                }
                            }
                        }
                    }
                }
            }

            enterLiveViewAccessState(client)
            val started = startStreamOnSocket(client, _uiState.value.resolution)
            if (!started) return@launch

            launch { queryCameraPosture(client) }
            startWatchdog(client)
        }
    }

    fun selectResolution(resolution: PreviewResolution) {
        if (!_uiState.value.supportsResolutionSelection || _uiState.value.resolutionLockedByCamera) {
            return
        }
        val previous = _uiState.value.resolution
        _uiState.update {
            it.copy(
                resolution = resolution,
                videoWidth = resolution.width,
                videoHeight = resolution.height
            )
        }
        if (previous == resolution) return

        val state = _uiState.value.status
        if (state != LiveViewStatus.STREAMING && state != LiveViewStatus.STARTING_STREAM) {
            return
        }

        val client = socketClient
        if (client == null) {
            startLiveView()
            return
        }

        val session = sessionJob
        if (session == null || !session.isActive) {
            startLiveView()
            return
        }

        resolutionSwitchJob?.cancel()
        resolutionSwitchJob = viewModelScope.launch {
            acceptVideoPackets.set(false)
            watchdogJob?.cancel()
            decoder.stop()
            resetCounters()
            _uiState.update {
                it.copy(
                    status = LiveViewStatus.STARTING_STREAM,
                    fps = 0,
                    bitrateKbps = 0,
                    hasFirstFrame = false,
                    errorMessage = null,
                    statusMessage = "Switching to ${resolution.label}..."
                )
            }
            val stopReply = withTimeoutOrNull(STOP_COMMAND_TIMEOUT_MS) {
                client.request(
                    LiveStreamProtocol.CMD_STOP_LIVE_STREAM,
                    LiveStreamProtocol.encodeStopLiveStream(),
                    timeoutMs = STOP_COMMAND_TIMEOUT_MS
                )
            }
            Log.i(TAG, "StopLiveStream (resolution switch) reply: code=${stopReply?.messageCode}")
            delay(RESOLUTION_SWITCH_DRAIN_DELAY_MS)
            if (startStreamOnSocket(client, resolution)) {
                startWatchdog(client)
            }
        }
    }

    fun openFullscreen() {
        _uiState.update { it.copy(isFullscreen = true) }
    }

    fun closeFullscreen() {
        _uiState.update { it.copy(isFullscreen = false) }
    }

    fun openWifiSettings(context: Context) {
        (wifiConnector ?: fallbackWifiConnector).openWifiSettings(context)
    }

    fun stopLiveView() {
        acceptVideoPackets.set(false)
        if (_uiState.value.status == LiveViewStatus.IDLE && socketClient == null && sessionJob == null) {
            if (_uiState.value.isFullscreen) {
                _uiState.update { it.copy(isFullscreen = false) }
            }
            return
        }
        cancelActiveJobs()
        decoder.stop()
        resetCounters()
        teardownSocketAsync()
        _uiState.update {
            it.copy(
                status = LiveViewStatus.IDLE,
                isFullscreen = false,
                rotationDegrees = 0,
                fps = 0,
                bitrateKbps = 0,
                hasFirstFrame = false,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    private suspend fun queryCameraPosture(client: CameraTransportClient) {
        if (socketClient !== client) return
        val reply = withTimeoutOrNull(2000L) {
            client.request(
                Go2BleProtocol.CMD_GET_OPTIONS,
                Go2BleProtocol.buildCameraPostureAndTypeRequest(),
                timeoutMs = 2000L
            )
        }
        val hex = reply?.body?.joinToString("") { "%02x".format(it) }.orEmpty()
        val info = reply?.takeIf { it.messageCode == 200 }?.let {
            Go2BleProtocol.parseCameraPostureAndTypeReply(it.body)
        }
        val model = resolveCameraModel(info?.cameraType)
        val adjustDegrees = model?.previewPostureAdjustDegrees ?: activePostureAdjustDegrees
        activePostureAdjustDegrees = adjustDegrees
        val deg = info?.rawPosture?.let {
            LiveStreamProtocol.cameraPostureToDegrees(it, adjustDegrees)
        }
        Log.i(
            TAG,
            "GetOptions(CAMERA_TYPE, CAMERA_POSTURE) reply: code=${reply?.messageCode} hex=$hex " +
                "cameraType=${info?.cameraType} model=${model?.name} rawPosture=${info?.rawPosture} " +
                "adjust=$adjustDegrees deg=$deg"
        )
        if (socketClient === client) {
            val supportsVariable = model?.supportsVariableLivePreviewResolution ?: _uiState.value.supportsResolutionSelection
            _uiState.update { state ->
                state.copy(
                    supportsResolutionSelection = supportsVariable,
                    rotationDegrees = deg ?: state.rotationDegrees
                )
            }
        }
    }

    private suspend fun enterLiveViewAccessState(client: CameraTransportClient) {
        liveViewAccessStateActive.set(true)
        val reply = withTimeoutOrNull(ACCESS_STATE_START_TIMEOUT_MS) {
            client.request(
                LiveStreamProtocol.CMD_SET_ACCESS_CAMERA_FILE_STATE,
                LiveStreamProtocol.encodeSetAccessCameraFileState(
                    LiveStreamProtocol.AccessCameraFileState.LIVE_VIEW
                ),
                timeoutMs = ACCESS_STATE_START_TIMEOUT_MS
            )
        }
        Log.i(TAG, "SetAccessCameraFileState(LIVE_VIEW) reply: code=${reply?.messageCode}")
        if (reply != null && reply.messageCode == 400) {
            liveViewAccessStateActive.set(false)
        }
    }

    private suspend fun startStreamOnSocket(
        client: CameraTransportClient,
        resolution: PreviewResolution
    ): Boolean {
        var activeResolution = resolution
        _uiState.update {
            it.copy(
                status = LiveViewStatus.STARTING_STREAM,
                hasFirstFrame = false,
                statusMessage = "Starting ${activeResolution.label} live preview..."
            )
        }

        decoder.start(activeResolution.width, activeResolution.height)
        activeSurface?.let { decoder.setSurface(it) }
        acceptVideoPackets.set(true)

        val config = StartLiveStreamConfig(
            resolution = activeResolution,
            resolution1 = activeResolution,
            enableGyro = true
        )
        var reply = client.request(
            LiveStreamProtocol.CMD_START_LIVE_STREAM,
            LiveStreamProtocol.encodeStartLiveStream(config),
            timeoutMs = COMMAND_TIMEOUT_MS
        )
        Log.i(
            TAG,
            "StartLiveStream(${activeResolution.label}) reply: code=${reply?.messageCode}, bodyLen=${reply?.body?.size}, hex=${reply?.body?.take(16)?.joinToString("") { "%02x".format(it) }}"
        )

        if (reply != null && reply.messageCode != 200 && activeResolution != PreviewResolution.RES_640_480_P30) {
            Log.w(
                TAG,
                "Camera refused ${activeResolution.label} with status ${reply.messageCode}; falling back to 480p"
            )
            activeResolution = PreviewResolution.RES_640_480_P30
            acceptVideoPackets.set(false)
            decoder.stop()
            decoder.start(activeResolution.width, activeResolution.height)
            activeSurface?.let { decoder.setSurface(it) }
            acceptVideoPackets.set(true)
            val fallbackConfig = StartLiveStreamConfig(
                resolution = activeResolution,
                resolution1 = activeResolution,
                enableGyro = true
            )
            reply = client.request(
                LiveStreamProtocol.CMD_START_LIVE_STREAM,
                LiveStreamProtocol.encodeStartLiveStream(fallbackConfig),
                timeoutMs = COMMAND_TIMEOUT_MS
            )
            Log.i(
                TAG,
                "StartLiveStream(${activeResolution.label} fallback) reply: code=${reply?.messageCode}, bodyLen=${reply?.body?.size}"
            )
            if (reply?.messageCode == 200) {
                _uiState.update {
                    it.copy(
                        resolution = activeResolution,
                        videoWidth = activeResolution.width,
                        videoHeight = activeResolution.height
                    )
                }
            }
        }

        if (reply == null || reply.messageCode != 200) {
            acceptVideoPackets.set(false)
            decoder.stop()
            val reason = if (reply == null) "no response" else "status ${reply.messageCode}"
            failSession("Camera refused Live View ($reason).", sendStopStream = false)
            return false
        }

        lastVideoPacketAtMs.set(System.currentTimeMillis())
        _uiState.update {
            it.copy(
                status = LiveViewStatus.STREAMING,
                statusMessage = "Waiting for keyframe..."
            )
        }

        requestIframeNow(client)
        viewModelScope.launch {
            delay(300L)
            if (socketClient === client &&
                _uiState.value.status == LiveViewStatus.STREAMING &&
                !_uiState.value.hasFirstFrame
            ) {
                requestIframeNow(client)
            }
        }
        return true
    }

    private fun startWatchdog(client: CameraTransportClient) {
        watchdogJob?.cancel()
        val scope = sessionJob?.let { viewModelScope } ?: viewModelScope
        watchdogJob = scope.launch {
            var recoveryAttempts = 0
            while (isActive && socketClient === client) {
                delay(1000L)
                val frames = renderedFramesInWindow.getAndSet(0)
                val bytes = bytesInWindow.getAndSet(0L)
                val bitrateKbps = ((bytes * 8L) / 1000L).toInt()

                _uiState.update { state ->
                    if (state.status == LiveViewStatus.STREAMING) {
                        state.copy(fps = frames, bitrateKbps = bitrateKbps)
                    } else {
                        state
                    }
                }

                val silenceMs = System.currentTimeMillis() - lastVideoPacketAtMs.get()
                when {
                    silenceMs < STALL_IFRAME_THRESHOLD_MS -> recoveryAttempts = 0
                    silenceMs in STALL_IFRAME_THRESHOLD_MS until STALL_RESTART_THRESHOLD_MS -> {
                        requestIframeThrottled()
                    }
                    else -> {
                        if (recoveryAttempts >= MAX_STREAM_RECOVERIES) {
                            failSession("Live preview stream stalled. Tap Retry to reconnect.")
                            break
                        }
                        recoveryAttempts++
                        lastVideoPacketAtMs.set(System.currentTimeMillis())
                        val res = _uiState.value.resolution
                        val cfg = StartLiveStreamConfig(resolution = res, resolution1 = res)
                        client.request(
                            LiveStreamProtocol.CMD_START_LIVE_STREAM,
                            LiveStreamProtocol.encodeStartLiveStream(cfg),
                            timeoutMs = COMMAND_TIMEOUT_MS
                        )
                        requestIframeNow(client)
                    }
                }
            }
        }
    }

    private fun requestIframeThrottled() {
        val client = socketClient ?: return
        val now = System.currentTimeMillis()
        val prev = lastIframeRequestAtMs.get()
        if (now - prev < IFRAME_COOLDOWN_MS) return
        if (!lastIframeRequestAtMs.compareAndSet(prev, now)) return

        viewModelScope.launch {
            requestIframeNow(client)
        }
    }

    private suspend fun requestIframeNow(client: CameraTransportClient) {
        if (socketClient !== client) return
        withTimeoutOrNull(2000L) {
            client.request(
                LiveStreamProtocol.CMD_REQUEST_STREAMING_IFRAME,
                LiveStreamProtocol.encodeRequestIframe(),
                timeoutMs = 2000L
            )
        }
    }

    private fun failSession(message: String, sendStopStream: Boolean = true) {
        acceptVideoPackets.set(false)
        cancelActiveJobs()
        decoder.stop()
        resetCounters()
        teardownSocketAsync(sendStopStream)
        _uiState.update {
            it.copy(
                status = LiveViewStatus.ERROR,
                fps = 0,
                bitrateKbps = 0,
                hasFirstFrame = false,
                statusMessage = null,
                errorMessage = message
            )
        }
    }

    private fun cancelActiveJobs() {
        resolutionSwitchJob?.cancel()
        resolutionSwitchJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        sessionJob?.cancel()
        sessionJob = null
    }

    private fun resetCounters() {
        renderedFramesInWindow.set(0)
        bytesInWindow.set(0L)
        lastVideoPacketAtMs.set(0L)
        lastIframeRequestAtMs.set(0L)
    }

    private fun teardownSocketAsync(sendStopStream: Boolean = true) {
        acceptVideoPackets.set(false)
        val client = socketClient
        val shouldDisconnect = ownsSocketClient
        val scopeToCancel = ownedSocketScope
        ownedSocketScope = null
        val shouldResetAccessState = liveViewAccessStateActive.getAndSet(false)
        if (client == null) {
            scopeToCancel?.cancel()
            return
        }
        socketClient = null
        ownsSocketClient = true
        client.onStreamPacket = null
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (sendStopStream) {
                    val stopReply = withTimeoutOrNull(STOP_COMMAND_TIMEOUT_MS) {
                        client.request(
                            LiveStreamProtocol.CMD_STOP_LIVE_STREAM,
                            LiveStreamProtocol.encodeStopLiveStream(),
                            timeoutMs = STOP_COMMAND_TIMEOUT_MS
                        )
                    }
                    Log.i(TAG, "StopLiveStream reply: code=${stopReply?.messageCode}")
                }
                if (shouldResetAccessState) {
                    val idleReply = withTimeoutOrNull(ACCESS_STATE_STOP_TIMEOUT_MS) {
                        client.request(
                            LiveStreamProtocol.CMD_SET_ACCESS_CAMERA_FILE_STATE,
                            LiveStreamProtocol.encodeSetAccessCameraFileState(
                                LiveStreamProtocol.AccessCameraFileState.IDLE
                            ),
                            timeoutMs = ACCESS_STATE_STOP_TIMEOUT_MS
                        )
                    }
                    Log.i(TAG, "SetAccessCameraFileState(IDLE) reply: code=${idleReply?.messageCode}")
                }
            } catch (_: Exception) {
            } finally {
                if (shouldDisconnect) client.disconnect()
                scopeToCancel?.cancel()
            }
        }
        teardownJob = job
        job.invokeOnCompletion {
            if (teardownJob === job) {
                teardownJob = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveJobs()
        decoder.release()
        teardownSocketAsync()
    }
}
