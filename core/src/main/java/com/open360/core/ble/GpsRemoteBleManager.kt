package com.open360.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import com.open360.core.model.BleWakeStrategy
import com.open360.core.model.Insta360CameraModel
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.open360.core.gps.GpsSendPacer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

enum class BleConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

/**
 * What the camera reports about itself besides its battery and recording state. Each value is
 * null until the camera has sent it, and all are forgotten when the link ends.
 */
data class CameraDetails(
    // Recording time left on the storage at the current mode's bitrate, in seconds. In Photo
    // mode the camera gives the photos left here instead.
    val captureSecondsLeft: Int? = null,
    val cardState: Go2BleProtocol.CardState? = null,
    val freeSpaceBytes: Long? = null,
    val totalSpaceBytes: Long? = null,
    val pod: Go2BleProtocol.ChargeBoxStatus? = null,
    // CameraCaptureState of the recording in progress (1 Video, 17 PRO Video), null when idle
    val recordingState: Int? = null,
    // The camera's photo and video sub-modes, which tell whether it is in Photo or Video
    val subModes: Go2BleProtocol.SubModes? = null,
    // Warnings the camera raises itself
    val batteryLow: Boolean = false,
    val temperatureHigh: Boolean = false
)

/**
 * Where getting the camera to accept the wake-up ID stands: not asked, asked, being asked about
 * on the camera's own screen, accepted, or one of the ways it can fail.
 */
enum class WakeUpRegistration {
    NONE,
    CHECKING,
    CONFIRM_ON_CAMERA,
    AUTHORIZED,
    REJECTED,
    TIMED_OUT,
    CAMERA_BUSY,
    NO_ANSWER
}

data class BleCameraState(
    val connectionStatus: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val wakeUpRegistration: WakeUpRegistration = WakeUpRegistration.NONE,
    // Whether the camera itself is answering. A GO 3S that is powered off still accepts a
    // Bluetooth connection, but sends nothing and acts on nothing until it is switched on.
    val isCameraAwake: Boolean = false,
    val isRecording: Boolean = false,
    val isCaptureStarting: Boolean = false,
    val isCaptureStopping: Boolean = false,
    // SystemClock.elapsedRealtime() from which the current recording's GPS is streamed, a little
    // before its first video frame; null when idle
    val gpsStreamStartAtMs: Long? = null,
    // Photos the camera has answered taking, or refused, on this run of the app: each count goes
    // up once per answer, so a screen can confirm every photo
    val photosTaken: Int = 0,
    val photosFailed: Int = 0,
    // Set from a take-picture command until the camera answers it, which it does once the photo
    // is taken, about 3 s later on a GO 3S
    val isTakingPhoto: Boolean = false,
    // File name of the last video the camera reported saving, e.g. VID_20261012_163049_00_066.mp4.
    // Only known for recordings stopped from the app. Cleared when the next recording starts.
    val lastVideoFile: String? = null,
    // Its length in whole seconds, from the same reply
    val lastVideoSeconds: Int? = null,
    // Set when the camera was already recording as the app connected: how long it had been
    // recording. The clip's first frame is worked back from this, to within about half a second.
    val joinedRecordingSeconds: Int? = null,
    // Set while the link is being opened again by itself after it dropped soon after the camera
    // started answering, as a GO 3S switched on while linked does
    val isReconnecting: Boolean = false,
    // Set from Wake Up until the camera answers or the attempt ends, including while the fresh
    // link for it is opened
    val isWaking: Boolean = false,
    // Set once the camera has not answered on the link for a few seconds: it is off, and nothing
    // needs to run until it answers
    val isLinkIdle: Boolean = false,
    // How far the clock the camera stamps clips with is ahead of GPS time, read on this link
    // (CameraClock); null until read, and forgotten once the camera stops answering or the link ends
    val cameraClockOffsetMs: Long? = null,
    // The camera's battery as it last reported it, forgotten once it stops answering or the link ends
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val details: CameraDetails = CameraDetails(),
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val cameraModel: Insta360CameraModel? = null,
    val reportedDeviceType: String? = null,
    val reportedSerialNumber: String? = null,
    val lastGpsSentTimeMs: Long = 0L,
    val lastError: String? = null,
    val isCameraWifiReady: Boolean = false
) {
    /** Best resolved [Insta360CameraModel] from reported type, advertised name, or serial prefix. */
    val resolvedModel: Insta360CameraModel?
        get() = cameraModel ?: Insta360CameraModel.resolveByIdentity(
            deviceType = reportedDeviceType,
            deviceName = deviceName,
            serialNumber = reportedSerialNumber
        )
}

/**
 * Manages Bluetooth Low Energy (BLE) communication with Insta360 cameras (GO 3 / GO 3S).
 *
 * Implements:
 * - Architecture B (Direct Control): Go2BlePacket framing over BE80/BE81/BE82.
 * - Architecture A fallback: Fixed 9-byte frames over CE80/CE81/CE82.
 * - Wake-up beacon: iBeacon manufacturer advertisement (Apple 0x004c, "ORBIT").
 * - Reliable sequential write queue avoiding Android BLE concurrency collisions.
 */
class GpsRemoteBleManager(private val context: Context) {

