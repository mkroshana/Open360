package com.open360.ui.camera

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
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
import com.open360.core.gps.GpxTrackState
import com.open360.core.gps.runAtFixedRate
import com.open360.core.model.CaptureMode
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val cameraDisplayInfo: String? = null
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

        // Measured on a ride against the camera's own motion sensors: turns and speed changes
        // showed in the GPS, as placed on the video, 0.55 s after they showed in the camera
        // (0.55 s on one clip, about 0.8 s on a second one started with the camera's button,
        // which has its own allowance). Each video moment is therefore given the GPS from this
        // much later. Whether the phone's GPS reports late or the video starts later than
        // worked out is not known; the effect on the video is the same.
        private const val GPS_BEHIND_PICTURE_MS = 550L
    }

    val bleManager = GpsRemoteBleManager(application)
    val gattServer = InstaGpsRemoteGattServer(application)
    val gpsEngine = GpsTrackerEngine(application)
    private val trackRecorder = GpxTrackRecorder(application)
    private val gpsTimeline = GpsTimeline(keepMs = GPS_HISTORY_MS)
    private val gpsSchedule = GpsStreamSchedule(GPS_SAMPLE_INTERVAL_MS, MAX_WAIT_FOR_NEXT_FIX_MS)

    private val settings = application.getSharedPreferences("open360_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        RemoteUiState(
            keepGpsActiveInBackground = settings.getBoolean(PREF_KEEP_GPS_IN_BACKGROUND, true),
            isGpsCardMinimized = settings.getBoolean(PREF_GPS_CARD_MINIMIZED, true)
        )
    )
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var gpsStreamJob: Job? = null
    private var trackedFirstFrameAtMs: Long? = null
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

        // Collect GATT server state updates (Architecture A: GPS Remote)
        viewModelScope.launch {
            gattServer.state.collect { srv ->
                _uiState.update { current ->
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
                _uiState.update { current ->
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
                        _uiState.update {
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
                    _uiState.update { it.copy(wakeUpSetupCardDismissed = false) }
                }

                // GPS is sampled for as long as the camera is connected. The BLE manager passes the
                // samples on only while a recording is running, whoever started it.
                val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
                if (linked && gpsStreamJob == null) {
                    startGpsTelemetryInjection()
                } else if (!linked && gpsStreamJob != null) {
                    stopGpsTelemetryInjection()
                }
                updateWaitingForCamera()
                updateBackgroundService()

                // One GPX track per recording, from its first frame to its stop
                val firstFrame = ble.recordingFirstFrameAtMs
                if (firstFrame != trackedFirstFrameAtMs) {
                    trackedFirstFrameAtMs = firstFrame
                    if (firstFrame != null) {
                        trackRecorder.start(firstFrame)
                        // A recording joined late began in the past: its track takes the fixes
                        // already remembered from then
                        gpsTimeline.fixesFrom(firstFrame).forEach { trackRecorder.addFix(it) }
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
                _uiState.update { it.copy(gpsState = gps) }
                gattServer.updateGpsTelemetry(gps)
                // The track keeps only the fixes taken while a recording is running
                gps.toFix()?.let { trackRecorder.addFix(it) }
            }
        }

        // Collect the GPX track file status
        viewModelScope.launch {
            trackRecorder.state.collect { track ->
                _uiState.update { it.copy(trackState = track) }
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
        _uiState.update {
            it.copy(
                wakeUpId = wakeUpId,
                wakeUpSetupOpen = setupOpen,
                wakeUpSetupCardDismissed = bleManager.isWakeUpSetupCardDismissed()
            )
        }

        // Start GPS tracking immediately so speed and location are available
        gpsEngine.startTracking()

        // Look for the camera straight away
        bleManager.startScanAndConnect()
    }

    fun wakeUpCamera(serialSuffix: String? = null) {
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
        val current = _uiState.value
        val ble = current.bleState
        // Not while a recording runs: what a switch would do to it is not known
        if (current.isRecording || ble.isCaptureStarting || ble.isCaptureStopping) return
        _uiState.update { it.copy(currentMode = mode) }

        // A camera that is on is switched too, so its own button and the pod's screen match.
        // One that is off acts on commands when it next wakes, so it is sent nothing.
        val photo = mode == CaptureMode.PHOTO
        if (ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake &&
            ble.details.subModes?.isPhoto != photo
        ) {
            bleManager.setCameraMode(photo)
        }
    }

    fun toggleSpeedUnit() {
        _uiState.update { it.copy(isSpeedMph = !it.isSpeedMph) }
    }

    /**
     * Stores the wake-up ID. IDs are lower case as the camera holds them, so that is how one is
     * kept however it was typed. Returns false when it is not a valid ID.
     */
    fun saveWakeUpId(id: String): Boolean {
        val normalized = id.trim().lowercase()
        if (!bleManager.saveWakeAuthorizationId(normalized)) return false
        _uiState.update { it.copy(wakeUpId = normalized) }
        // What the camera said about the last ID no longer applies. A camera that is on is asked
        // about this one, and asks on its own screen to allow it if it does not know it.
        bleManager.registerWakeUpId(normalized)
        return true
    }

    fun openWakeUpSetup() {
        bleManager.setHoldLinkWhileOff(true)
        _uiState.update { it.copy(wakeUpSetupOpen = true) }
    }

    fun closeWakeUpSetup() {
        bleManager.markWakeUpSetupOffered()
        bleManager.setHoldLinkWhileOff(false)
        _uiState.update { it.copy(wakeUpSetupOpen = false) }
    }

    /** Hides the card that leads to the set-up. Pressing Wake Up without an ID still opens it. */
    fun dismissWakeUpSetupCard() {
        bleManager.setWakeUpSetupCardDismissed(true)
        _uiState.update { it.copy(wakeUpSetupCardDismissed = true) }
    }

    fun openWakeUpId() {
        _uiState.update { it.copy(wakeUpIdOpen = true) }
    }

    fun closeWakeUpId() {
        _uiState.update { it.copy(wakeUpIdOpen = false) }
    }

    /**
     * Starts the set-up, or tries it again: looks for the camera if it is not connected, makes a
     * wake-up ID if there is none, and asks the camera about the ID. A camera that is off is
     * asked as soon as it answers. Does nothing more while the camera is already being asked.
     */
    fun startWakeUpSetup() {
        bleManager.markWakeUpSetupOffered()
        val ble = bleManager.state.value
        if (ble.connectionStatus == BleConnectionStatus.DISCONNECTED) bleManager.startScanAndConnect()

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
        _uiState.update { it.copy(keepGpsActiveInBackground = keep) }
        updateBackgroundService()
    }

    fun toggleGpsCardMinimized() {
        val minimized = !_uiState.value.isGpsCardMinimized
        settings.edit().putBoolean(PREF_GPS_CARD_MINIMIZED, minimized).apply()
        _uiState.update { it.copy(isGpsCardMinimized = minimized) }
    }

    /**
     * Runs the foreground service for as long as the camera is connected, so that with the
     * display off GPS fixes keep arriving and the link stays up. That covers recordings started
     * on the camera too. Android only lets it start while the app is on screen, so it is kept
     * while the link is being opened again by itself, which may happen with the phone locked.
     */
    private fun updateBackgroundService() {
        val state = _uiState.value
        val wanted = state.keepGpsActiveInBackground &&
            (state.bleState.connectionStatus == BleConnectionStatus.CONNECTED || state.bleState.isReconnecting)
        if (wanted == backgroundServiceRunning) return

        try {
            if (wanted) {
                // Android refuses a location service to an app without the permission
                if (!gpsEngine.hasLocationPermission()) return
                GpsTrackerService.start(getApplication(), holdAwake = !waitingForCamera)
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
     */
    private fun updateWaitingForCamera() {
        val ble = _uiState.value.bleState
        val waiting = !appVisible && ble.isLinkIdle && !ble.isCameraAwake && ble.recordingFirstFrameAtMs == null
        if (waiting == waitingForCamera) return
        waitingForCamera = waiting
        if (waiting) {
            Log.i(TAG, "Waiting for the camera to be switched on: GPS paused")
            gpsEngine.stopTracking()
        } else {
            Log.i(TAG, "No longer waiting for the camera: GPS resumed")
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
        // Send remote button command over CE82 (Architecture A)
        gattServer.sendShutter()

        // Also trigger via Architecture B if connected
        val current = _uiState.value
        // A camera that is switched off holds on to commands and acts on them when it next
        // wakes, so nothing is sent until it is answering
        if (current.bleState.connectionStatus == BleConnectionStatus.CONNECTED && current.bleState.isCameraAwake) {
            when (current.currentMode) {
                CaptureMode.PHOTO -> bleManager.takePhoto()
                CaptureMode.VIDEO -> {
                    val ble = current.bleState
                    if (ble.isCaptureStarting || ble.isCaptureStopping) {
                        // Waiting for the camera to confirm the last tap
                    } else if (ble.isRecording) {
                        bleManager.stopRecording()
                    } else {
                        bleManager.startRecording()
                    }
                }
            }
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        // A recording found already running on connecting carries on from the camera's own count
        val startAt = bleManager.state.value.joinedRecordingSeconds?.toLong() ?: CAPTURE_STATUS_LAG_SECONDS
        _uiState.update { it.copy(recordingDurationSeconds = startAt) }
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update {
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
        _uiState.update { it.copy(recordingDurationSeconds = 0L) }
    }

    private fun startGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = viewModelScope.launch {
            // The clip stores when the first GPS record arrived, not when each one did, and
            // Insta360 Studio shows that first record on the first frame and the rest 100 ms
            // apart by counting. So the samples sent are the positions at the first frame, 100 ms
            // later, 200 ms later and so on, each interpolated between the fixes around it.
            // They go out in a batch each time a new fix makes the next ones known.
            runAtFixedRate(GPS_SAMPLE_INTERVAL_MS, MAX_CATCH_UP_MS, SystemClock::elapsedRealtime) { scheduledMs ->
                gpsSchedule.setFirstFrame(bleManager.state.value.recordingFirstFrameAtMs)
                // A video moment uses the GPS from GPS_BEHIND_PICTURE_MS later, so it is ready
                // once a fix that late has arrived
                val coveredUpTo = gpsTimeline.newestFixMs()?.let { it - GPS_BEHIND_PICTURE_MS }
                val room = MAX_GPS_BACKLOG - bleManager.gpsBacklog()
                if (room <= 0) return@runAtFixedRate
                for (moment in gpsSchedule.due(scheduledMs, coveredUpTo, max = room)) {
                    val sample = gpsTimeline.sampleAt(moment + GPS_BEHIND_PICTURE_MS)
                    bleManager.addGpsFix(
                        // With no fix at all the sample still carries the time of its moment
                        timestampMs = sample?.utcMs
                            ?: (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - moment)),
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
            bearingDegrees = bearingDegrees.toDouble(),
            gpsAltitudeMeters = gpsAltitudeMeters
        )
    }

    override fun onCleared() {
        super.onCleared()
        gpsEngine.stopTracking()
        trackRecorder.stop(SystemClock.elapsedRealtime())
        gattServer.stopServer()
        bleManager.disconnect()
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
