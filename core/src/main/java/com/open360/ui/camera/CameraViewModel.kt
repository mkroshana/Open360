package com.open360.ui.camera

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.R
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraClock
import com.open360.core.ble.CameraMode
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.ble.InstaGpsRemoteGattServer
import com.open360.core.ble.InstaGpsRemoteServerState
import com.open360.core.ble.WakeUpRegistration
import com.open360.core.gps.GpsFix
import com.open360.core.gps.GpsStreamSchedule
import com.open360.core.gps.GpsTelemetry
import com.open360.core.gps.GpsTimeline
import com.open360.core.gps.GpsTrackerEngine
import com.open360.core.gps.GpsTrackerService
import com.open360.core.gps.GpxTrackRecorder
import com.open360.core.sensors.SportsSensorManager
import com.open360.core.gps.GpxTrackState
import com.open360.core.gps.runAtFixedRate
import com.open360.core.model.CaptureMode
import com.open360.core.peer.LinkSharing
import com.open360.core.peer.PeerCommand
import com.open360.core.peer.PeerDevice
import com.open360.core.peer.PeerLink
import com.open360.core.peer.PeerMessages
import com.open360.core.peer.PeerSnapshot
import com.open360.core.peer.holdsLink
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RemoteUiState(
    val bleState: BleCameraState = BleCameraState(),
    val serverState: InstaGpsRemoteServerState = InstaGpsRemoteServerState(),
    val gpsState: GpsTelemetry = GpsTelemetry(),
    val trackState: GpxTrackState = GpxTrackState(),
    val currentMode: CaptureMode = CaptureMode.VIDEO,
    val isSpeedMph: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val keepGpsActiveInBackground: Boolean = true,
    val isGpsCardMinimized: Boolean = true,
    // The ID a switched-off camera accepts a wake-up from, null when none is stored
    val wakeUpId: String? = null,
    // Whether the set-up that gets the camera to allow this app to wake it is on screen
    val wakeUpSetupOpen: Boolean = false,
    // Whether the card that leads to that set-up has been dismissed
    val wakeUpSetupCardDismissed: Boolean = false,
    // Whether the wake-up ID itself is on screen, for copying or replacing it
    val wakeUpIdOpen: Boolean = false,
    val isConnected: Boolean = false,
    val isRecording: Boolean = false,
    val cameraDisplayInfo: String? = null,
    // Whether Open360 is open on the other device, the watch for the phone app and the phone for
    // the watch app, and in reach
    val peerOpen: Boolean = false,
    // Set while the camera is linked through the other device: the camera, GPS, mode and recording
    // time shown are that device's, and the camera's controls are passed on to it
    val linkedThroughPeer: Boolean = false
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"

        // Whether GPS and the camera link are kept going with the display off; on unless turned off
        private const val PREF_KEEP_GPS_IN_BACKGROUND = "keep_gps_in_background"
        private const val PREF_GPS_CARD_MINIMIZED = "gps_card_minimized"

        // The camera reports "recording" about a second after the clip really starts: on three
        // GO 3S clips the time between its start and stop reports was 0.85 to 1.06 s shorter
        // than the clip. The timer starts from this value to make up for it.
        private const val CAPTURE_STATUS_LAG_SECONDS = 1L

        private const val GPS_SAMPLE_INTERVAL_MS = 100L
        private const val MAX_CATCH_UP_MS = 2000L

        // A sample is sent once the fix after its moment has arrived, normally within two
        // seconds. If no fix comes in this long the sample goes out as void instead.
        private const val MAX_WAIT_FOR_NEXT_FIX_MS = 4000L

        // Fixes are remembered this long, so a recording that was already running when the app
        // connected can be given the positions from its missed part, where there were any
        private const val GPS_HISTORY_MS = 10 * 60_000L

        // Samples are only made as fast as they can be sent. When a recording is joined late
        // its whole missed part is due at once, and no sample may be dropped.
        private const val MAX_GPS_BACKLOG = 40

        // GPS time is counted from a fix at most this far from the moment, as the phone's clock
        // drifts from it by about 30 ppm
        private const val GPS_TIME_FROM_FIX_WITHIN_MS = 60_000L

        // How long a recording's GPS records wait for the camera's clock to be read; it takes
        // about 8 s from the camera starting to answer, and 15 s when the clock is also set
        private const val WAIT_FOR_CAMERA_CLOCK_MS = 15_000L

        // The other app's snapshots (PeerSnapshot): it counts as open for this long after the last.
        // Each app sends one on every change, within PEER_UPDATE_MS, and at least every
        // PEER_HEARTBEAT_MS while it hears from the other, and only every PEER_IDLE_HEARTBEAT_MS
        // otherwise.
        private const val PEER_FRESH_MS = 6000L
        private const val PEER_HEARTBEAT_MS = 2000L
        private const val PEER_IDLE_HEARTBEAT_MS = 10_000L
        private const val PEER_UPDATE_MS = 250L

        // On opening, the other app is given this long to say whether it has the camera before
        // this one looks for it
        private const val PEER_ANSWER_MS = 1500L

        private const val SERVICE_TEXT_OWN_LINK = "GPS is sent to the camera while recording"
    }

    val bleManager = GpsRemoteBleManager(application)
    val gattServer = InstaGpsRemoteGattServer(application)
    val gpsEngine = GpsTrackerEngine(application)
    // Heart rate, cadence and power sensors, whose readings go into each recording's FIT file
    val sportsSensors = SportsSensorManager(application)
    private val trackRecorder = GpxTrackRecorder(application) { from, to -> sportsSensors.readings(from, to) }
    private val gpsTimeline = GpsTimeline(keepMs = GPS_HISTORY_MS)
    private val gpsSchedule = GpsStreamSchedule(GPS_SAMPLE_INTERVAL_MS, MAX_WAIT_FOR_NEXT_FIX_MS)

    private val settings = application.getSharedPreferences("open360_settings", Context.MODE_PRIVATE)

    // Each video moment is given the GPS from this much later, as the device's GPS reports motion
    // that much after the camera films it: 550 ms for the phone and 250 ms for the watch, each
    // measured on rides against the camera's own motion sensors (res/values/gps_timing.xml)
    private val gpsBehindPictureMs = application.resources.getInteger(R.integer.gps_behind_picture_ms).toLong()

    private val _uiState = MutableStateFlow(
        RemoteUiState(
            keepGpsActiveInBackground = settings.getBoolean(PREF_KEEP_GPS_IN_BACKGROUND, true),
            isGpsCardMinimized = settings.getBoolean(PREF_GPS_CARD_MINIMIZED, true)
        )
    )
    // What the screens show: this app's own state, or, while the camera is linked through the
    // other app, that app's camera in it (showState)
    private val _shownState = MutableStateFlow(_uiState.value)
    val uiState: StateFlow<RemoteUiState> = _shownState.asStateFlow()

    // This app and the other one, which can control the camera through each other
    private val thisDevice =
        if (application.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) PeerDevice.WATCH else PeerDevice.PHONE
    private val peerName = if (thisDevice == PeerDevice.PHONE) "watch" else "phone"
    private val peerLink = PeerLink(application, ::onPeerSnapshot, ::onPeerCommand)
    private var peer: PeerSnapshot? = null
    private var peerHeardAtMs = 0L
    private val peerAnswered = CompletableDeferred<Unit>()
    private var linkedThrough = false
    // Photos the other app's camera answered while it was heard from, added to this app's own
    // counts so that a screen confirms each photo whichever app holds the link
    private var peerPhotosTaken = 0
    private var peerPhotosFailed = 0
    private var serviceText = SERVICE_TEXT_OWN_LINK

    private var recordingTimerJob: Job? = null
    private var gpsStreamJob: Job? = null
    private var trackedStreamStartAtMs: Long? = null
    // The recording whose GPS records are being stamped, when the app first saw it, and how much
    // later than their moments its records are stamped (CameraClock.stampOffsetMs), fixed once known
    private var stampedStreamStartMs: Long? = null
    private var streamSeenAtMs = 0L
    private var stampOffsetMs: Long? = null
    // The camera's mode as last reported, so the Video and Photo choice follows each new report
    private var followedSubModes: Go2BleProtocol.SubModes? = null
    private var backgroundServiceRunning = false

    // Whether the app is on screen, and whether it is off screen with a link to a camera that is
    // off, when GPS is paused and the processor may sleep (updateWaitingForCamera)
    private var appVisible = true
    private var waitingForCamera = false

    init {
        // The GO 3S has not connected to a phone advertising as the GPS Remote, so the GATT server
        // is not started here. The app connects out to the camera and uploads GPS over that link.
        // The two cannot run together: there is only one LE link between phone and camera.

        // The camera's clock is read and set against GPS time
        bleManager.setGpsTimeSource { elapsedMs -> gpsTimeline.utcAt(elapsedMs, GPS_TIME_FROM_FIX_WITHIN_MS) }

        // Collect GATT server state updates (Architecture A: GPS Remote)
        viewModelScope.launch {
            gattServer.state.collect { srv ->
                updateState { current ->
                    val connected = srv.isCameraConnected || current.bleState.connectionStatus == BleConnectionStatus.CONNECTED
                    val recording = srv.isRecording || current.bleState.isRecording
                    val duration = if (srv.isRecording && srv.recordingDurationSeconds > 0) {
                        srv.recordingDurationSeconds
                    } else {
                        current.recordingDurationSeconds
                    }
                    current.copy(
                        serverState = srv,
                        isConnected = connected,
                        isRecording = recording,
                        recordingDurationSeconds = duration,
                        cameraDisplayInfo = srv.cameraDisplayString
                    )
                }

                if (srv.isRecording && recordingTimerJob == null) {
                    startRecordingTimer()
                } else if (!srv.isRecording && recordingTimerJob != null && !_uiState.value.bleState.isRecording) {
                    stopRecordingTimer()
                }
            }
        }

        // Collect BLE state updates (Architecture B: direct connection)
        viewModelScope.launch {
            bleManager.state.collect { ble ->
                updateState { current ->
                    val connected = current.serverState.isCameraConnected || ble.connectionStatus == BleConnectionStatus.CONNECTED
                    val recording = current.serverState.isRecording || ble.isRecording
                    current.copy(
                        bleState = ble,
                        isConnected = connected,
                        isRecording = recording
                    )
                }

                // The Video and Photo choice follows the camera when it reports its mode, on
                // connecting and whenever it changes, from the app or on the camera
                val subModes = ble.details.subModes
                if (subModes != followedSubModes) {
                    followedSubModes = subModes
                    if (subModes != null) {
                        updateState {
                            it.copy(currentMode = if (subModes.isPhoto) CaptureMode.PHOTO else CaptureMode.VIDEO)
                        }
                    }
                }

                if (ble.isRecording && recordingTimerJob == null) {
                    startRecordingTimer()
                } else if (!ble.isRecording && recordingTimerJob != null && !_uiState.value.serverState.isRecording) {
                    stopRecordingTimer()
                }

                // Once the camera has allowed the app, a dismissal of the set-up card is spent:
                // the card is to show again if the camera should stop allowing it
                if (ble.wakeUpRegistration == WakeUpRegistration.AUTHORIZED && _uiState.value.wakeUpSetupCardDismissed) {
                    bleManager.setWakeUpSetupCardDismissed(false)
                    updateState { it.copy(wakeUpSetupCardDismissed = false) }
                }

                // GPS is sampled for as long as the camera is connected. The BLE manager passes the
                // samples on only while a recording is running, whoever started it.
                val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
                if (linked && gpsStreamJob == null) {
                    startGpsTelemetryInjection()
                } else if (!linked && gpsStreamJob != null) {
                    stopGpsTelemetryInjection()
                }
                coordinateLink()
                updateWaitingForCamera()
                updateBackgroundService()

                // One GPX track per recording, from the start of its GPS stream to its stop
                val streamStart = ble.gpsStreamStartAtMs
                if (streamStart != trackedStreamStartAtMs) {
                    trackedStreamStartAtMs = streamStart
                    if (streamStart != null) {
                        trackRecorder.start(streamStart)
                        // A recording joined late began in the past: its track takes the fixes
                        // already remembered from then
                        gpsTimeline.fixesFrom(streamStart).forEach { trackRecorder.addFix(it) }
                    } else {
                        trackRecorder.stop(SystemClock.elapsedRealtime())
                    }
                }
                ble.lastVideoFile?.let { trackRecorder.setVideoFileName(it) }
            }
        }

        // Collect GPS updates and feed directly into the 10 Hz GATT server stream
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                updateState { it.copy(gpsState = gps) }
                gattServer.updateGpsTelemetry(gps)
                // The track keeps only the fixes taken while a recording is running
                gps.toFix()?.let { trackRecorder.addFix(it) }
            }
        }

        // Collect the GPX track file status
        viewModelScope.launch {
            trackRecorder.state.collect { track ->
                updateState { it.copy(trackState = track) }
            }
        }

        // Keep the recent fixes for interpolating the samples sent to the camera
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                gps.toFix()?.let { gpsTimeline.add(it) }
            }
        }

        // The set-up for Wake Up is put forward once, the first time the app runs without an ID
        val wakeUpId = bleManager.getWakeAuthorizationId()
        val setupOpen = wakeUpId == null && !bleManager.wasWakeUpSetupOffered()
        // The set-up waits for a camera that is off to be switched on, which only a held link shows
        bleManager.setHoldLinkWhileOff(setupOpen)
        updateState {
            it.copy(
                wakeUpId = wakeUpId,
                wakeUpSetupOpen = setupOpen,
                wakeUpSetupCardDismissed = bleManager.isWakeUpSetupCardDismissed()
            )
        }

        // Start GPS tracking immediately so speed and location are available
        gpsEngine.startTracking()

        // Look for the camera straight away, unless Open360 on the other device has it: with a
        // device in reach, that app is asked first
        peerLink.start { hasDevice ->
            viewModelScope.launch {
                if (hasDevice) {
                    sendSnapshot(ownSnapshot())
                    withTimeoutOrNull(PEER_ANSWER_MS) { peerAnswered.await() }
                }
                connectBle()
            }
        }
        startPeerUpdates()
        sportsSensors.start()
    }

    private inline fun updateState(transform: (RemoteUiState) -> RemoteUiState) {
        _uiState.update(transform)
        showState()
    }

    /**
     * Works out what the screens show from this app's own state and the other app's last snapshot:
     * while the camera is linked through the other app, its camera, GPS, mode and recording time.
     * Photo counts are this app's and the other's added, so each new photo shows as one more.
     */
    private fun showState() {
        val own = _uiState.value
        val other = freshPeer()
        val through = LinkSharing.isLinkedThrough(own.bleState, other, takingLink = false)
        val photosTaken = own.bleState.photosTaken + peerPhotosTaken
        val photosFailed = own.bleState.photosFailed + peerPhotosFailed
        val shown = if (through && other != null) {
            val camera = other.camera
            own.copy(
                // What the camera said about this app's own wake-up ID stays this app's
                bleState = camera.copy(
                    wakeUpRegistration = own.bleState.wakeUpRegistration,
                    photosTaken = photosTaken,
                    photosFailed = photosFailed
                ),
                gpsState = other.gps,
                currentMode = other.currentMode,
                recordingDurationSeconds = other.recordingSeconds,
                isConnected = camera.connectionStatus == BleConnectionStatus.CONNECTED,
                isRecording = camera.isRecording
            )
        } else {
            own.copy(bleState = own.bleState.copy(photosTaken = photosTaken, photosFailed = photosFailed))
        }
        _shownState.value = shown.copy(peerOpen = other != null, linkedThroughPeer = through)

        if (through != linkedThrough) {
            linkedThrough = through
            Log.i(TAG, if (through) "The camera is linked through the $peerName" else "The camera is no longer linked through the $peerName")
            updateWaitingForCamera()
            updateBackgroundService()
        }
    }

    /** The other app's last snapshot, while it is recent enough to count as open. */
    private fun freshPeer(nowMs: Long = SystemClock.elapsedRealtime()): PeerSnapshot? =
        peer?.takeIf { nowMs - peerHeardAtMs <= PEER_FRESH_MS }

    /** What this app tells the other about its own link, whether or not it is linked through that one. */
    private fun ownSnapshot(): PeerSnapshot {
        val own = _uiState.value
        // The link's own state, which may be ahead of the copy in _uiState, as when the app closes
        val camera = bleManager.state.value
        return PeerSnapshot(
            from = thisDevice,
            // Only whether GPS has been sent is shown, so its time is left out of the comparison
            // that decides whether a snapshot has changed
            camera = camera.copy(lastGpsSentTimeMs = if (camera.lastGpsSentTimeMs > 0) 1L else 0L),
            currentMode = own.currentMode,
            recordingSeconds = own.recordingDurationSeconds,
            gps = own.gpsState,
            canTakeLink = appVisible || backgroundServiceRunning
        )
    }

    private fun sendSnapshot(snapshot: PeerSnapshot) {
        peerLink.sendSnapshot(PeerMessages.encodeSnapshot(snapshot, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    // Whether the other app needs to hear from this one: not from one closed to the background
    // with no link, which has nothing to show it and could not keep a link moved to it
    private fun isWorthTelling(): Boolean = appVisible || backgroundServiceRunning || bleManager.state.value.holdsLink

    /**
     * Tells the other app about this one's link on every change and as a heartbeat, and stops
     * showing the other's camera once it has gone quiet.
     */
    private fun startPeerUpdates() {
        viewModelScope.launch {
            var lastSent: PeerSnapshot? = null
            var lastSentAtMs = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val snapshot = ownSnapshot()
                // With the other app not heard from only the heartbeat goes out, as there may be no
                // one to tell; an app that opens says so, and is answered at once
                val due = if (freshPeer(now) != null) {
                    snapshot != lastSent || now - lastSentAtMs >= PEER_HEARTBEAT_MS
                } else {
                    now - lastSentAtMs >= PEER_IDLE_HEARTBEAT_MS
                }
                if (isWorthTelling() && due) {
                    sendSnapshot(snapshot)
                    lastSent = snapshot
                    lastSentAtMs = now
                }
                showState()
                delay(PEER_UPDATE_MS)
            }
        }
    }

    private fun onPeerSnapshot(snapshot: PeerSnapshot) {
        // Another device of this app's own kind is not one to share the camera with
        if (snapshot.from == thisDevice) return
        val now = SystemClock.elapsedRealtime()
        val previous = freshPeer(now)
        if (previous != null) {
            // A count that went down is the other app having started again
            peerPhotosTaken += (snapshot.camera.photosTaken - previous.camera.photosTaken).coerceAtLeast(0)
            peerPhotosFailed += (snapshot.camera.photosFailed - previous.camera.photosFailed).coerceAtLeast(0)
        } else {
            Log.i(TAG, "Open360 is open on the $peerName (link ${snapshot.camera.connectionStatus})")
            // An app that has just opened hears from this one at once, not at the next heartbeat
            if (isWorthTelling()) sendSnapshot(ownSnapshot())
        }
        peer = snapshot
        peerHeardAtMs = now
        peerAnswered.complete(Unit)
        coordinateLink()
        showState()
    }

    /** What the other app asks while this one holds the link and it controls the camera through it. */
    private fun onPeerCommand(command: PeerCommand) {
        // Only the app holding the link acts on the other's taps
        if (!bleManager.state.value.holdsLink) return
        when (command) {
            // A second tap before the other app has heard that a photo is being taken
            PeerCommand.Shutter -> if (!bleManager.state.value.isTakingPhoto) onShutterClicked()
            is PeerCommand.SetCaptureMode -> setMode(command.mode)
            is PeerCommand.SetCameraMode -> setCameraMode(command.mode)
            is PeerCommand.PressCameraButton -> onCameraButton(command.button)
            PeerCommand.GiveLink, PeerCommand.TakeLink, PeerCommand.TakeLinkFailed -> Unit
        }
    }

    /** Lets go of this app's link, or stops looking for the camera, when the other app has it too. */
    private fun coordinateLink() {
        val other = freshPeer() ?: return
        if (LinkSharing.shouldLetGo(bleManager.state.value, other, thisDevice, takingLink = false)) {
            Log.i(TAG, "Open360 on the $peerName has the camera link too, so this one lets go of it")
            disconnectBle()
        }
    }

    // Whether Open360 on the other device holds the link or is looking for the camera
    private fun peerHoldsLink(): Boolean = freshPeer()?.holdsLink == true

    fun wakeUpCamera(serialSuffix: String? = null) {
        // The app holding the link wakes the camera, when it is linked to it and the camera is off
        if (linkedThrough) {
            val camera = freshPeer()?.camera
            if (camera?.connectionStatus == BleConnectionStatus.CONNECTED && !camera.isCameraAwake) {
                peerLink.sendCommand(PeerCommand.PressCameraButton(CameraButton.WAKE_UP))
            }
            return
        }
        val ble = bleManager.state.value
        // Nothing to wake when the camera is already on
        if (ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake) return

        // A GO camera only wakes for an app it has allowed, which the set-up takes care of
        if (_uiState.value.wakeUpId == null) openWakeUpSetup()

        // A GO camera that is off still accepts a connection and is woken over a fresh one.
        // The beacon is for cameras that sleep without advertising.
        val suffix = serialSuffix ?: "8JY3HF"
        bleManager.startWakeUpBroadcast(suffix)
        bleManager.wakeByConnecting()
    }

    /**
     * Does what the camera card's button showed when it was pressed. Connect also switches the
     * camera on if it is off, once Wake Up is set up; without an ID it only connects.
     */
    fun onCameraButton(button: CameraButton) {
        // The button was worked out from the other app's link, so that app does what it says
        if (linkedThrough) {
            peerLink.sendCommand(PeerCommand.PressCameraButton(button))
            return
        }
        when (button) {
            CameraButton.CONNECT -> if (_uiState.value.wakeUpId != null) wakeUpCamera() else connectBle()
            CameraButton.WAKE_UP -> wakeUpCamera()
            CameraButton.STOP, CameraButton.DISCONNECT -> disconnectBle()
        }
    }

    fun startGpsRemoteServer() {
        gattServer.startServer()
    }

    fun stopGpsRemoteServer() {
        gattServer.stopServer()
    }

    fun connectBle() {
        // Not while Open360 on the other device has the camera, which this app then controls through it
        if (peerHoldsLink()) return
        bleManager.startScanAndConnect()
    }

    fun connect() {
        connectBle()
    }

    fun disconnectBle() {
        bleManager.disconnect()
        stopRecordingTimer()
        stopGpsTelemetryInjection()
    }

    fun disconnect() {
        disconnectBle()
    }

    fun setMode(mode: CaptureMode) {
        if (linkedThrough) {
            if (!isShownCameraBusy()) peerLink.sendCommand(PeerCommand.SetCaptureMode(mode))
            return
        }
        val current = _uiState.value
        val ble = current.bleState
        // Not while a recording runs: what a switch would do to it is not known
        if (current.isRecording || ble.isCaptureStarting || ble.isCaptureStopping) return
        updateState { it.copy(currentMode = mode) }

        // A camera that is on is switched too, so its own button and the pod's screen match.
        // One that is off acts on commands when it next wakes, so it is sent nothing.
        val photo = mode == CaptureMode.PHOTO
        if (ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake &&
            ble.details.subModes?.isPhoto != photo
        ) {
            bleManager.setCameraMode(photo)
        }
    }

    /** Switches a camera that is on to [mode], from the row of modes. Held while a recording runs. */
    fun setCameraMode(mode: CameraMode) {
        if (linkedThrough) {
            if (!isShownCameraBusy()) peerLink.sendCommand(PeerCommand.SetCameraMode(mode))
            return
        }
        val current = _uiState.value
        val ble = current.bleState
        if (current.isRecording || ble.isCaptureStarting || ble.isCaptureStopping) return
        if (ble.connectionStatus != BleConnectionStatus.CONNECTED || !ble.isCameraAwake) return
        updateState { it.copy(currentMode = if (mode.isPhoto) CaptureMode.PHOTO else CaptureMode.VIDEO) }
        bleManager.setCameraMode(mode)
    }

    // Whether the camera shown is recording or confirming a start or stop, when its mode is held
    private fun isShownCameraBusy(): Boolean {
        val camera = _shownState.value.bleState
        return camera.isRecording || camera.isCaptureStarting || camera.isCaptureStopping
    }

    fun toggleSpeedUnit() {
        updateState { it.copy(isSpeedMph = !it.isSpeedMph) }
    }

    /**
     * Stores the wake-up ID. IDs are lower case as the camera holds them, so that is how one is
     * kept however it was typed. Returns false when it is not a valid ID.
     */
    fun saveWakeUpId(id: String): Boolean {
        val normalized = id.trim().lowercase()
        if (!bleManager.saveWakeAuthorizationId(normalized)) return false
        updateState { it.copy(wakeUpId = normalized) }
        // What the camera said about the last ID no longer applies. A camera that is on is asked
        // about this one, and asks on its own screen to allow it if it does not know it.
        bleManager.registerWakeUpId(normalized)
        return true
    }

    fun openWakeUpSetup() {
        bleManager.setHoldLinkWhileOff(true)
        updateState { it.copy(wakeUpSetupOpen = true) }
    }

    fun closeWakeUpSetup() {
        bleManager.markWakeUpSetupOffered()
        bleManager.setHoldLinkWhileOff(false)
        updateState { it.copy(wakeUpSetupOpen = false) }
    }

    /** Hides the card that leads to the set-up. Pressing Wake Up without an ID still opens it. */
    fun dismissWakeUpSetupCard() {
        bleManager.setWakeUpSetupCardDismissed(true)
        updateState { it.copy(wakeUpSetupCardDismissed = true) }
    }

    fun openWakeUpId() {
        updateState { it.copy(wakeUpIdOpen = true) }
    }

    fun closeWakeUpId() {
        updateState { it.copy(wakeUpIdOpen = false) }
    }

    /**
     * Starts the set-up, or tries it again: looks for the camera if it is not connected, makes a
     * wake-up ID if there is none, and asks the camera about the ID. A camera that is off is
     * asked as soon as it answers. Does nothing more while the camera is already being asked.
     */
    fun startWakeUpSetup() {
        bleManager.markWakeUpSetupOffered()
        val ble = bleManager.state.value
        if (ble.connectionStatus == BleConnectionStatus.DISCONNECTED) connectBle()

        val id = _uiState.value.wakeUpId
        when {
            id == null -> saveWakeUpId(UUID.randomUUID().toString())
            ble.wakeUpRegistration == WakeUpRegistration.CHECKING ||
                ble.wakeUpRegistration == WakeUpRegistration.CONFIRM_ON_CAMERA -> Unit
            else -> bleManager.registerWakeUpId(id)
        }
    }

    fun toggleKeepGpsInBackground() {
        val keep = !_uiState.value.keepGpsActiveInBackground
        settings.edit().putBoolean(PREF_KEEP_GPS_IN_BACKGROUND, keep).apply()
        updateState { it.copy(keepGpsActiveInBackground = keep) }
        updateBackgroundService()
    }

    fun toggleGpsCardMinimized() {
        val minimized = !_uiState.value.isGpsCardMinimized
        settings.edit().putBoolean(PREF_GPS_CARD_MINIMIZED, minimized).apply()
        updateState { it.copy(isGpsCardMinimized = minimized) }
    }

    /**
     * Runs the foreground service for as long as the camera is connected, so that with the
     * display off GPS fixes keep arriving and the link stays up. That covers recordings started
     * on the camera too. Android only lets it start while the app is on screen, so it is kept
     * while the link is being opened again by itself, which may happen with the phone locked.
     * It runs too while the camera is linked through the other device, so this app stays up to
     * show it, which on the watch keeps the remote on its face, and can take the link over.
     */
    private fun updateBackgroundService() {
        val state = _uiState.value
        val wanted = state.keepGpsActiveInBackground &&
            (state.bleState.connectionStatus == BleConnectionStatus.CONNECTED || state.bleState.isReconnecting || linkedThrough)
        val text = if (linkedThrough) "Controlled through Open360 on your $peerName" else SERVICE_TEXT_OWN_LINK
        if (wanted == backgroundServiceRunning) {
            if (wanted && text != serviceText) {
                try {
                    GpsTrackerService.setText(getApplication(), text)
                    serviceText = text
                } catch (e: Exception) {
                    Log.w(TAG, "Could not change the background service's notification", e)
                }
            }
            return
        }

        try {
            if (wanted) {
                // Android refuses a location service to an app without the permission
                if (!gpsEngine.hasLocationPermission()) return
                GpsTrackerService.start(getApplication(), holdAwake = !waitingForCamera, text = text)
                serviceText = text
            } else {
                GpsTrackerService.stop(getApplication())
            }
            backgroundServiceRunning = wanted
        } catch (e: Exception) {
            Log.w(TAG, "Could not ${if (wanted) "start" else "stop"} the background service", e)
        }
    }

    /** Called as the app comes on screen and goes off it. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        updateWaitingForCamera()
    }

    /**
     * While the app is off screen with an idle link to a camera that is off, nothing it does is
     * needed until the camera is switched on, so GPS is paused and the processor is let sleep.
     * Both start again as soon as the camera answers; the location service keeps running, as
     * Android would not let it start again from the background. On screen GPS keeps running for
     * the readouts, and while a link is being opened or a recording runs nothing is paused.
     * The same goes for the camera linked through the other device, whose GPS goes to the camera:
     * this one's is wanted only on screen, ready for the link to be moved here.
     */
    private fun updateWaitingForCamera() {
        val ble = _uiState.value.bleState
        val cameraOff = ble.isLinkIdle && !ble.isCameraAwake && ble.gpsStreamStartAtMs == null
        val waiting = !appVisible && (cameraOff || linkedThrough)
        if (waiting == waitingForCamera) return
        waitingForCamera = waiting
        if (waiting) {
            Log.i(TAG, if (linkedThrough) "Linked through the $peerName and off screen: GPS paused" else "Waiting for the camera to be switched on: GPS paused")
            gpsEngine.stopTracking()
        } else {
            Log.i(TAG, "GPS resumed")
            gpsEngine.startTracking()
        }
        if (backgroundServiceRunning) {
            try {
                GpsTrackerService.setHoldAwake(getApplication(), holdAwake = !waiting)
            } catch (e: Exception) {
                Log.w(TAG, "Could not tell the background service whether to hold the phone awake", e)
            }
        }
    }

    fun onShutterClicked() {
        // The app holding the link works out what the shutter does, from its camera's mode
        if (linkedThrough) {
            peerLink.sendCommand(PeerCommand.Shutter)
            return
        }

        // Send remote button command over CE82 (Architecture A)
        gattServer.sendShutter()

        // Also trigger via Architecture B if connected
        val current = _uiState.value
        // A camera that is switched off holds on to commands and acts on them when it next
        // wakes, so nothing is sent until it is answering
        if (current.bleState.connectionStatus == BleConnectionStatus.CONNECTED && current.bleState.isCameraAwake) {
            val ble = current.bleState
            // Each of the camera's modes is captured with its own command. Its mode is used when
            // it is one of those the Video and Photo choice shows; otherwise normal video or a
            // single photo.
            val mode = CameraMode.inGroup(ble.details.subModes, photo = current.currentMode == CaptureMode.PHOTO)
            val singleShot = mode?.let { !it.isContinuous } ?: (current.currentMode == CaptureMode.PHOTO)
            if (ble.isCaptureStarting || ble.isCaptureStopping) {
                // Waiting for the camera to confirm the last tap
            } else if (ble.isRecording) {
                // A recording, or a timelapse, interval or starlapse run
                bleManager.stopRecording()
            } else if (singleShot) {
                bleManager.takePhoto(mode)
            } else {
                bleManager.startRecording(mode)
            }
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        // A recording found already running on connecting carries on from the camera's own count
        val startAt = bleManager.state.value.joinedRecordingSeconds?.toLong() ?: CAPTURE_STATUS_LAG_SECONDS
        updateState { it.copy(recordingDurationSeconds = startAt) }
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                updateState {
                    if (it.serverState.isRecording && it.serverState.recordingDurationSeconds > 0) {
                        it.copy(recordingDurationSeconds = it.serverState.recordingDurationSeconds)
                    } else {
                        it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1)
                    }
                }
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        updateState { it.copy(recordingDurationSeconds = 0L) }
    }

    private fun startGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = viewModelScope.launch {
            // Insta360 Studio shows at each video moment the record whose time is the clip's
            // first_gps_timestamp plus the video time, and that is the camera's clock reading
            // from just before the first frame (CameraClock). So the samples sent are the
            // positions at moments 100 ms apart, each interpolated between the fixes around it
            // and stamped with its moment moved onto the camera's clock. They go out in a batch
            // each time a new fix makes the next ones known.
            runAtFixedRate(GPS_SAMPLE_INTERVAL_MS, MAX_CATCH_UP_MS, SystemClock::elapsedRealtime) { scheduledMs ->
                val ble = bleManager.state.value
                val streamStart = ble.gpsStreamStartAtMs
                gpsSchedule.setStart(streamStart)
                if (streamStart != stampedStreamStartMs) {
                    stampedStreamStartMs = streamStart
                    streamSeenAtMs = scheduledMs
                    stampOffsetMs = null
                }
                // The stamps need the camera's clock, which is read once the camera answers, so
                // a recording started straight away waits a little for it
                if (streamStart != null && stampOffsetMs == null) {
                    val clockOffset = ble.cameraClockOffsetMs
                    if (clockOffset == null && scheduledMs - streamSeenAtMs < WAIT_FOR_CAMERA_CLOCK_MS) return@runAtFixedRate
                    // Fixed for the whole recording. Without a reading the clock is taken as right,
                    // as it was set on an earlier connection.
                    stampOffsetMs = CameraClock.stampOffsetMs(clockOffset ?: 0L, ble.recordingCaptureState())
                    Log.i(TAG, "GPS records stamped ${stampOffsetMs} ms after their moments (camera clock ${clockOffset ?: "not read"})")
                }
                // A video moment uses the GPS from gpsBehindPictureMs later, so it is ready
                // once a fix that late has arrived
                val coveredUpTo = gpsTimeline.newestFixMs()?.let { it - gpsBehindPictureMs }
                val room = MAX_GPS_BACKLOG - bleManager.gpsBacklog()
                if (room <= 0) return@runAtFixedRate
                for (moment in gpsSchedule.due(scheduledMs, coveredUpTo, max = room)) {
                    val sample = gpsTimeline.sampleAt(moment + gpsBehindPictureMs)
                    // With no fix near, the phone's own clock gives the moment's time
                    val momentUtc = gpsTimeline.utcAt(moment, GPS_TIME_FROM_FIX_WITHIN_MS)
                        ?: (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - moment))
                    bleManager.addGpsFix(
                        timestampMs = momentUtc + (stampOffsetMs ?: 0L),
                        latitude = sample?.latitude ?: 0.0,
                        longitude = sample?.longitude ?: 0.0,
                        speedMps = sample?.speedMps ?: 0.0,
                        bearingDegrees = sample?.bearingDegrees ?: 0.0,
                        altitudeMeters = sample?.altitudeMeters ?: 0.0,
                        // A moment with no fix close on both sides is sent as void
                        hasFix = sample?.valid == true
                    )
                }
            }
        }
    }

    private fun stopGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = null
    }

    /**
     * The CameraCaptureState of the recording, or, before the camera says, normal Video or Slow
     * Motion if that is its mode.
     */
    private fun BleCameraState.recordingCaptureState(): Int? =
        details.recordingState
            ?: when (details.subModes?.let { CameraMode.of(it) }) {
                CameraMode.VIDEO -> CameraClock.CAPTURE_STATE_VIDEO
                CameraMode.SLOW_MOTION -> CameraClock.CAPTURE_STATE_SLOW_MOTION
                else -> null
            }

    /** The fix this telemetry holds, or null before the first position has arrived. */
    private fun GpsTelemetry.toFix(): GpsFix? {
        if (timestampEpochMs <= 0 || fixElapsedRealtimeMs <= 0) return null
        return GpsFix(
            elapsedMs = fixElapsedRealtimeMs,
            utcMs = timestampEpochMs,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            speedMps = speedKmh / 3.6,
            bearingDegrees = bearingDegrees.toDouble()
        )
    }

    override fun onCleared() {
        super.onCleared()
        gpsEngine.stopTracking()
        trackRecorder.stop(SystemClock.elapsedRealtime())
        sportsSensors.stop()
        gattServer.stopServer()
        bleManager.disconnect()
        // The other app stops showing this one's link straight away, rather than once it goes quiet
        sendSnapshot(ownSnapshot())
        peerLink.stop()
        recordingTimerJob?.cancel()
        gpsStreamJob?.cancel()
        if (backgroundServiceRunning) {
            try {
                GpsTrackerService.stop(getApplication())
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the background service", e)
            }
            backgroundServiceRunning = false
        }
    }
}