    companion object {
        private const val TAG = "GpsRemoteBleManager"
        private const val KEEP_ALIVE_INTERVAL_MS = 2000L

        // An awake camera answers every keep-alive. After this long with nothing from it, it
        // is taken to have gone to sleep.
        private const val CAMERA_SILENT_MS = 7000L

        // How long the wake beacon is broadcast for
        private const val WAKE_BEACON_MS = 10_000
        private const val LOW_BATTERY_CLEARED_PERCENT = 20
        private const val CAPTURE_CHANGE_TIMEOUT_MS = 10_000L

        // A photo with no answer by then is no longer shown as being taken. An HDR set takes
        // longer than a single photo, so this allows well over the 3 s a single one took.
        private const val PHOTO_ANSWER_TIMEOUT_MS = 15_000L

        // 10 s of samples. Beyond this the link is not keeping up and the oldest are dropped.
        private const val MAX_QUEUED_GPS_RECORDS = 100

        // Samples are made ten a second, and the camera takes no more than that (GpsSendPacer),
        // so they go out at that rate, a fix's ten together, in messages of as many as the link
        // takes (four at the MTU of 247 a GO 3S takes)
        private const val GPS_SEND_RECORDS_PER_SECOND = 10.0
        private const val GPS_SEND_MAX_BURST = 10

        // A recording's GPS is streamed from before its first frame, so that its first record is
        // older than the camera's clock reading the clip stores, which then places the GPS
        // (CameraClock); Studio leaves out the records before it. On a GO 3S (2026-10-17) the first
        // frame came 1.92 to 2.02 s after the start notice in Video and 0.78 to 0.82 s after it in
        // PRO Video, for app and button starts alike, and the notice about 0.15 s after the app's
        // start command. So a recording started from the app is streamed from its start command,
        // and one started on the camera from its start notice.
        // A start notice this soon after the app's start command was caused by it
        private const val START_COMMAND_NOTICE_WINDOW_MS = 3000L

        // The capture status notification came 1.2 to 1.5 s after the first frame (2026-10-17),
        // and 1.6 to 2.3 s after it before. Used only when a recording is seen without its start notice.
        private const val STREAM_START_BEFORE_CAPTURE_STATUS_MS = 2800L

        val SERVICE_BE80: UUID = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
        val WRITE_BE81: UUID = UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_BE82: UUID = UUID.fromString("0000be82-0000-1000-8000-00805f9b34fb")

        val SERVICE_CE80: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")
        val WRITE_CE81: UUID = UUID.fromString("0000ce81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CE82: UUID = UUID.fromString("0000ce82-0000-1000-8000-00805f9b34fb")

        // The camera gives the time recorded so far in whole seconds: on the one clip checked, a
        // reply of 5 s came between 5.4 and 6.4 s after the first frame. A recording joined late
        // is streamed from this much further back, so from before its first frame.
        private const val JOINED_RECORDING_STREAM_EXTRA_MS = 1500L

        // How long the camera's Bluetooth is given to answer the wake-up authorization, and
        // then the camera to switch on. With the Insta360 app these took 0.3 s and 2.1 s.
        private const val WAKE_REPLY_MS = 3000L
        private const val WAKE_SWITCH_ON_MS = 8000L

        private const val PREF_WAKE_AUTHORIZATION_ID = "wake_authorization_id"

        private const val PREF_WAKE_SETUP_OFFERED = "wake_setup_offered"
        private const val PREF_WAKE_SETUP_CARD_DISMISSED = "wake_setup_card_dismissed"

        // How long the camera is given to answer an authorization check, and how long its
        // question about a new app is given to be answered on the camera
        private const val AUTHORIZATION_REPLY_MS = 5000L
        private const val AUTHORIZATION_CONFIRM_MS = 60_000L

        // The camera's Wi-Fi name and password, kept from its last answer. A GO 3S answered the
        // request in about a second.
        private const val PREF_CAMERA_WIFI_SSID = "camera_wifi_ssid"
        private const val PREF_CAMERA_WIFI_PASSWORD = "camera_wifi_password"
        private const val WIFI_INFO_REPLY_MS = 5000L

        // A GO 3S switched on while linked sometimes drops the link a few seconds after it starts
        // answering (5, 7 and 23 s seen), and a link opened after that holds. Its Bluetooth stops
        // sending without ending the link, as it always does when the camera is switched off,
        // and the phone gives up on it after the 5 s supervision timeout. A link that drops this
        // soon after the camera started answering on it is opened again by itself, and so is a
        // reconnection that fails, at most this many times in a row (ReconnectPolicy).
        private const val EARLY_DROP_MS = 60_000L
        private const val MAX_AUTO_RECONNECTS = 3
        private const val RECONNECT_DELAY_MS = 1000L

        // A camera that is on answers as soon as the link is up. One that has not answered within
        // this long is off, and the link is let go.
        private const val SILENT_LINK_MS = 3000L

        // The camera's clock (CameraClock) is read this long after the camera starts answering
        // and after each recording, once GPS time is known, waiting this long at most for a fix.
        // It is asked for this many times this far apart, across its ticks, and the reading is
        // kept when it is good to this much.
        private const val CLOCK_SYNC_DELAY_MS = 3000L
        private const val CLOCK_WAIT_FOR_GPS_MS = 120_000L
        private const val CLOCK_READINGS = 24
        private const val CLOCK_READING_INTERVAL_MS = 150L
        private const val CLOCK_LAST_ANSWER_MS = 1000L
        private const val CLOCK_MAX_UNCERTAINTY_MS = 150L

        // A clock further than this from GPS time is set, while nothing is recording. The set is
        // planned this far ahead and written this long before the camera should handle it.
        private const val CLOCK_SET_THRESHOLD_MS = 600L
        private const val CLOCK_SET_LEAD_MS = 400L
        private const val CLOCK_SET_WRITE_AHEAD_MS = 60L
        private const val CLOCK_SET_SETTLE_MS = 1500L

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BleCameraState())
    val state: StateFlow<BleCameraState> = _state.asStateFlow()

    @Volatile
    private var activeGatt: BluetoothGatt? = null
    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var sequenceNumber = 1

    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile
    private var pendingWriteAck: CompletableDeferred<Boolean>? = null

    @Volatile
    private var attMtu = 23
    private var activeScanCallback: ScanCallback? = null
    @Volatile
    private var keepAliveJob: Job? = null
    @Volatile
    private var captureChangeTimeoutJob: Job? = null
    @Volatile
    private var photoTimeoutJob: Job? = null
    @Volatile
    private var startCaptureSeq = -1
    @Volatile
    private var stopCaptureSeq = -1
    // The take-picture command awaiting its answer, which comes once the photo is taken
    @Volatile
    private var takePictureSeq = -1
    @Volatile
    private var lastHeardAtMs = 0L
    @Volatile
    private var captureStatusQuerySeq = -1
    @Volatile
    private var statusOptionsQuerySeq = -1

    // Whether the status requests have gone out on this connection
    @Volatile
    private var statusRequested = false

    // Set by Wake Up: the next connection sends the wake-up packet before anything else
    @Volatile
    private var wakeOnNextConnect = false
    // Set while the Wake-Up set-up is open: its first step waits for the camera to be switched
    // on, which only a link shows, so a link to a camera that is off is held then
    @Volatile
    private var holdLinkWhileOff = false
    @Volatile
    private var wakeInProgress = false
    @Volatile
    private var startCommandAtMs: Long? = null
    // The photo and video sub-modes the camera was last seen in, so a switch to Photo or Video
    // goes back to that one (PRO Video stays PRO Video). 0 is single photo and normal video.
    private data class PendingSubModeWrite(
        val subModes: Go2BleProtocol.SubModes,
        val generation: Long
    )
    private val subModeLock = Any()
    private var subModeGeneration = 0L
    private var lastAppliedSubModeGeneration = 0L
    private var deferredConfirmedSubMode: PendingSubModeWrite? = null
    private val pendingSubModeWrites = HashMap<Int, PendingSubModeWrite>()
    @Volatile
    private var lastPhotoSubMode = 0
    @Volatile
    private var lastVideoSubMode = 0
    @Volatile
    internal var onPacketQueuedForTesting: ((ByteArray) -> Unit)? = null
    private val gpsQueue = ConcurrentLinkedQueue<ByteArray>()
    // Completed by the camera's answer to the wake-up authorization
    @Volatile
    private var wakeReply: CompletableDeferred<Boolean>? = null

    // Registering the wake-up ID. The check is matched to its reply by sequence number, a null
    // answer meaning the camera returned an error status; the outcome is the camera's result
    // notification, sent once its question about a new app has been answered on the camera.
    @Volatile
    private var registrationJob: Job? = null
    @Volatile
    private var authorizationCheckSeq = -1
    @Volatile
    private var authorizationCheckReply: CompletableDeferred<Go2BleProtocol.AuthorizationStatus?>? = null
    @Volatile
    private var authorizationOutcome: CompletableDeferred<Go2BleProtocol.AuthorizationResult>? = null
    @Volatile
    private var wakeJob: Job? = null

    // The request for the camera's Wi-Fi details, matched to its reply by sequence number
    @Volatile
    private var wifiInfoQuerySeq = -1
    @Volatile
    private var wifiInfoReply: CompletableDeferred<Go2BleProtocol.WifiInfo?>? = null
    @Volatile
    private var deviceIdentityQuerySeq = -1

    // When the camera first answered on the current link, null until it does, and whether the
    // link was opened by an automatic reconnection
    @Volatile
    private var answeringSinceMs: Long? = null
    @Volatile
    private var linkIsReconnect = false

    // GPS time of a moment on the elapsedRealtime clock, from the phone's fixes; null before any
    @Volatile
    private var gpsTimeAt: ((Long) -> Long?)? = null

    // Requests for the camera's clock in flight, by sequence number, with when each was sent,
    // and what their answers gave
    private data class ClockAnswer(val sentAtMs: Long, val answeredAtMs: Long, val utcSeconds: Long)
    private val clockQueries = ConcurrentHashMap<Int, Long>()
    private val clockAnswers = ConcurrentLinkedQueue<ClockAnswer>()
    @Volatile
    private var clockJob: Job? = null

    private val reconnectPolicy = ReconnectPolicy(EARLY_DROP_MS, MAX_AUTO_RECONNECTS)
    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var idleLinkJob: Job? = null

    // Both are used only by the write queue worker
    private val gpsPacer = GpsSendPacer(GPS_SEND_RECORDS_PER_SECOND, GPS_SEND_MAX_BURST)
    @Volatile
    private var gpsFlushRetry: Job? = null

    // Placed on the write queue to tell the worker that gpsQueue has records to send
    private val gpsReady = ByteArray(0)

    init {
        // Start write queue worker loop
        scope.launch {
            for (packet in writeChannel) {
                if (packet === gpsReady) {
                    flushGps()
                } else {
                    performGattWrite(packet)
                }
            }
        }
    }

    private fun nextSeq(): Int = synchronized(subModeLock) {
        val s = sequenceNumber
        sequenceNumber = if (sequenceNumber >= 254) 1 else sequenceNumber + 1
        pendingSubModeWrites.remove(s)
        s
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun canAdvertise(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    /**
     * Broadcasts the Apple iBeacon wake-up beacon ("ORBIT" + serial suffix) to wake camera from sleep.
     */
    /**
     * The authorization id sent to wake the camera, or null when none is stored. The camera
     * only wakes for an id it has authorized: one copied from a device it already wakes for, or
     * one it was asked about with [registerWakeUpId] and that was allowed on the camera.
     */
    fun getWakeAuthorizationId(): String? {
        val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_WAKE_AUTHORIZATION_ID, null)?.takeIf { Go2BleProtocol.isValidAuthorizationId(it) }
    }

    /** Stores the wake-up authorization id. Returns false when it is not a valid id. */
    fun saveWakeAuthorizationId(id: String): Boolean {
        if (!Go2BleProtocol.isValidAuthorizationId(id)) return false
        val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_WAKE_AUTHORIZATION_ID, id) }
        return true
    }

    fun getSavedSerialSuffix(): String {
        val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
        return prefs.getString("camera_serial_suffix", "") ?: ""
    }

    fun saveSerialSuffix(suffix: String) {
        if (suffix.isNotBlank()) {
            val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            prefs.edit { putString("camera_serial_suffix", suffix) }
        }
    }

    @SuppressLint("MissingPermission")
    fun startWakeUpBroadcast(serialSuffix: String? = null, onComplete: (() -> Unit)? = null) {
        val targetSuffix = serialSuffix?.takeIf { it.isNotBlank() } ?: getSavedSerialSuffix()
        if (targetSuffix.isBlank()) return

        if (!canAdvertise()) {
            _state.update { it.copy(lastError = "Open360 is not allowed to use Bluetooth. Allow Nearby Devices for it in the phone's settings.") }
            return
        }

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            _state.update { it.copy(lastError = "Bluetooth is off or not available") }
            return
        }

        val payload = ByteArray(24)
        payload[0] = 0x02
        payload[1] = 0x15
        payload[2] = 0x09
        payload[3] = 'O'.code.toByte()
        payload[4] = 'R'.code.toByte()
        payload[5] = 'B'.code.toByte()
        payload[6] = 'I'.code.toByte()
        payload[7] = 'T'.code.toByte()
        payload[8] = 0x09
        payload[9] = 0xff.toByte()
        payload[10] = 0x0f
        payload[11] = 0x00

