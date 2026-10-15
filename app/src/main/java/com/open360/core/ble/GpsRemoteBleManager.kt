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
import android.util.Log
import androidx.core.content.ContextCompat
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
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

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
    // Recording time left on the storage at the current mode's bitrate
    val captureSecondsLeft: Int? = null,
    val cardState: Go2BleProtocol.CardState? = null,
    val pod: Go2BleProtocol.ChargeBoxStatus? = null,
    // CameraCaptureState of the recording in progress (1 Video, 17 PRO Video), null when idle
    val recordingState: Int? = null,
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
    // SystemClock.elapsedRealtime() of the current recording's first video frame, null when idle
    val recordingFirstFrameAtMs: Long? = null,
    // File name of the last video the camera reported saving, e.g. VID_20261012_163049_00_066.mp4.
    // Only known for recordings stopped from the app. Cleared when the next recording starts.
    val lastVideoFile: String? = null,
    // Its length in whole seconds, from the same reply
    val lastVideoSeconds: Int? = null,
    // Set when the camera was already recording as the app connected: how long it had been
    // recording. The clip's first frame is worked back from this, to within about half a second.
    val joinedRecordingSeconds: Int? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val details: CameraDetails = CameraDetails(),
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val lastGpsSentTimeMs: Long = 0L,
    val lastError: String? = null
)

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

        // 10 s of samples. Beyond this the link is not keeping up and the oldest are dropped.
        private const val MAX_QUEUED_GPS_RECORDS = 100

        // Samples are made ten a second. Sending is held to a little more than that, so a
        // backlog clears without the burst that makes the camera drop records.
        private const val GPS_SEND_RECORDS_PER_SECOND = 12.0
        private const val GPS_SEND_MAX_BURST = 4

        // Time from the camera's start notice to the first video frame on a GO 3S, worked out as
        // the stop minus the video length, so it takes the video to end at the stop. It depends
        // on who started the recording. The notice comes about 0.2 s after the app's start
        // command, with the first frame 1.61 and 1.67 s later on two clips. When the camera's
        // own button is used the notice comes well into the start-up, with the first frame 0.2
        // to 0.4 s later on four clips. On a ride, though, the GPS of a clip started with the
        // button sat about 0.2 s later against the camera's motion sensors than that of a clip
        // started from the app, so the button figure is taken as 0.55 s.
        private const val FIRST_FRAME_AFTER_APP_START_NOTICE_MS = 1640L
        private const val FIRST_FRAME_AFTER_BUTTON_START_NOTICE_MS = 550L

        // Used for an app start until the notice arrives
        private const val FIRST_FRAME_AFTER_START_COMMAND_MS = 1850L

        // A start notice this soon after the app's start command was caused by it
        private const val START_COMMAND_NOTICE_WINDOW_MS = 3000L

        // The capture status notification comes 1.6 to 2.3 s after the first frame. Used only
        // when a recording is seen without its start notice.
        private const val FIRST_FRAME_BEFORE_CAPTURE_STATUS_MS = 1800L

        val SERVICE_BE80: UUID = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
        val WRITE_BE81: UUID = UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_BE82: UUID = UUID.fromString("0000be82-0000-1000-8000-00805f9b34fb")

        val SERVICE_CE80: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")
        val WRITE_CE81: UUID = UUID.fromString("0000ce81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CE82: UUID = UUID.fromString("0000ce82-0000-1000-8000-00805f9b34fb")

        // The camera gives the time recorded so far in whole seconds. Half a second is added,
        // the middle of what that leaves open, so a recording joined late is placed to within
        // about half a second. On the one clip checked, a reply of 5 s came between 5.4 and
        // 6.4 s after the first frame.
        private const val JOINED_RECORDING_ROUNDING_MS = 500L

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

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BleCameraState())
    val state: StateFlow<BleCameraState> = _state.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var sequenceNumber = 1

    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var pendingWriteAck: CompletableDeferred<Boolean>? = null

    private var attMtu = 23
    private var activeScanCallback: ScanCallback? = null
    private var keepAliveJob: Job? = null
    private var captureChangeTimeoutJob: Job? = null
    @Volatile
    private var stopCaptureSeq = -1
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
    @Volatile
    private var wakeInProgress = false
    @Volatile
    private var startCommandAtMs: Long? = null
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
    private var wakeJob: Job? = null

    // The request for the camera's Wi-Fi details, matched to its reply by sequence number
    @Volatile
    private var wifiInfoQuerySeq = -1
    @Volatile
    private var wifiInfoReply: CompletableDeferred<Go2BleProtocol.WifiInfo?>? = null

    // Both are used only by the write queue worker
    private val gpsPacer = GpsSendPacer(GPS_SEND_RECORDS_PER_SECOND, GPS_SEND_MAX_BURST)
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

    @Synchronized
    private fun nextSeq(): Int {
        val s = sequenceNumber
        sequenceNumber = if (sequenceNumber >= 254) 1 else sequenceNumber + 1
        return s
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
        prefs.edit().putString(PREF_WAKE_AUTHORIZATION_ID, id).apply()
        return true
    }

    fun getSavedSerialSuffix(): String {
        val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
        return prefs.getString("camera_serial_suffix", "8JY3HF") ?: "8JY3HF"
    }

    fun saveSerialSuffix(suffix: String) {
        if (suffix.isNotBlank()) {
            val prefs = context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("camera_serial_suffix", suffix).apply()
        }
    }

    @SuppressLint("MissingPermission")
    fun startWakeUpBroadcast(serialSuffix: String? = null, onComplete: (() -> Unit)? = null) {
        if (!canAdvertise()) {
            _state.update { it.copy(lastError = "Open360 is not allowed to use Bluetooth. Allow Nearby Devices for it in the phone's settings.") }
            return
        }

        val targetSuffix = serialSuffix?.takeIf { it.isNotBlank() } ?: getSavedSerialSuffix()

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
    fun startScanAndConnect(targetSerialSuffix: String? = null) {
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

        _state.update { it.copy(connectionStatus = BleConnectionStatus.SCANNING, lastError = null) }

        val filters = mutableListOf<ScanFilter>()
        // Filter by known services
        filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_BE80)).build())
        filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_CE80)).build())

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (activeScanCallback !== this) return
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: ""

                // Match Insta360 camera prefixes or explicit serial suffix
                val isInsta360 = name.startsWith("GO 3", ignoreCase = true) ||
                        name.startsWith("Insta360", ignoreCase = true) ||
                        name.startsWith("X3", ignoreCase = true) ||
                        name.startsWith("X4", ignoreCase = true) ||
                        name.startsWith("ONE RS", ignoreCase = true) ||
                        (targetSerialSuffix != null && name.contains(targetSerialSuffix, ignoreCase = true)) ||
                        result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_BE80 || it.uuid == SERVICE_CE80 } == true

                if (isInsta360) {
                    Log.i(TAG, "Found target camera: $name (${device.address})")
                    stopScan()

                    connectGatt(device, name)
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
     * Sends the keep-alive packet on a timer while connected.
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                // Nothing else is sent while a wake-up attempt is under way
                if (wakeInProgress) continue
                queuePacket(Go2BleProtocol.buildKeepAlivePacket())
                if (_state.value.isCameraAwake && SystemClock.elapsedRealtime() - lastHeardAtMs > CAMERA_SILENT_MS) {
                    Log.w(TAG, "Camera has stopped answering")
                    _state.update { it.copy(isCameraAwake = false) }
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
    private fun connectGatt(device: BluetoothDevice, discoveredName: String) {
        val safeName = discoveredName.ifEmpty { device.name ?: "Insta360 Camera" }
        val tokens = safeName.trim().split("\\s+".toRegex())
        val candidateSuffix = tokens.lastOrNull()
        if (candidateSuffix != null && candidateSuffix.length == 6 && candidateSuffix.all { it.isLetterOrDigit() }) {
            saveSerialSuffix(candidateSuffix)
        }

        _state.update {
            it.copy(
                connectionStatus = BleConnectionStatus.CONNECTING,
                deviceName = safeName,
                deviceAddress = device.address
            )
        }

        activeGatt?.close()
        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(connectionStatus = BleConnectionStatus.CONNECTING) }
                // Request MTU to support full GPS and protobuf packets
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "GATT disconnected, status=$status")
                writeCharacteristic = null
                stopKeepAlive()
                markRecordingStop()
                registrationJob?.cancel()
                _state.update {
                    it.copy(
                        connectionStatus = BleConnectionStatus.DISCONNECTED,
                        isCameraAwake = false,
                        isRecording = false,
                        isCaptureStarting = false,
                        isCaptureStopping = false,
                        details = CameraDetails(),
                        wakeUpRegistration = it.wakeUpRegistration.afterLinkDown()
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, status: $status. Discovering services...")
            if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _state.update { it.copy(lastError = "Could not finish connecting to the camera (error $status)") }
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

            // Primary: BE81 (Direct command write)
            writeCharacteristic = be80?.getCharacteristic(WRITE_BE81)
                ?: ce80?.getCharacteristic(WRITE_CE81)

            // Notifications: subscribe to BE82 or CE82
            val notifyChar = be80?.getCharacteristic(NOTIFY_BE82)
                ?: ce80?.getCharacteristic(NOTIFY_CE82)

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

            _state.update { it.copy(connectionStatus = BleConnectionStatus.CONNECTED) }
            startKeepAlive()
            Log.i(TAG, "Camera BLE successfully connected. Ready for commands.")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingWriteAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val channel = descriptor.characteristic?.uuid
            Log.i(TAG, "Subscription to $channel finished, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && (channel == NOTIFY_BE82 || channel == NOTIFY_CE82)) {
                // Subscription complete: channel is open for writes
                if (wakeOnNextConnect) {
                    wakeOnNextConnect = false
                    wakeCamera()
                } else {
                    requestStatusOnce()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { onNotification(characteristic, it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onNotification(characteristic, value)
        }
    }

    private fun onNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == NOTIFY_BE82 || characteristic.uuid == NOTIFY_CE82) {
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
        if (writeCharacteristic == null) return
        wakeJob?.cancel()
        wakeJob = scope.launch {
            wakeInProgress = true
            try {
                // A camera that is on says so within a second of the link opening
                if (answersWithin(1000)) {
                    Log.i(TAG, "Wake: the camera is already on")
                    return@launch
                }
                val id = getWakeAuthorizationId()
                if (id == null) {
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
                // Carry on as after any connection; a camera that is off answers once it is on
                requestStatusOnce()
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
            .edit().putBoolean(PREF_WAKE_SETUP_OFFERED, true).apply()
    }

    /** Whether the card that leads to the set-up for Wake Up has been dismissed. */
    fun isWakeUpSetupCardDismissed(): Boolean =
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_WAKE_SETUP_CARD_DISMISSED, false)

    fun setWakeUpSetupCardDismissed(dismissed: Boolean) {
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_WAKE_SETUP_CARD_DISMISSED, dismissed).apply()
    }

    /**
     * Sends the camera a command by its code, with a payload given in hex, for trying out
     * commands from a debug build. The camera's answer shows in the log under its sequence number.
     */
    fun sendCommandForTesting(commandCode: Int, payloadHex: String) {
        val payload = payloadHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val seq = nextSeq()
        Log.i(TAG, "Test command 0x${Integer.toHexString(commandCode)}, seq=$seq, payload=$payloadHex")
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
        val status = withTimeoutOrNull(AUTHORIZATION_REPLY_MS) { reply.await() }
        authorizationCheckReply = null
        authorizationCheckSeq = -1
        return status
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
        val info = withTimeoutOrNull(WIFI_INFO_REPLY_MS) { reply.await() }
        wifiInfoReply = null
        wifiInfoQuerySeq = -1
        if (info == null) {
            Log.w(TAG, "The camera gave no Wi-Fi details")
            return null
        }
        Log.i(TAG, "Camera Wi-Fi network: ${info.ssid}")
        context.getSharedPreferences("open360_ble_prefs", Context.MODE_PRIVATE).edit()
            .putString(PREF_CAMERA_WIFI_SSID, info.ssid)
            .putString(PREF_CAMERA_WIFI_PASSWORD, info.password)
            .apply()
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
        wakeOnNextConnect = true
        if (_state.value.connectionStatus != BleConnectionStatus.DISCONNECTED) {
            disconnect()
        }
        startScanAndConnect()
    }

    /** Sends the status requests once per connection. */
    private fun requestStatusOnce() {
        if (statusRequested) return
        statusRequested = true
        queryCaptureStatus()
        queryStatusOptions()
    }

    private suspend fun answersWithin(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it.isCameraAwake } } != null

    private fun handleIncomingNotification(raw: ByteArray) {
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
        if (!_state.value.isCameraAwake) {
            Log.i(TAG, "Camera is answering")
            _state.update { it.copy(isCameraAwake = true) }
            requestStatusOnce()
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
                            markRecordingStart(
                                if (startedByApp) FIRST_FRAME_AFTER_APP_START_NOTICE_MS
                                else FIRST_FRAME_AFTER_BUTTON_START_NOTICE_MS
                            )
                            // The notice names the mode before the capture status does
                            Go2BleProtocol.parseCaptureStepState(inner.payload)
                                ?.takeIf { Go2BleProtocol.isRecordingCaptureState(it) }
                                ?.let { mode -> updateDetails { it.copy(recordingState = mode) } }
                        }
                        Go2BleProtocol.CAPTURE_STEP_STOPPING, Go2BleProtocol.CAPTURE_STEP_DONE -> markRecordingStop()
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
                        authorizationCheckSeq = -1
                        Log.i(TAG, "Authorization check reply: $hexPayload")
                        authorizationCheckReply?.complete(Go2BleProtocol.parseCheckAuthorizationReply(inner.payload))
                    }
                    if (inner.sequence == wifiInfoQuerySeq) {
                        wifiInfoQuerySeq = -1
                        wifiInfoReply?.complete(Go2BleProtocol.parseWifiInfoReply(inner.payload))
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
                }

                in 100..599 -> {
                    Log.w(TAG, "Camera returned status ${inner.commandCode} for seq ${inner.sequence}: $hexPayload")
                    if (inner.sequence == authorizationCheckSeq) {
                        authorizationCheckSeq = -1
                        authorizationCheckReply?.complete(null)
                    }
                    if (inner.sequence == wifiInfoQuerySeq) {
                        wifiInfoQuerySeq = -1
                        wifiInfoReply?.complete(null)
                    }
                }
            }
        }
    }

    private fun updateDetails(change: (CameraDetails) -> CameraDetails) {
        _state.update { it.copy(details = change(it.details)) }
    }

    /**
     * The camera's answer to the status request sent on connecting. It fills in what the camera
     * would otherwise only send when something next changes.
     */
    private fun onStatusOptionsReply(reply: Go2BleProtocol.StatusOptions) {
        Log.i(TAG, "Status on connecting: battery=${reply.battery}, time left=${reply.captureSecondsLeft} s, " +
            "storage=${reply.cardState}, pod=${reply.pod}")
        _state.update { state ->
            state.copy(
                batteryPercent = reply.battery?.levelPercent ?: state.batteryPercent,
                isCharging = reply.battery?.onExternalPower ?: state.isCharging,
                details = state.details.copy(
                    captureSecondsLeft = reply.captureSecondsLeft ?: state.details.captureSecondsLeft,
                    cardState = reply.cardState ?: state.details.cardState,
                    pod = reply.pod ?: state.details.pod
                )
            )
        }
    }

    /**
     * The camera's answer to the capture status request sent on connecting. If it is already
     * recording, the clip's first frame is worked back from the time recorded so far, and the
     * GPS stream starts from there: the camera's software places GPS by counting from the first
     * frame, so the part that was missed has to be sent too, as records without a fix.
     */
    private fun onCaptureStatusReply(reply: Go2BleProtocol.CaptureStatusReply) {
        Log.i(TAG, "Capture status on connecting: state=${reply.captureState}, recorded ${reply.captureSeconds} s")
        if (!Go2BleProtocol.isRecordingCaptureState(reply.captureState)) return
        // A start notice may have arrived in the meantime; then the start was seen after all
        if (_state.value.recordingFirstFrameAtMs != null) return

        markRecordingStart(-(reply.captureSeconds * 1000L + JOINED_RECORDING_ROUNDING_MS))
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
                if (current.recordingFirstFrameAtMs == null && current.joinedRecordingSeconds == null) {
                    // Recording without the start notice having been seen
                    markRecordingStart(-FIRST_FRAME_BEFORE_CAPTURE_STATUS_MS)
                }
                updateDetails { it.copy(recordingState = captureState) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Records when the first video frame of the recording now starting is, or was, captured.
     *
     * The camera keeps every GPS sample it receives from the start to the stop, but the video
     * itself begins later (about 1.8 s after an app start command), and Insta360 Studio shows
     * the first kept sample on the first frame. So the samples sent have to begin with the one
     * for this moment.
     */
    private fun markRecordingStart(firstFrameInMs: Long) {
        val firstFrameAt = SystemClock.elapsedRealtime() + firstFrameInMs
        _state.update {
            it.copy(recordingFirstFrameAtMs = firstFrameAt, lastVideoFile = null, lastVideoSeconds = null)
        }
        Log.i(TAG, "Recording start, first frame in $firstFrameInMs ms")
    }

    private fun markRecordingStop() {
        if (_state.value.recordingFirstFrameAtMs != null) Log.i(TAG, "Recording stop")
        _state.update {
            it.copy(
                recordingFirstFrameAtMs = null,
                joinedRecordingSeconds = null,
                details = it.details.copy(recordingState = null)
            )
        }
        gpsQueue.clear()
    }

    private fun queuePacket(packet: ByteArray) {
        writeChannel.trySend(packet)
    }

    @SuppressLint("MissingPermission")
    private suspend fun performGattWrite(packet: ByteArray) {
        val gatt = activeGatt ?: return
        val char = writeCharacteristic ?: return
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
                }
            }
        }
    }

    /**
     * Triggers a photo capture.
     */
    fun takePhoto() {
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_TAKE_PICTURE, nextSeq())
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)

        // Also queue Architecture A fallback if write characteristic is CE81
        if (writeCharacteristic?.uuid == WRITE_CE81) {
            queuePacket(Go2BleProtocol.REMOTE_CMD_SHUTTER)
        }
    }

    /**
     * Starts normal video recording.
     */
    fun startRecording() {
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_START_CAPTURE, nextSeq())
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)

        if (writeCharacteristic?.uuid == WRITE_CE81) {
            queuePacket(Go2BleProtocol.REMOTE_CMD_SHUTTER)
        }

        startCommandAtMs = SystemClock.elapsedRealtime()
        markRecordingStart(FIRST_FRAME_AFTER_START_COMMAND_MS)

        // The camera takes a few seconds to start. isRecording is set when its capture status
        // notification arrives, so the timer lines up with the clip.
        _state.update { it.copy(isCaptureStarting = true) }
        captureChangeTimeoutJob?.cancel()
        captureChangeTimeoutJob = scope.launch {
            delay(CAPTURE_CHANGE_TIMEOUT_MS)
            _state.update { it.copy(isCaptureStarting = false) }
        }
    }

    /**
     * Stops video recording.
     */
    fun stopRecording() {
        // The camera's reply to this command carries the name of the video file
        val seq = nextSeq()
        stopCaptureSeq = seq
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_STOP_CAPTURE, seq)
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

    /**
     * Queues one GPS sample for the camera.
     *
     * Every sample is sent, in order, because the stored records are placed on the video by
     * their position in the stream. Samples that are waiting go out at a steady pace, not all
     * together: the camera drops records that arrive in a burst.
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
        if (_state.value.recordingFirstFrameAtMs == null) return
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
     * Wakes the camera screen.
     */
    fun wakeScreen() {
        queuePacket(Go2BleProtocol.REMOTE_CMD_SCREEN_TOGGLE)
    }

    /**
     * Cycles camera capture mode over BLE.
     */
    fun cycleMode() {
        queuePacket(Go2BleProtocol.REMOTE_CMD_MODE)
    }

    /**
     * Powers off the camera over BLE.
     */
    fun powerOff() {
        queuePacket(Go2BleProtocol.REMOTE_CMD_POWER_OFF)
    }

    /**
     * Cleanly disconnects and releases Bluetooth resources.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        stopKeepAlive()
        markRecordingStop()
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        writeCharacteristic = null
        registrationJob?.cancel()
        _state.update {
            it.copy(
                connectionStatus = BleConnectionStatus.DISCONNECTED,
                isCameraAwake = false,
                isRecording = false,
                isCaptureStarting = false,
                isCaptureStopping = false,
                details = CameraDetails(),
                wakeUpRegistration = it.wakeUpRegistration.afterLinkDown()
            )
        }
    }

    /** What the camera last said about the ID stays when the link goes down; a question cut short does not. */
    private fun WakeUpRegistration.afterLinkDown(): WakeUpRegistration = when (this) {
        WakeUpRegistration.CHECKING, WakeUpRegistration.CONFIRM_ON_CAMERA -> WakeUpRegistration.NONE
        else -> this
    }
}