        val suffixBytes = targetSuffix.take(6).toByteArray(Charsets.US_ASCII)
        for (i in suffixBytes.indices) {
            payload[12 + i] = suffixBytes[i]
        }
        payload[22] = 0xe4.toByte()
        payload[23] = 0x01

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // A sleeping camera's scanner is documented as expecting a connectable advertisement
            .setConnectable(true)
            .setTimeout(WAKE_BEACON_MS)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x004c, payload)
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "Wake-up beacon broadcast started")
                onComplete?.invoke()
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Wake-up beacon broadcast failed: $errorCode")
                _state.update { it.copy(lastError = "Could not send the wake-up signal (error $errorCode)") }
            }
        })
    }

    /**
     * Scans for the camera and connects via BLE GATT.
     */
    @SuppressLint("MissingPermission")
    fun startScanAndConnect(
        targetSerialSuffix: String? = null,
        preferredModel: Insta360CameraModel? = null
    ) {
        if (!canScan()) {
            _state.update { it.copy(lastError = "Open360 is not allowed to use Bluetooth. Allow Nearby Devices for it in the phone's settings.") }
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _state.update { it.copy(lastError = "Bluetooth is off or not available. Turn it on to find the camera.") }
            return
        }

        val status = _state.value.connectionStatus
        if (status == BleConnectionStatus.CONNECTING || status == BleConnectionStatus.CONNECTED) return
        stopScan()
        reconnectPolicy.reset()

        _state.update { it.copy(connectionStatus = BleConnectionStatus.SCANNING, lastError = null) }

        // Scan broadly in low-latency foreground mode and classify in BleScanMatcher so cameras
        // advertising BE80, CE80, AE00, 0x4F47 manufacturer data, or model/iBeacon prefixes are all found.
        val filters = emptyList<ScanFilter>()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (activeScanCallback !== this) return
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: ""
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                val mfgData = result.scanRecord?.manufacturerSpecificData
                val manufacturerIds = if (mfgData != null) {
                    List(mfgData.size()) { idx -> mfgData.keyAt(idx) }
                } else {
                    emptyList()
                }
                val match = BleScanMatcher.evaluate(
                    deviceName = name,
                    serviceUuids = serviceUuids,
                    manufacturerIds = manufacturerIds,
                    targetSerialSuffix = targetSerialSuffix
                )

                if (match.isMatch) {
                    Log.i(TAG, "Found target camera (${match.reason}): $name (${device.address})")
                    stopScan()

                    connectGatt(device, name, discoveredModel = match.model ?: preferredModel)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed: $errorCode")
                _state.update {
                    it.copy(
                        connectionStatus = BleConnectionStatus.DISCONNECTED,
                        lastError = "Could not look for the camera (error $errorCode)"
                    )
                }
            }
        }

        try {
            // Also add broad scan if filters are empty
            activeScanCallback = scanCallback
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan", e)
            activeScanCallback = null
            _state.update { it.copy(connectionStatus = BleConnectionStatus.DISCONNECTED, lastError = e.message) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = activeScanCallback ?: return
        activeScanCallback = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {}
    }

    /**
     * Sends the keep-alive packet on a timer while connected, once the camera has answered on the
     * link. A camera that is off holds the link without it and announces itself when switched on.
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                // Nothing else is sent while a wake-up attempt is under way
                if (wakeInProgress || answeringSinceMs == null) continue
                queuePacket(Go2BleProtocol.buildKeepAlivePacket())
                if (_state.value.isCameraAwake && SystemClock.elapsedRealtime() - lastHeardAtMs > CAMERA_SILENT_MS) {
                    Log.w(TAG, "Camera has stopped answering")
                    clockJob?.cancel()
                    captureChangeTimeoutJob?.cancel()
                    markRecordingStop()
                    forgetPhotoInProgress()
                    statusRequested = false
                    answeringSinceMs = null
                    synchronized(subModeLock) {
                        forgetPendingReplies()
                        _state.update {
                            it.copy(
                                isCameraAwake = false,
                                isRecording = false,
                                isCaptureStarting = false,
                                isCaptureStopping = false,
                                isTakingPhoto = false,
                                batteryPercent = null,
                                isCharging = false,
                                cameraClockOffsetMs = null,
                                isCameraWifiReady = false,
                                details = CameraDetails()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun ByteArray.toHex(max: Int = 48): String {
        val shown = take(max).joinToString("") { "%02x".format(it) }
        return if (size > max) "$shown... ($size bytes)" else shown
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(
        device: BluetoothDevice,
        discoveredName: String,
        isReconnect: Boolean = false,
        discoveredModel: Insta360CameraModel? = null
    ) {
        answeringSinceMs = null
        linkIsReconnect = isReconnect
        val safeName = discoveredName.ifEmpty { device.name ?: "Insta360 Camera" }
        val tokens = safeName.trim().split("\\s+".toRegex())
        val candidateSuffix = tokens.lastOrNull()
        if (candidateSuffix != null && candidateSuffix.length == 6 && candidateSuffix.all { it.isLetterOrDigit() }) {
            saveSerialSuffix(candidateSuffix)
        }

        _state.update {
            it.copy(
                connectionStatus = BleConnectionStatus.CONNECTING,
                isLinkIdle = false,
                deviceName = safeName,
                deviceAddress = device.address,
                cameraModel = discoveredModel ?: it.cameraModel ?: Insta360CameraModel.resolveByIdentity(deviceName = safeName)
            )
        }

        val oldGatt = activeGatt
        activeGatt = null
        oldGatt?.close()
        activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Whether the link that has just dropped is to be opened again by itself, as [ReconnectPolicy]
     * decides. Anything it does not reconnect ends as it always has, disconnected.
     */
    private fun shouldReconnect(): Boolean {
        val answeredAt = answeringSinceMs
        val wasReconnect = linkIsReconnect
        answeringSinceMs = null
        linkIsReconnect = false
        val now = SystemClock.elapsedRealtime()
        if (answeredAt != null) {
            Log.i(TAG, "Link dropped ${now - answeredAt} ms after the camera started answering, " +
                "${now - lastHeardAtMs} ms after it was last heard")
        }
        return when (reconnectPolicy.onDrop(now, answeredAt, wasReconnect)) {
            ReconnectPolicy.Action.RECONNECT -> true
            ReconnectPolicy.Action.GIVE_UP -> {
                Log.w(TAG, "Not reconnecting: $MAX_AUTO_RECONNECTS reconnections in a row did not hold")
                false
            }
            ReconnectPolicy.Action.STAY_DISCONNECTED -> false
        }
    }

    /**
     * Lets go of the link when the camera has not answered on it within a few seconds and no
     * wake-up is under way: the camera is switched off. Its Bluetooth accepts a link while it is
     * off, but holding one is no use until it is switched on, and a link held while the camera
     * powered up in its Action Pod is the likely trigger for the pod losing the camera. So
     * opening the app with the camera off, or the link opened again after it was switched off,
     * ends here, and Connect (which wakes it) links again. A camera switched on with its own
     * button is not noticed until then.
     */
    private fun letGoIfSilent(gatt: BluetoothGatt) {
        idleLinkJob?.cancel()
        idleLinkJob = scope.launch {
            delay(SILENT_LINK_MS)
            if (answeringSinceMs != null || wakeInProgress || wakeOnNextConnect || activeGatt !== gatt) {
                return@launch
            }
            if (holdLinkWhileOff) {
                _state.update { it.copy(isLinkIdle = true) }
                return@launch
            }
            Log.i(TAG, "The camera is not answering: it is off, letting go of the link")
            disconnect()
        }
    }

    /** Opens the link to [device] again, a moment after it dropped. */
    private fun reconnect(device: BluetoothDevice) {
        Log.i(TAG, "Reconnecting to the camera, ${reconnectPolicy.reconnectsLeft} more tries left after this one")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Lets the Bluetooth stack finish with the old link first
            delay(RECONNECT_DELAY_MS)
            connectGatt(device, _state.value.deviceName ?: "", isReconnect = true)
        }
    }

    private fun drainWriteQueue() {
        while (writeChannel.tryReceive().isSuccess) {
            // Discard packets queued for a link that has ended
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (gatt !== activeGatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(connectionStatus = BleConnectionStatus.CONNECTING) }
                // Request MTU to support full GPS and protobuf packets
                gatt.requestMtu(247)
            } else {
                Log.w(TAG, "GATT disconnected or failed, status=$status, newState=$newState")
                activeGatt = null
                gatt.close()
                writeCharacteristic = null
                attMtu = 23
                idleLinkJob?.cancel()
                stopKeepAlive()
                captureChangeTimeoutJob?.cancel()
                gpsFlushRetry?.cancel()
                pendingWriteAck?.complete(false)
                drainWriteQueue()
                markRecordingStop()
                forgetPhotoInProgress()
                registrationJob?.cancel()
                clockJob?.cancel()
                val reconnect = shouldReconnect()
                if (reconnect) reconnect(gatt.device)
                synchronized(subModeLock) {
                    forgetPendingReplies()
                    _state.update {
                        it.copy(
                            // A reconnection is about to be tried; Disconnected would only flash up
                            connectionStatus = if (reconnect) BleConnectionStatus.CONNECTING else BleConnectionStatus.DISCONNECTED,
                            isReconnecting = reconnect,
                            isWaking = false,
                            isLinkIdle = false,
                            isCameraAwake = false,
                            isRecording = false,
                            isCaptureStarting = false,
                            isCaptureStopping = false,
                            isTakingPhoto = false,
                            batteryPercent = null,
                            isCharging = false,
                            cameraClockOffsetMs = null,
                            cameraModel = null,
                            reportedDeviceType = null,
                            reportedSerialNumber = null,
                            isCameraWifiReady = false,
                            details = CameraDetails(),
                            wakeUpRegistration = it.wakeUpRegistration.afterLinkDown()
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== activeGatt) return
            Log.i(TAG, "MTU changed to $mtu, status: $status. Discovering services...")
            if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== activeGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _state.update { it.copy(lastError = "Could not finish connecting to the camera (error $status)") }
                disconnect()
                return
            }

            Log.i(TAG, "Discovered ${gatt.services.size} services")
            statusRequested = false
            for (service in gatt.services) {
                Log.d(TAG, "Service ${service.uuid}")
                for (characteristic in service.characteristics) {
                    Log.d(TAG, "  characteristic ${characteristic.uuid} properties=0x${Integer.toHexString(characteristic.properties)}")
                }
            }
            val be80 = gatt.getService(SERVICE_BE80)
            val ce80 = gatt.getService(SERVICE_CE80)
            val ae00 = gatt.getService(BleScanMatcher.SERVICE_AE00)

            // Primary: BE81, fallback CE81 (legacy/remote), fallback AE01 (older accessory/camera service)
            writeCharacteristic = be80?.getCharacteristic(WRITE_BE81)
                ?: ce80?.getCharacteristic(WRITE_CE81)
                ?: ae00?.getCharacteristic(BleScanMatcher.WRITE_AE01)

            // Notifications: subscribe to BE82, CE82, or AE02
            val notifyChar = be80?.getCharacteristic(NOTIFY_BE82)
                ?: ce80?.getCharacteristic(NOTIFY_CE82)
                ?: ae00?.getCharacteristic(BleScanMatcher.NOTIFY_AE02)

            if (writeCharacteristic == null && notifyChar == null) {
                Log.w(TAG, "Camera control service not found")
                _state.update { it.copy(lastError = "Camera control service not found") }
                disconnect()
                return
            }

            if (notifyChar != null) {
                gatt.setCharacteristicNotification(notifyChar, true)
                val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    Log.i(TAG, "Subscribed to notifications on ${notifyChar.uuid}")
                }
            }

            _state.update { it.copy(connectionStatus = BleConnectionStatus.CONNECTED, isReconnecting = false) }
            startKeepAlive()
            letGoIfSilent(gatt)
            Log.i(TAG, "Camera BLE successfully connected. Ready for commands.")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== activeGatt) return
            pendingWriteAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== activeGatt) return
            val channel = descriptor.characteristic?.uuid
            Log.i(TAG, "Subscription to $channel finished, status=$status")
            if (channel == NOTIFY_BE82 || channel == NOTIFY_CE82 || channel == BleScanMatcher.NOTIFY_AE02) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Subscription complete: channel is open for writes. Nothing is asked until the
                    // camera answers (see requestStatusOnce), which one that is on does at once.
                    if (wakeOnNextConnect) {
                        wakeOnNextConnect = false
                        wakeCamera()
                    }
                } else {
                    wakeOnNextConnect = false
                    _state.update { it.copy(isWaking = false) }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== activeGatt) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { onNotification(characteristic, it) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== activeGatt) return
            onNotification(characteristic, value)
        }
    }

    private fun onNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == NOTIFY_BE82 ||
            characteristic.uuid == NOTIFY_CE82 ||
            characteristic.uuid == BleScanMatcher.NOTIFY_AE02
        ) {
            handleIncomingNotification(value)
        } else {
            // Nothing else is subscribed to; logged in case the camera sends on another channel
            Log.i(TAG, "Notification on ${characteristic.uuid}: ${value.toHex(96)}")
        }
    }

    /**
     * Switches on a camera that is connected but powered off, the way the Insta360 app was seen
     * to: the authorization id is written before anything else on a fresh link, the camera's
     * Bluetooth chip answers whether it accepts it, and about two seconds later the camera is on.
     */
    private fun wakeCamera() {
        if (writeCharacteristic == null) {
            _state.update { it.copy(isWaking = false) }
            return
        }
        wakeJob?.cancel()
        wakeJob = scope.launch {
            val attempt = coroutineContext[Job]
            wakeInProgress = true
            try {
                // A camera that is on says so within a second of the link opening
                if (answersWithin(1000)) {
                    Log.i(TAG, "Wake: the camera is already on")
                    return@launch
                }
                val wakeStrategy = _state.value.resolvedModel?.wakeStrategy ?: BleWakeStrategy.BLE_AUTH_ID
                if (wakeStrategy == BleWakeStrategy.GO2_FIXED_PACKET) {
                    Log.i(TAG, "Wake: sending GO 2 fixed BLE wake packet")
                    queuePacket(Go2BleProtocol.buildGo2WakePacket())
                    if (answersWithin(WAKE_SWITCH_ON_MS)) {
                        Log.i(TAG, "Wake: GO 2 switched on")
                    } else {
                        Log.w(TAG, "Wake: GO 2 did not switch on after fixed wake packet")
                    }
                    return@launch
                }

                val id = getWakeAuthorizationId()
                if (id == null) {
                    if (wakeStrategy == BleWakeStrategy.IBEACON_ORBIT) {
                        Log.i(TAG, "Wake: falling back to iBeacon/ORBIT wake broadcast")
                        startWakeUpBroadcast(null)
                        return@launch
                    }
                    Log.w(TAG, "Wake: no authorization id has been set")
                    _state.update { it.copy(lastError = "Wake Up has not been set up for this camera") }
                    return@launch
                }

                val reply = CompletableDeferred<Boolean>()
                wakeReply = reply
                Log.i(TAG, "Wake: sending the authorization")
                queuePacket(Go2BleProtocol.buildWakeAuthorizationPacket(id))
                when (withTimeoutOrNull(WAKE_REPLY_MS) { reply.await() }) {
                    null -> {
                        if (wakeStrategy == BleWakeStrategy.IBEACON_ORBIT) {
                            Log.i(TAG, "Wake: auth timed out on iBeacon model, starting iBeacon/ORBIT broadcast")
                            startWakeUpBroadcast(null)
                            return@launch
                        }
                        Log.w(TAG, "Wake: the camera did not answer the authorization")
                        return@launch
                    }
                    false -> {
                        _state.update { it.copy(lastError = "The camera did not allow Wake Up. Set it up again in Settings.") }
                        return@launch
                    }
                    true -> Unit
                }

                if (answersWithin(WAKE_SWITCH_ON_MS)) {
                    Log.i(TAG, "Wake: the camera is on")
                } else {
                    Log.w(TAG, "Wake: the camera accepted the authorization but did not switch on")
                }
            } finally {
                wakeReply = null
                wakeInProgress = false
                // A newer attempt, started since this one was cancelled, is still under way
                if (wakeJob === attempt) _state.update { it.copy(isWaking = false) }
                // A camera that came on was asked for its status as it answered; one that did not is let go
                activeGatt?.let { letGoIfSilent(it) }
            }
        }
    }

    /**
     * Gets the camera to accept [id] for waking it, and reports where that stands in
     * [BleCameraState.wakeUpRegistration]. The camera is asked whether it has authorized the id,
     * as the Insta360 app does on every connection. For an id it does not know it answers
     * unauthorized and asks on its own screen whether to connect to a new app; once that is
     * answered there it sends its result. With the camera off there is nothing to ask.
     */
    fun registerWakeUpId(id: String) {
        registrationJob?.cancel()
        if (!cameraIsOn()) {
            setRegistration(WakeUpRegistration.NONE)
            return
        }
        // Said before the work starts, so that a caller looking straight after sees it under way
        setRegistration(WakeUpRegistration.CHECKING)
        registrationJob = scope.launch {
            // Listened for from before the check, so that a quick answer on the camera is not missed
            val outcome = CompletableDeferred<Go2BleProtocol.AuthorizationResult>()
            authorizationOutcome = outcome
            try {
                val status = checkAuthorization(id)
                if (status != Go2BleProtocol.AuthorizationStatus.UNAUTHORIZED) {
                    setRegistration(
                        when (status) {
                            Go2BleProtocol.AuthorizationStatus.AUTHORIZED -> WakeUpRegistration.AUTHORIZED
                            Go2BleProtocol.AuthorizationStatus.BUSY -> WakeUpRegistration.CAMERA_BUSY
                            else -> WakeUpRegistration.NO_ANSWER
                        }
                    )
                    return@launch
                }

                setRegistration(WakeUpRegistration.CONFIRM_ON_CAMERA)
                val result = withTimeoutOrNull(AUTHORIZATION_CONFIRM_MS) { outcome.await() }
                setRegistration(
                    when (result) {
                        Go2BleProtocol.AuthorizationResult.SUCCESS -> WakeUpRegistration.AUTHORIZED
                        Go2BleProtocol.AuthorizationResult.REJECTED -> WakeUpRegistration.REJECTED
                        Go2BleProtocol.AuthorizationResult.BUSY -> WakeUpRegistration.CAMERA_BUSY
                        Go2BleProtocol.AuthorizationResult.TIMED_OUT, null -> WakeUpRegistration.TIMED_OUT
                    }
                )
            } finally {
                if (authorizationOutcome === outcome) authorizationOutcome = null
            }
        }
    }

    /** Whether the set-up for Wake Up has been offered on this phone before. */
    fun wasWakeUpSetupOffered(): Boolean =
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE).getBoolean(PREF_WAKE_SETUP_OFFERED, false)

    fun markWakeUpSetupOffered() {
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean(PREF_WAKE_SETUP_OFFERED, true) }
    }

    /** Whether the card that leads to the set-up for Wake Up has been dismissed. */
    fun isWakeUpSetupCardDismissed(): Boolean =
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_WAKE_SETUP_CARD_DISMISSED, false)

    fun setWakeUpSetupCardDismissed(dismissed: Boolean) {
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean(PREF_WAKE_SETUP_CARD_DISMISSED, dismissed) }
    }

    /**
     * Sends the camera a command by its code, with a payload given in hex, for trying out
     * commands from a debug build. The camera's answer shows in the log under its sequence number.
     */
    fun sendCommandForTesting(commandCode: Int, payloadHex: String) {
        val clean = payloadHex.filterNot { it.isWhitespace() }
        if (clean.length % 2 != 0) {
            Log.w(TAG, "Invalid test payload hex (odd length): $payloadHex")
            return
        }
        val payload = ByteArray(clean.length / 2)
        for (i in payload.indices) {
            val byte = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)
            if (byte == null) {
                Log.w(TAG, "Invalid test payload hex: $payloadHex")
                return
            }
            payload[i] = byte.toByte()
        }
        val seq = nextSeq()
        Log.i(TAG, "Test command 0x${Integer.toHexString(commandCode)}, seq=$seq, payload=$clean")
        queuePacket(Go2BleProtocol.encodeMessagePacket(Go2BleProtocol.encodeInnerMessage(commandCode, seq, payload)))
    }

    private fun cameraIsOn(): Boolean {
        val current = _state.value
        return current.connectionStatus == BleConnectionStatus.CONNECTED && current.isCameraAwake
    }

    private fun setRegistration(registration: WakeUpRegistration) {
        Log.i(TAG, "Wake-up ID registration: $registration")
        _state.update { it.copy(wakeUpRegistration = registration) }
    }

    /** Asks the camera whether it has authorized [id]. Null when it gives no usable answer. */
    private suspend fun checkAuthorization(id: String): Go2BleProtocol.AuthorizationStatus? {
        val reply = CompletableDeferred<Go2BleProtocol.AuthorizationStatus?>()
        authorizationCheckReply = reply
        val seq = nextSeq()
        authorizationCheckSeq = seq
        queuePacket(
            Go2BleProtocol.encodeMessagePacket(
                Go2BleProtocol.encodeInnerMessage(
                    Go2BleProtocol.CMD_CHECK_AUTHORIZATION,
                    seq,
                    Go2BleProtocol.buildCheckAuthorizationPayload(id)
                )
            )
        )
        try {
            return withTimeoutOrNull(AUTHORIZATION_REPLY_MS) { reply.await() }
        } finally {
            // Also when cancelled, and only its own: a newer check may have started meanwhile
            if (authorizationCheckReply === reply) authorizationCheckReply = null
            if (authorizationCheckSeq == seq) authorizationCheckSeq = -1
        }
    }

    /**
     * Asks the camera for its Wi-Fi name and password, which it gives over Bluetooth, and keeps
     * them for when it is not connected. Null when the camera is not on or gives no usable
     * answer. The camera switches its Wi-Fi on by itself a few seconds after it comes on.
     */
    suspend fun requestWifiInfo(): Go2BleProtocol.WifiInfo? {
        if (!cameraIsOn()) return null
        val reply = CompletableDeferred<Go2BleProtocol.WifiInfo?>()
        wifiInfoReply = reply
        val seq = nextSeq()
        wifiInfoQuerySeq = seq
        queuePacket(
            Go2BleProtocol.encodeMessagePacket(
                Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_GET_OPTIONS, seq, Go2BleProtocol.buildWifiInfoRequest())
            )
        )
        val info = try {
            withTimeoutOrNull(WIFI_INFO_REPLY_MS) { reply.await() }
        } finally {
            if (wifiInfoReply === reply) wifiInfoReply = null
            if (wifiInfoQuerySeq == seq) wifiInfoQuerySeq = -1
        }
        if (info == null) {
            Log.w(TAG, "The camera gave no Wi-Fi details")
            return null
        }
        Log.i(TAG, "Camera Wi-Fi network: ${info.ssid}")
        _state.update { it.copy(isCameraWifiReady = true) }
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE).edit {
            putString(PREF_CAMERA_WIFI_SSID, info.ssid)
            putString(PREF_CAMERA_WIFI_PASSWORD, info.password)
        }
        return info
    }

    /** The camera's Wi-Fi name and password from its last answer, or null if it has not given them. */
    fun getSavedWifiInfo(): Go2BleProtocol.WifiInfo? {
        val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
        val ssid = prefs.getString(PREF_CAMERA_WIFI_SSID, null) ?: return null
        val password = prefs.getString(PREF_CAMERA_WIFI_PASSWORD, null) ?: return null
        return Go2BleProtocol.WifiInfo(ssid, password)
    }

    /**
     * Wakes a camera that is off by opening a fresh link to it, on which the wake-up
     * authorization is the first thing sent.
     */
    fun wakeByConnecting() {
        if (_state.value.connectionStatus != BleConnectionStatus.DISCONNECTED) {
            disconnect()
        }
        wakeOnNextConnect = true
        startScanAndConnect()
        // Nothing was started when Bluetooth is off or not allowed
        val started = _state.value.connectionStatus != BleConnectionStatus.DISCONNECTED
        wakeOnNextConnect = started
        _state.update { it.copy(isWaking = started) }
    }

    /**
     * Holds a link to a camera that is off, as the Wake-Up set-up needs to see it switched on, or
     * goes back to letting go of one; a link held for the set-up is let go once it closes, if the
     * camera is still off.
     */
    fun setHoldLinkWhileOff(hold: Boolean) {
        holdLinkWhileOff = hold
        val gatt = activeGatt ?: return
        if (hold) {
            if (answeringSinceMs == null && !_state.value.isLinkIdle) letGoIfSilent(gatt)
        } else {
            if (_state.value.isLinkIdle && answeringSinceMs == null && !wakeInProgress) {
                disconnect()
            } else {
                letGoIfSilent(gatt)
            }
        }
    }

    /**
     * Sends the status requests once per connection, once the camera is answering. A camera that
     * is off holds requests, even after the link ends, and answers them after its next wake-up
     * over Bluetooth under their old sequence numbers, which a request then may have again.
     */
    private fun requestStatusOnce() {
        if (statusRequested) return
        statusRequested = true
        queryCaptureStatus()
        queryStatusOptions()
        queryDeviceIdentity()
    }

    /** Gives the GPS time of a moment on the elapsedRealtime clock, null before any fix. */
    fun setGpsTimeSource(source: (Long) -> Long?) {
        gpsTimeAt = source
    }

    /**
     * Reads the camera's clock against GPS time, sets it when it is further than
     * [CLOCK_SET_THRESHOLD_MS] out and nothing is recording, and keeps how far ahead it is
     * ([BleCameraState.cameraClockOffsetMs]) for stamping the GPS records.
     */
    private fun scheduleClockSync() {
        clockJob?.cancel()
        clockJob = scope.launch {
            delay(CLOCK_SYNC_DELAY_MS)
            val giveUpAt = SystemClock.elapsedRealtime() + CLOCK_WAIT_FOR_GPS_MS
            while (gpsTimeAt?.invoke(SystemClock.elapsedRealtime()) == null) {
                if (!cameraIsOn() || SystemClock.elapsedRealtime() > giveUpAt) {
                    Log.w(TAG, "Camera clock not read: no GPS time")
                    return@launch
                }
                delay(2000)
            }
            var offset = readClock()
            if (offset == null && cameraIsOn() && !isCapturing()) {
                delay(5000L)
                if (cameraIsOn() && !isCapturing()) offset = readClock()
            }
            val measured = offset ?: return@launch
            if (abs(measured.ms) > CLOCK_SET_THRESHOLD_MS && !isCapturing() && setClock(measured)) {
                delay(CLOCK_SET_SETTLE_MS)
                val updated = readClock() ?: return@launch
                _state.update { it.copy(cameraClockOffsetMs = updated.ms) }
            } else {
                _state.update { it.copy(cameraClockOffsetMs = measured.ms) }
            }
        }
    }

    /** Whether a recording is starting or running, when the camera's clock must not be changed. */
    private fun isCapturing(): Boolean =
        _state.value.let { it.isRecording || it.isCaptureStarting || it.gpsStreamStartAtMs != null }

    /**
     * Asks for the camera's clock [CLOCK_READINGS] times, across its ticks, and works out how far
     * it is ahead of GPS time. Null when the answers do not pin it down.
     */
    private suspend fun readClock(): CameraClock.Offset? {
        clockQueries.clear()
        clockAnswers.clear()
        repeat(CLOCK_READINGS) {
            if (!cameraIsOn()) return null
            val seq = nextSeq()
            clockQueries[seq] = SystemClock.elapsedRealtime()
            queuePacket(
                Go2BleProtocol.encodeMessagePacket(
                    Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_GET_OPTIONS, seq, Go2BleProtocol.buildClockRequest())
                )
            )
            delay(CLOCK_READING_INTERVAL_MS)
        }
        delay(CLOCK_LAST_ANSWER_MS)
        clockQueries.clear()
        val toGps = gpsTimeAt ?: return null
        val readings = clockAnswers.mapNotNull { answer ->
            val asked = toGps(answer.sentAtMs) ?: return@mapNotNull null
            val answered = toGps(answer.answeredAtMs) ?: return@mapNotNull null
            CameraClock.Reading(asked, answered, answer.utcSeconds)
        }
        val offset = CameraClock.offset(readings)
        if (offset == null || offset.uncertaintyMs > CLOCK_MAX_UNCERTAINTY_MS) {
            Log.w(TAG, "Camera clock not read: ${readings.size} answers, " +
                (offset?.let { "${it.lowMs} to ${it.highMs} ms" } ?: "not agreeing"))
            return null
        }
        Log.i(TAG, "Camera clock is ${offset.ms} ms ahead of GPS time (${offset.lowMs} to ${offset.highMs} ms, ${readings.size} answers)")
        return offset
    }

    /**
     * Sets the camera's clock as near GPS time as its own fraction of a second allows
     * (CameraClock.planSet). False when it was not sent, as a recording started meanwhile.
     */
    private suspend fun setClock(measured: CameraClock.Offset): Boolean {
        val toGps = gpsTimeAt ?: return false
        val nowUtc = toGps(SystemClock.elapsedRealtime()) ?: return false
        val plan = CameraClock.planSet(nowUtc, measured.ms, CLOCK_SET_LEAD_MS)
        delay(plan.handleAtUtcMs - nowUtc - CLOCK_SET_WRITE_AHEAD_MS)
        if (isCapturing() || !cameraIsOn()) return false
        val zone = TimeZone.getDefault()
        val payload = Go2BleProtocol.buildSetClockPayload(plan.utcSeconds, zone.getOffset(System.currentTimeMillis()) / 1000, zone.id)
        queuePacket(
            Go2BleProtocol.encodeMessagePacket(Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_SET_OPTIONS, nextSeq(), payload))
        )
        Log.i(TAG, "Setting the camera's clock to ${plan.utcSeconds} UTC, to be ${plan.expectedOffsetMs} ms ahead of GPS time")
        return true
    }

    private suspend fun answersWithin(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it.isCameraAwake } } != null

    @androidx.annotation.VisibleForTesting
    fun setConnectionStatusForTesting(status: BleConnectionStatus, isCameraAwake: Boolean = true) {
        synchronized(subModeLock) {
            _state.update { it.copy(connectionStatus = status, isCameraAwake = isCameraAwake) }
        }
    }

    internal fun handleIncomingNotification(raw: ByteArray) {
        Log.v(TAG, "RX ${raw.toHex()}")

        // The camera's Bluetooth chip answers the wake-up authorization while the camera is off
        Go2BleProtocol.parseWakeAuthorizationReply(raw)?.let { accepted ->
            Log.i(TAG, if (accepted) "The camera accepted the wake-up authorization" else "The camera refused the wake-up authorization")
            wakeReply?.complete(accepted)
            return
        }

        val decoded = Go2BleProtocol.decodePacket(raw)
        if (decoded == null) {
            // Check for raw sync pattern: "AB BA"
            if (raw.size >= 4 && raw[0] == 0xAB.toByte() && raw[1] == 0xBA.toByte()) {
                Log.i(TAG, "Received raw AB BA sync pattern, sending 7-zero sync response")
                queuePacket(Go2BleProtocol.buildSyncResponsePacket())
            }
            return
        }

        // Anything valid from the camera, a keep-alive answer included, shows it is switched on
        lastHeardAtMs = SystemClock.elapsedRealtime()
        if (answeringSinceMs == null) {
            answeringSinceMs = lastHeardAtMs
            _state.update { it.copy(isLinkIdle = false) }
        }
        if (!_state.value.isCameraAwake) {
            Log.i(TAG, "Camera is answering")
            _state.update { it.copy(isCameraAwake = true) }
            requestStatusOnce()
            scheduleClockSync()
            // Shows whether Wake Up will work with this camera, and gets it to ask if not
            if (registrationJob?.isActive != true) getWakeAuthorizationId()?.let { registerWakeUpId(it) }
        }

        if (decoded.isSync) {
            Log.i(TAG, "Received camera sync packet (${decoded.innerData.size} bytes), replying with sync response")
            queuePacket(Go2BleProtocol.buildSyncResponsePacket())
            return
        }

        if (decoded.isMessage) {
            val inner = Go2BleProtocol.decodeInnerMessage(decoded.innerData) ?: return
            val hexPayload = inner.payload.joinToString("") { "%02x".format(it) }
            // 200 OK responses arrive for every GPS upload, ten times a second
            val level = if (inner.commandCode == 0xC8) Log.VERBOSE else Log.DEBUG
            Log.println(level, TAG, "Received camera message: code=0x${Integer.toHexString(inner.commandCode)}, seq=${inner.sequence}, size=${inner.payload.size}, payload=$hexPayload")

            when (inner.commandCode) {
                Go2BleProtocol.NOTIF_BATTERY_UPDATE -> {
                    Go2BleProtocol.parseBatteryUpdateNotification(inner.payload)?.let { battery ->
                        _state.update {
                            it.copy(batteryPercent = battery.levelPercent, isCharging = battery.onExternalPower)
                        }
                        // The low battery warning ends once the camera is charging or has charge again
                        if (battery.onExternalPower || battery.levelPercent > LOW_BATTERY_CLEARED_PERCENT) {
                            updateDetails { it.copy(batteryLow = false) }
                        }
                        Log.i(TAG, "Battery update: ${battery.levelPercent}%, external power=${battery.onExternalPower}")
                    }
                }

                Go2BleProtocol.NOTIF_BATTERY_LOW -> {
                    Log.w(TAG, "Camera reports its battery is low")
                    updateDetails { it.copy(batteryLow = true) }
                }

                Go2BleProtocol.NOTIF_STORAGE_FULL -> {
                    Log.w(TAG, "Camera reports its storage is full")
                    updateDetails { it.copy(cardState = Go2BleProtocol.CardState.FULL) }
                }

                Go2BleProtocol.NOTIF_TEMPERATURE -> {
                    val high = Go2BleProtocol.parseTemperatureIsHigh(inner.payload)
                    if (high) Log.w(TAG, "Camera reports it is hot")
                    updateDetails { it.copy(temperatureHigh = high) }
                }

                Go2BleProtocol.NOTIF_CAM_WIFI_START -> {
                    Log.i(TAG, "Camera Wi-Fi AP started (0x2017)")
                    _state.update { it.copy(isCameraWifiReady = true) }
                }

                Go2BleProtocol.NOTIF_SHUTDOWN -> {
                    // Kept as the error line, which stays on screen after the link drops
                    val reason = when (Go2BleProtocol.parseShutdown(inner.payload)) {
                        Go2BleProtocol.ShutdownReason.OVERHEATED -> "Camera shut down: too hot"
                        Go2BleProtocol.ShutdownReason.BATTERY_EMPTY -> "Camera shut down: battery empty"
                    }
                    Log.w(TAG, reason)
                    _state.update { it.copy(lastError = reason) }
                }

                Go2BleProtocol.NOTIF_CURRENT_CAPTURE_STATUS -> {
                    parseCaptureStatus(inner.payload)
                }

                Go2BleProtocol.NOTIF_AUTHORIZATION_RESULT -> {
                    val result = Go2BleProtocol.parseAuthorizationResult(inner.payload)
                    Log.i(TAG, "Authorization result from the camera: $result ($hexPayload)")
                    val waiting = authorizationOutcome
                    if (waiting != null) {
                        waiting.complete(result)
                    } else if (result == Go2BleProtocol.AuthorizationResult.SUCCESS) {
                        // Allowed on the camera after the app had stopped waiting
                        setRegistration(WakeUpRegistration.AUTHORIZED)
                    }
                }

                Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT -> {
                    Go2BleProtocol.parseCaptureTimeLeft(inner.payload)?.let { seconds ->
                        updateDetails { it.copy(captureSecondsLeft = seconds) }
                    }
                }

                Go2BleProtocol.NOTIF_STORAGE_UPDATE -> {
                    val cardState = Go2BleProtocol.parseStorageUpdate(inner.payload)
                    updateDetails { it.copy(cardState = cardState) }
                }

                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE -> {
                    Go2BleProtocol.parseSubModeNotification(inner.payload)?.let { subModes ->
                        Log.i(TAG, "Camera mode: photo sub-mode ${subModes.photo}, video sub-mode ${subModes.video}")
                        onSubModes(subModes)
                    }
                }

                Go2BleProtocol.NOTIF_CHARGE_BOX_STATUS -> {
                    Go2BleProtocol.parseChargeBoxStatus(inner.payload)?.let { pod ->
                        updateDetails { it.copy(pod = pod) }
                    }
                }

                Go2BleProtocol.NOTIF_PERIODIC_STATUS -> {
                    when (Go2BleProtocol.parseCaptureStep(inner.payload)) {
                        Go2BleProtocol.CAPTURE_STEP_VIDEO_STARTING -> {
                            val startedByApp = startCommandAtMs?.let {
                                SystemClock.elapsedRealtime() - it <= START_COMMAND_NOTICE_WINDOW_MS
                            } == true
                            // A recording started from the app is already streamed from its command
                            if (!startedByApp || _state.value.gpsStreamStartAtMs == null) markRecordingStart(0L)
                            // The notice names the mode before the capture status does
                            Go2BleProtocol.parseCaptureStepState(inner.payload)
                                ?.takeIf { Go2BleProtocol.isRecordingCaptureState(it) }
                                ?.let { mode -> updateDetails { it.copy(recordingState = mode) } }
                        }
                        Go2BleProtocol.CAPTURE_STEP_STOPPING, Go2BleProtocol.CAPTURE_STEP_DONE ->
                            if (Go2BleProtocol.captureStepEndsRecording(inner.payload)) markRecordingStop()
                    }
                }

                0xC8 -> { // 200 OK Response
                    Log.v(TAG, "Camera returned 200 OK for seq ${inner.sequence}")
                    // Replies carry the sequence number of the request they answer
                    if (inner.sequence == captureStatusQuerySeq) {
                        captureStatusQuerySeq = -1
                        Go2BleProtocol.parseCaptureStatusReply(inner.payload)?.let { onCaptureStatusReply(it) }
                    }
                    if (inner.sequence == statusOptionsQuerySeq) {
                        statusOptionsQuerySeq = -1
                        Go2BleProtocol.parseStatusOptionsReply(inner.payload)?.let { onStatusOptionsReply(it) }
                    }
                    if (inner.sequence == authorizationCheckSeq) {
                        Log.i(TAG, "Authorization check reply: $hexPayload")
                        val status = Go2BleProtocol.parseCheckAuthorizationReply(inner.payload)
                        if (status != null) {
                            authorizationCheckSeq = -1
                            authorizationCheckReply?.complete(status)
                        } else {
                            // Another request's reply under the check's number; its own may still come
                            Log.w(TAG, "Not an answer to the authorization check, still waiting for one")
                        }
                    }
                    if (inner.sequence == wifiInfoQuerySeq) {
                        wifiInfoQuerySeq = -1
                        wifiInfoReply?.complete(Go2BleProtocol.parseWifiInfoReply(inner.payload))
                    }
                    if (inner.sequence == deviceIdentityQuerySeq) {
                        deviceIdentityQuerySeq = -1
                        Go2BleProtocol.parseDeviceIdentityReply(inner.payload)?.let { identity ->
                            _state.update { cur ->
                                val model = Insta360CameraModel.resolveByIdentity(
                                    deviceType = identity.cameraType,
                                    deviceName = cur.deviceName,
                                    serialNumber = identity.serialNumber
                                ) ?: cur.cameraModel
                                cur.copy(
                                    reportedDeviceType = identity.cameraType ?: cur.reportedDeviceType,
                                    reportedSerialNumber = identity.serialNumber ?: cur.reportedSerialNumber,
                                    cameraModel = model
                                )
                            }
                        }
                    }
                    clockQueries.remove(inner.sequence)?.let { sentAt ->
                        Go2BleProtocol.parseClockReply(inner.payload)?.let { seconds ->
                            clockAnswers.add(ClockAnswer(sentAt, SystemClock.elapsedRealtime(), seconds))
                        }
                    }
                    if (inner.sequence == startCaptureSeq) {
                        startCaptureSeq = -1
                    }
                    if (inner.sequence == takePictureSeq) {
                        takePictureSeq = -1
                        photoTimeoutJob?.cancel()
                        Log.i(TAG, "Camera took the photo")
                        _state.update { it.copy(photosTaken = it.photosTaken + 1, isTakingPhoto = false) }
                    }
                    if (inner.sequence == stopCaptureSeq) {
                        stopCaptureSeq = -1
                        Go2BleProtocol.parseCapturedFilePath(inner.payload)?.let { path ->
                            val name = path.substringAfterLast('/')
                            val seconds = Go2BleProtocol.parseCapturedFileSeconds(inner.payload)
                            Log.i(TAG, "Camera saved $name")
                            _state.update { it.copy(lastVideoFile = name, lastVideoSeconds = seconds) }
                        }
                    }
                    val confirmedSubModes = synchronized(subModeLock) {
                        val pending = pendingSubModeWrites.remove(inner.sequence)
                        if (pending != null && pending.generation > lastAppliedSubModeGeneration) {
                            pendingSubModeWrites.entries.removeAll { it.value.generation <= pending.generation }
                            val hasNewerPending = pendingSubModeWrites.values.any { it.generation > pending.generation }
                            if (!hasNewerPending) {
                                lastAppliedSubModeGeneration = pending.generation
                                deferredConfirmedSubMode = null
                                applySubModesLocked(pending.subModes)
                                pending.subModes
                            } else {
                                if (deferredConfirmedSubMode == null || pending.generation > deferredConfirmedSubMode!!.generation) {
                                    deferredConfirmedSubMode = pending
                                }
                                null
                            }
                        } else {
                            null
                        }
                    }
                    if (confirmedSubModes != null) {
                        Log.i(
                            TAG,
                            "Camera confirmed sub-mode switch seq=${inner.sequence}: photo=${confirmedSubModes.photo}, video=${confirmedSubModes.video}"
                        )
                    }
                }

                in 100..599 -> {
                    Log.w(TAG, "Camera returned status ${inner.commandCode} for seq ${inner.sequence}: $hexPayload")
                    synchronized(subModeLock) {
                        if (pendingSubModeWrites.remove(inner.sequence) != null) {
                            promoteDeferredSubModeIfReadyLocked()
                        }
                    }
                    if (inner.sequence == authorizationCheckSeq) {
                        authorizationCheckSeq = -1
                        authorizationCheckReply?.complete(null)
                    }
                    if (inner.sequence == wifiInfoQuerySeq) {
                        wifiInfoQuerySeq = -1
                        wifiInfoReply?.complete(null)
                    }
                    if (inner.sequence == takePictureSeq) {
                        takePictureSeq = -1
                        photoTimeoutJob?.cancel()
                        _state.update { it.copy(photosFailed = it.photosFailed + 1, isTakingPhoto = false) }
                    }
                    if (inner.sequence == startCaptureSeq) {
                        startCaptureSeq = -1
                        captureChangeTimeoutJob?.cancel()
                        markRecordingStop()
                        _state.update {
                            it.copy(
                                isCaptureStarting = false,
                                lastError = "Camera refused to start recording (status ${inner.commandCode})"
                            )
                        }
                    }
                    if (inner.sequence == stopCaptureSeq) {
                        stopCaptureSeq = -1
                        captureChangeTimeoutJob?.cancel()
                        _state.update { it.copy(isCaptureStopping = false) }
                        queryCaptureStatus()
                    }
                }
            }
        }
    }

    private fun updateDetails(change: (CameraDetails) -> CameraDetails) {
        _state.update { it.copy(details = change(it.details)) }
    }

    /** Keeps the camera's reported mode, and the sub-mode of that mode for switching back to it. */
    private fun onSubModes(subModes: Go2BleProtocol.SubModes) {
        synchronized(subModeLock) {
            onSubModesLocked(subModes)
        }
    }

    private fun onSubModesLocked(subModes: Go2BleProtocol.SubModes) {
        val maxPendingGen = pendingSubModeWrites.values.maxOfOrNull { it.generation }
        val matchingOlder = if (maxPendingGen != null) {
            pendingSubModeWrites.values
                .filter { it.subModes == subModes && it.generation < maxPendingGen }
                .maxByOrNull { it.generation }
        } else {
            null
        }
        if (matchingOlder != null) {
            pendingSubModeWrites.entries.removeAll { it.value.generation <= matchingOlder.generation }
            if (deferredConfirmedSubMode == null || matchingOlder.generation > deferredConfirmedSubMode!!.generation) {
                deferredConfirmedSubMode = matchingOlder
            }
        } else {
            val gen = ++subModeGeneration
            lastAppliedSubModeGeneration = gen
            deferredConfirmedSubMode = null
            pendingSubModeWrites.clear()
            applySubModesLocked(subModes)
        }
    }

    private fun promoteDeferredSubModeIfReadyLocked() {
        if (pendingSubModeWrites.isNotEmpty()) return
        val deferred = deferredConfirmedSubMode ?: return
        if (deferred.generation > lastAppliedSubModeGeneration) {
            lastAppliedSubModeGeneration = deferred.generation
            deferredConfirmedSubMode = null
            applySubModesLocked(deferred.subModes)
        } else {
            deferredConfirmedSubMode = null
        }
    }

    private fun applySubModesLocked(subModes: Go2BleProtocol.SubModes) {
        if (subModes.photo in 0 until Go2BleProtocol.SUB_MODE_NONE) lastPhotoSubMode = subModes.photo
        if (subModes.video in 0 until Go2BleProtocol.SUB_MODE_NONE) lastVideoSubMode = subModes.video
        updateDetails { it.copy(subModes = subModes) }
    }

    /**
     * Updates the camera's active sub-modes when reported by an auxiliary transport (e.g., TCP port
     * 6666 during Live View while BLE `0x2053` notifications are suppressed) and invalidates older
     * in-flight BLE sub-mode write expectations.
     */
    fun updateSubModesFromExternalTransport(subModes: Go2BleProtocol.SubModes) {
        synchronized(subModeLock) {
            if (_state.value.connectionStatus != BleConnectionStatus.CONNECTED) return
            Log.i(TAG, "External transport camera mode: photo sub-mode ${subModes.photo}, video sub-mode ${subModes.video}")
            onSubModesLocked(subModes)
        }
    }

    /**
     * Updates remaining recording time or photo count when reported by an auxiliary transport with
     * the same semantics as BLE `NOTIF_CAPTURE_TIME_LEFT`.
     */
    fun updateCaptureSecondsLeftFromExternalTransport(secondsLeft: Int) {
        if (secondsLeft < 0) return
        synchronized(subModeLock) {
            if (_state.value.connectionStatus != BleConnectionStatus.CONNECTED) return
            updateDetails { it.copy(captureSecondsLeft = secondsLeft) }
        }
    }

    /**
     * Returns the latest in-flight requested sub-modes if a mode switch is pending, or the last
     * confirmed `details.subModes` otherwise.
     */
    fun effectiveSubModes(): Go2BleProtocol.SubModes? = synchronized(subModeLock) {
        pendingSubModeWrites.values.maxByOrNull { it.generation }?.subModes
            ?: _state.value.details.subModes
    }

    /**
     * The camera's answer to the status request sent on connecting. It fills in what the camera
     * would otherwise only send when something next changes.
     */
    private fun onStatusOptionsReply(reply: Go2BleProtocol.StatusOptions) {
        Log.i(TAG, "Status on connecting: battery=${reply.battery}, time left=${reply.captureSecondsLeft} s, " +
            "storage=${reply.cardState}, pod=${reply.pod}, sub-modes=${reply.subModes}")
        reply.subModes?.let { onSubModes(it) }
        _state.update { state ->
            state.copy(
                batteryPercent = reply.battery?.levelPercent ?: state.batteryPercent,
                isCharging = reply.battery?.onExternalPower ?: state.isCharging,
                details = state.details.copy(
                    captureSecondsLeft = reply.captureSecondsLeft ?: state.details.captureSecondsLeft,
                    cardState = reply.cardState ?: state.details.cardState,
                    freeSpaceBytes = reply.freeSpaceBytes ?: state.details.freeSpaceBytes,
                    totalSpaceBytes = reply.totalSpaceBytes ?: state.details.totalSpaceBytes,
                    pod = reply.pod ?: state.details.pod
                )
            )
        }
    }

    /**
     * The camera's answer to the capture status request sent on connecting. If it is already
     * recording, the GPS stream starts from before the clip's first frame, worked back from the
     * time recorded so far, so the part that was missed is sent too, as records without a fix
     * where there are no remembered fixes.
     */
    private fun onCaptureStatusReply(reply: Go2BleProtocol.CaptureStatusReply) {
        Log.i(TAG, "Capture status on connecting: state=${reply.captureState}, recorded ${reply.captureSeconds} s")
        if (!Go2BleProtocol.isRecordingCaptureState(reply.captureState)) return
        // A start notice may have arrived in the meantime; then the start was seen after all
        if (_state.value.gpsStreamStartAtMs != null) return

        markRecordingStart(-(reply.captureSeconds * 1000L + JOINED_RECORDING_STREAM_EXTRA_MS))
        _state.update {
            it.copy(
                isRecording = true,
                isCaptureStarting = false,
                joinedRecordingSeconds = reply.captureSeconds,
                details = it.details.copy(recordingState = reply.captureState)
            )
        }
    }

    private fun parseCaptureStatus(payload: ByteArray) {
        // Notification 0x2010 is CaptureStatus; field 1 (tag 0x08) is the CameraCaptureState.
        if (payload.isEmpty()) return
        try {
            val captureState = Go2BleProtocol.parseCaptureState(payload)
            val isRecording = Go2BleProtocol.isRecordingCaptureState(captureState)
            captureChangeTimeoutJob?.cancel()
            _state.update { it.copy(isRecording = isRecording, isCaptureStarting = false, isCaptureStopping = false) }
            Log.i(TAG, "Parsed capture status: isRecording=$isRecording (state=$captureState)")

            if (!isRecording) {
                markRecordingStop()
            } else {
                val current = _state.value
                if (current.gpsStreamStartAtMs == null && current.joinedRecordingSeconds == null) {
                    // Recording without the start notice having been seen
                    markRecordingStart(-STREAM_START_BEFORE_CAPTURE_STATUS_MS)
                }
                updateDetails { it.copy(recordingState = captureState) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Records the moment, [startInMs] from now, from which the recording now starting, or already
     * running, has its GPS streamed: a little before its first frame (STREAM_START_* and the
     * notes beside it).
     */
    private fun markRecordingStart(startInMs: Long) {
        val startAt = SystemClock.elapsedRealtime() + startInMs
        _state.update {
            it.copy(gpsStreamStartAtMs = startAt, lastVideoFile = null, lastVideoSeconds = null)
        }
        Log.i(TAG, "Recording start, GPS streamed from $startInMs ms from now")
    }

    private fun markRecordingStop() {
        if (_state.value.gpsStreamStartAtMs != null) {
            Log.i(TAG, "Recording stop")
            // Read again for the next recording, in case the clock has drifted or been set
            if (cameraIsOn()) scheduleClockSync()
        }
        _state.update {
            it.copy(
                gpsStreamStartAtMs = null,
                joinedRecordingSeconds = null,
                details = it.details.copy(recordingState = null)
            )
        }
        gpsQueue.clear()
    }

    private fun queuePacket(packet: ByteArray) {
        onPacketQueuedForTesting?.let { hook ->
            hook(packet)
            if (activeGatt == null || writeCharacteristic == null) return
        }
        writeChannel.trySend(packet)
    }

    @SuppressLint("MissingPermission")
    private suspend fun performGattWrite(packet: ByteArray) {
        val gatt = activeGatt
        val char = writeCharacteristic
        if (gatt == null || char == null) {
            clearPendingSubModeWriteForPacket(packet)
            return
        }
        Log.v(TAG, "TX ${packet.toHex()}")

        var retries = 3
        while (retries > 0) {
            val ack = CompletableDeferred<Boolean>()
            pendingWriteAck = ack

            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = packet
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }

            if (initiated) {
                withTimeoutOrNull(1000) { ack.await() }
                return
            } else {
                retries--
                if (retries > 0) {
                    kotlinx.coroutines.delay(100)
                } else {
                    Log.w(TAG, "Failed to initiate GATT write on ${char.uuid} after retries")
                    clearPendingSubModeWriteForPacket(packet)
                }
            }
        }
    }

    private fun clearPendingSubModeWriteForPacket(packet: ByteArray) {
        synchronized(subModeLock) {
            if (pendingSubModeWrites.isEmpty()) return
        }
        val inner = Go2BleProtocol.decodePacket(packet)
            ?.takeIf { it.isMessage }
            ?.let { Go2BleProtocol.decodeInnerMessage(it.innerData) }
            ?: return
        synchronized(subModeLock) {
            if (pendingSubModeWrites.remove(inner.sequence) != null) {
                promoteDeferredSubModeIfReadyLocked()
            }
        }
    }

    /**
     * Switches the camera to Photo ([photo] true) or Video, in the sub-mode it was last seen in
     * for that mode. The camera confirms with `CMD_SET_OPTIONS` (0xC8) and/or a sub-mode notification.
     */
    fun setCameraMode(photo: Boolean) {
        val subMode = synchronized(subModeLock) {
            if (photo) lastPhotoSubMode else lastVideoSubMode
        }
        sendSubMode(photo, subMode)
    }

    /** Switches the camera to [mode]. The camera confirms with `CMD_SET_OPTIONS` (0xC8) and/or a sub-mode notification. */
    fun setCameraMode(mode: CameraMode) {
        sendSubMode(mode.isPhoto, mode.subMode)
    }

    private fun sendSubMode(photo: Boolean, subMode: Int) {
        if (onPacketQueuedForTesting == null && (activeGatt == null || writeCharacteristic == null)) {
            return
        }
        val targetSubModes = if (photo) {
            Go2BleProtocol.SubModes(photo = subMode, video = Go2BleProtocol.SUB_MODE_NONE)
        } else {
            Go2BleProtocol.SubModes(photo = Go2BleProtocol.SUB_MODE_NONE, video = subMode)
        }
        val payload = Go2BleProtocol.buildSetSubModePayload(photo, subMode)
        synchronized(subModeLock) {
            val seq = nextSeq()
            val gen = ++subModeGeneration
            pendingSubModeWrites[seq] = PendingSubModeWrite(targetSubModes, gen)
            Log.i(TAG, "Switching the camera to ${if (photo) "Photo" else "Video"}, sub-mode $subMode, seq=$seq")
            queuePacket(
                Go2BleProtocol.encodeMessagePacket(
                    Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_SET_OPTIONS, seq, payload)
                )
            )
        }
    }

    /**
     * Takes a photo, or an HDR set when [mode] is HDR Photo. A plain take-picture command puts
     * a GO 3S back in single Photo first.
     */
    fun takePhoto(mode: CameraMode? = null) {
        val command = mode?.startCommand ?: Go2BleProtocol.CMD_TAKE_PICTURE
        val seq = nextSeq()
        takePictureSeq = seq
        val inner = Go2BleProtocol.encodeInnerMessage(command, seq, mode?.startPayload() ?: ByteArray(0))
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)

        // Also queue Architecture A fallback if write characteristic is CE81
        if (writeCharacteristic?.uuid == WRITE_CE81) {
            queuePacket(Go2BleProtocol.REMOTE_CMD_SHUTTER)
        }

        _state.update { it.copy(isTakingPhoto = true) }
        photoTimeoutJob?.cancel()
        photoTimeoutJob = scope.launch {
            delay(PHOTO_ANSWER_TIMEOUT_MS)
            if (takePictureSeq == seq) {
                takePictureSeq = -1
                Log.w(TAG, "No answer to the photo command after ${PHOTO_ANSWER_TIMEOUT_MS / 1000} s")
                _state.update { it.copy(isTakingPhoto = false) }
            }
        }
    }

    /** A photo waiting for its answer when the link ends gets none; the state's flag is cleared with the rest. */
    private fun forgetPhotoInProgress() {
        takePictureSeq = -1
        photoTimeoutJob?.cancel()
    }

    /**
     * Lets go of the sequence numbers of requests still waiting for a reply when a link ends, as
     * none will come on it. Left set, one could take the reply to a later request that reuses
     * the number once the count wraps.
     */
    internal fun forgetPendingReplies() {
        captureStatusQuerySeq = -1
        statusOptionsQuerySeq = -1
        authorizationCheckSeq = -1
        wifiInfoQuerySeq = -1
        deviceIdentityQuerySeq = -1
        startCaptureSeq = -1
        stopCaptureSeq = -1
        clockQueries.clear()
        synchronized(subModeLock) {
            pendingSubModeWrites.clear()
            deferredConfirmedSubMode = null
            subModeGeneration++
            lastAppliedSubModeGeneration = subModeGeneration
        }
    }

    /**
     * Starts a recording in [mode], or a timelapse, interval or starlapse run; normal video when
     * it is null. A plain start command puts a GO 3S back in normal Video first.
     */
    fun startRecording(mode: CameraMode? = null) {
        val command = mode?.startCommand ?: Go2BleProtocol.CMD_START_CAPTURE
        val seq = nextSeq()
        startCaptureSeq = seq
        val inner = Go2BleProtocol.encodeInnerMessage(command, seq, mode?.startPayload() ?: ByteArray(0))
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)

        if (writeCharacteristic?.uuid == WRITE_CE81) {
            queuePacket(Go2BleProtocol.REMOTE_CMD_SHUTTER)
        }

        startCommandAtMs = SystemClock.elapsedRealtime()
        markRecordingStart(0L)

        // The camera takes a few seconds to start. isRecording is set when its capture status
        // notification arrives, so the timer lines up with the clip.
        _state.update { it.copy(isCaptureStarting = true) }
        captureChangeTimeoutJob?.cancel()
        captureChangeTimeoutJob = scope.launch {
            delay(CAPTURE_CHANGE_TIMEOUT_MS)
            if (startCaptureSeq == seq) startCaptureSeq = -1
            if (!_state.value.isRecording) markRecordingStop()
            _state.update { it.copy(isCaptureStarting = false) }
        }
    }

    /**
     * Stops the recording or run, with the stop command of the mode the camera is in, so one
     * started with the camera's own button stops too.
     */
    fun stopRecording() {
        val mode = _state.value.details.recordingState?.let { CameraMode.ofCaptureState(it) }
            ?: _state.value.details.subModes?.let { CameraMode.of(it) }?.takeIf { it.isContinuous }
        // The camera's reply to this command carries the name of the video file
        val seq = nextSeq()
        stopCaptureSeq = seq
        val command = mode?.stopCommand ?: Go2BleProtocol.CMD_STOP_CAPTURE
        val inner = Go2BleProtocol.encodeInnerMessage(command, seq, mode?.stopPayload() ?: ByteArray(0))
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)

        if (writeCharacteristic?.uuid == WRITE_CE81) {
            queuePacket(Go2BleProtocol.REMOTE_CMD_SHUTTER)
        }

        markRecordingStop()

        // As with start, the camera's capture status ends the recording state. If it never
        // arrives, the timeout clears it.
        _state.update { it.copy(isCaptureStopping = true) }
        captureChangeTimeoutJob?.cancel()
        captureChangeTimeoutJob = scope.launch {
            delay(CAPTURE_CHANGE_TIMEOUT_MS)
            _state.update { it.copy(isRecording = false, isCaptureStopping = false) }
        }
    }

    /**
     * Asks the camera whether it is recording. Sent on connecting, to notice a recording that
     * was started before the app was there to see it.
     */
    private fun queryCaptureStatus() {
        val seq = nextSeq()
        captureStatusQuerySeq = seq
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_GET_CURRENT_CAPTURE_STATUS, seq)
        queuePacket(Go2BleProtocol.encodeMessagePacket(inner))
    }

    /**
     * Asks the camera for its battery, recording time left, storage state and Action Pod. Sent
     * on connecting, because the camera otherwise reports these only when they next change.
     */
    private fun queryStatusOptions() {
        val seq = nextSeq()
        statusOptionsQuerySeq = seq
        val inner = Go2BleProtocol.encodeInnerMessage(
            Go2BleProtocol.CMD_GET_OPTIONS,
            seq,
            Go2BleProtocol.buildStatusOptionsRequest()
        )
        queuePacket(Go2BleProtocol.encodeMessagePacket(inner))
    }

    private fun queryDeviceIdentity() {
        val seq = nextSeq()
        deviceIdentityQuerySeq = seq
        val inner = Go2BleProtocol.encodeInnerMessage(
            Go2BleProtocol.CMD_GET_OPTIONS,
            seq,
            Go2BleProtocol.buildDeviceIdentityRequest()
        )
        queuePacket(Go2BleProtocol.encodeMessagePacket(inner))
    }

    /**
     * Queues one GPS sample for the camera.
     *
     * Every sample is sent, in order, and samples that are waiting go out at a steady pace, not
     * all together: the camera drops records that arrive in a burst.
     */
    fun addGpsFix(
        timestampMs: Long,
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        bearingDegrees: Double,
        altitudeMeters: Double,
        hasFix: Boolean = true
    ) {
        if (writeCharacteristic == null) return
        if (_state.value.gpsStreamStartAtMs == null) return
        gpsQueue.add(
            Go2BleProtocol.buildGpsRecord(timestampMs, latitude, longitude, speedMps, bearingDegrees, altitudeMeters, hasFix)
        )
        if (gpsQueue.size > MAX_QUEUED_GPS_RECORDS) {
            gpsQueue.poll()
            Log.w(TAG, "GPS queue full, dropped the oldest sample")
        }
        writeChannel.trySend(gpsReady)
    }

    /** How many GPS samples are waiting to be sent. */
    fun gpsBacklog(): Int = gpsQueue.size

    private suspend fun flushGps() {
        val waiting = gpsQueue.size
        if (waiting == 0) return

        val max = gpsPacer.take(
            SystemClock.elapsedRealtime(),
            minOf(waiting, Go2BleProtocol.gpsRecordsPerPacket(attMtu))
        )
        val batch = ArrayList<ByteArray>(max)
        while (batch.size < max) {
            batch.add(gpsQueue.poll() ?: break)
        }

        if (batch.isNotEmpty()) {
            val seq = nextSeq()
            val inner = Go2BleProtocol.encodeInnerMessage(
                Go2BleProtocol.CMD_UPLOAD_GPS,
                seq,
                Go2BleProtocol.buildUploadGpsPayload(batch)
            )
            Log.v(TAG, "UploadGps seq=$seq records=${batch.size}")
            performGattWrite(Go2BleProtocol.encodeMessagePacket(inner))
            _state.update { it.copy(lastGpsSentTimeMs = System.currentTimeMillis()) }
        }

        // Come back for the samples still waiting once the pace allows the next one
        if (gpsQueue.isNotEmpty() && gpsFlushRetry?.isActive != true) {
            val waitMs = gpsPacer.waitMs(SystemClock.elapsedRealtime())
            gpsFlushRetry = scope.launch {
                delay(waitMs)
                writeChannel.trySend(gpsReady)
            }
        }
    }

    /**
     * Cleanly disconnects and releases Bluetooth resources.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        // Cleared first, so that the link ending here is not taken for one that dropped
        reconnectJob?.cancel()
        idleLinkJob?.cancel()
        clockJob?.cancel()
        captureChangeTimeoutJob?.cancel()
        gpsFlushRetry?.cancel()
        wakeJob?.cancel()
        wakeJob = null
        wakeOnNextConnect = false
        answeringSinceMs = null
        linkIsReconnect = false
        stopScan()
        stopKeepAlive()
        pendingWriteAck?.complete(false)
        drainWriteQueue()
        markRecordingStop()
        forgetPhotoInProgress()
        val oldGatt = activeGatt
        activeGatt = null
        oldGatt?.disconnect()
        oldGatt?.close()
        writeCharacteristic = null
        attMtu = 23
        registrationJob?.cancel()
        synchronized(subModeLock) {
            forgetPendingReplies()
            _state.update {
                it.copy(
                    connectionStatus = BleConnectionStatus.DISCONNECTED,
                    isReconnecting = false,
                    isWaking = false,
                    isLinkIdle = false,
                    isCameraAwake = false,
                    isRecording = false,
                    isCaptureStarting = false,
                    isCaptureStopping = false,
                    isTakingPhoto = false,
                    batteryPercent = null,
                    isCharging = false,
                    cameraClockOffsetMs = null,
                    cameraModel = null,
                    reportedDeviceType = null,
                    reportedSerialNumber = null,
                    isCameraWifiReady = false,
                    details = CameraDetails(),
                    wakeUpRegistration = it.wakeUpRegistration.afterLinkDown()
                )
            }
        }
    }

    /** What the camera last said about the ID stays when the link goes down; a question cut short does not. */
    private fun WakeUpRegistration.afterLinkDown(): WakeUpRegistration = when (this) {
        WakeUpRegistration.CHECKING, WakeUpRegistration.CONFIRM_ON_CAMERA -> WakeUpRegistration.NONE
        else -> this
    }
}
