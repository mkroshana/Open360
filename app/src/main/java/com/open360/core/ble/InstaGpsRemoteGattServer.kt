package com.open360.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.open360.core.gps.GpsTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class InstaGpsRemoteServerState(
    val isServerRunning: Boolean = false,
    val isAdvertising: Boolean = false,
    val isCameraConnected: Boolean = false,
    val cameraAddress: String? = null,
    val cameraName: String? = null,
    val cameraSerial: String? = null,
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val cameraDisplayString: String? = null,
    val cameraModeString: String? = null,
    val lastGpsSentTimeMs: Long = 0L,
    val lastError: String? = null
)

/**
 * Hosts an emulated Insta360 GPS Remote GATT Server (Architecture A).
 *
 * Implements:
 * - Primary Service 0xCE80:
 *     - CE81: Camera writes status / recording timer display string / serial handshake.
 *     - CE82: Remote notifies camera with buttons (0x86) and 10 Hz NMEA GPS frames (0x83).
 *     - CE83: Remote static info read (returns 0x01, 0x02).
 * - Secondary Service 0xD0FF:
 *     - Hardware and version info characteristics (FFD1..FFE0).
 * - Connectable LE Advertising of the ORBIT beacon, with device name "Insta360 GPS Remote" in the scan response.
 * - 10 Hz NMEA GPS streaming loop maintaining BLE keep-alive with status 'V' when waiting for fix.
 */
class InstaGpsRemoteGattServer(private val context: Context) {

    companion object {
        private const val TAG = "InstaGpsRemoteGatt"
        const val REMOTE_NAME = "Insta360 GPS Remote"

        // Architecture A Primary Service (0xCE80)
        val SERVICE_CE80: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE_CE81: UUID = UUID.fromString("0000ce81-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY_CE82: UUID = UUID.fromString("0000ce82-0000-1000-8000-00805f9b34fb")
        val CHAR_READ_CE83: UUID = UUID.fromString("0000ce83-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Architecture A Secondary Service (0xD0FF)
        val SERVICE_D0FF: UUID = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFD1: UUID = UUID.fromString("0000ffd1-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFD2: UUID = UUID.fromString("0000ffd2-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFD3: UUID = UUID.fromString("0000ffd3-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFD4: UUID = UUID.fromString("0000ffd4-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFD5: UUID = UUID.fromString("0000ffd5-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFD8: UUID = UUID.fromString("0000ffd8-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFF1: UUID = UUID.fromString("0000fff1-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFF2: UUID = UUID.fromString("0000fff2-3c17-d293-8e48-14fe2e4da212")
        val CHAR_FFE0: UUID = UUID.fromString("0000ffe0-3c17-d293-8e48-14fe2e4da212")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(InstaGpsRemoteServerState())
    val state: StateFlow<InstaGpsRemoteServerState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristicCe82: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var notificationsEnabled = false

    private var buttonSequenceNumber = 0
    private var latestTelemetry = GpsTelemetry()
    private var gpsStreamJob: Job? = null
    private var originalDeviceName: String? = null
    private var beaconSerialSuffix = "8JY3HF"
    private var advertiser: BluetoothLeAdvertiser? = null
    private val pendingServices = ConcurrentLinkedQueue<BluetoothGattService>()

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }
    }

    private fun canAdvertise(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE Advertising started as '$REMOTE_NAME'")
            _state.update { it.copy(isAdvertising = true, lastError = null) }
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMsg = "Advertising failed: code $errorCode"
            Log.e(TAG, errorMsg)
            _state.update { it.copy(isAdvertising = false, lastError = errorMsg) }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Connection state changed: device=${device.address}, status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Fires for every LE link on the phone, not only the camera, so a device is
                // treated as the camera only once it uses the CE80 service (see bindCamera).
                // Android drops an LE link ~4 s after it loses its last local holder, so hold
                // this one while waiting to see whether it is the camera.
                if (connectedDevice == null) {
                    gattServer?.connect(device, false)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                    notificationsEnabled = false
                    stopGpsStreamLoop()
                    _state.update {
                        it.copy(
                            isCameraConnected = false,
                            isRecording = false,
                            cameraAddress = null
                        )
                    }
                    // Resume advertising so camera can reconnect
                    if (_state.value.isServerRunning) {
                        startAdvertising()
                    }
                } else if (connectedDevice == null && _state.value.isServerRunning) {
                    // A central that connected through our advertisement without using CE80
                    // still ended it, so start it again.
                    startAdvertising()
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "Service added: ${service.uuid}, status=$status")
            addNextService()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Read request on ${characteristic.uuid} from ${device.address}")
            when (characteristic.uuid) {
                CHAR_READ_CE83 -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(0x01, 0x02))
                }
                CHAR_FFD2 -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, "1.0.0".toByteArray(Charsets.US_ASCII))
                }
                CHAR_FFD3 -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(0x01, 0x90.toByte(), 0x1E, 0x30))
                }
                CHAR_FFD4 -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(0x01, 0x20, 0x00, 0x18))
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            if (characteristic.uuid == CHAR_WRITE_CE81 && value != null) {
                bindCamera(device)
                handleCameraIncomingPacket(value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val value = if (notificationsEnabled) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID && value != null) {
                bindCamera(device)
                notificationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                Log.i(TAG, "CCCD notification subscription: $notificationsEnabled for ${device.address}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU changed to $mtu for device ${device.address}")
        }
    }

    /**
     * Marks [device] as the connected camera the first time it writes to CE81 or the CE82 CCCD.
     */
    @SuppressLint("MissingPermission")
    private fun bindCamera(device: BluetoothDevice) {
        if (connectedDevice?.address == device.address) return
        Log.i(TAG, "Camera bound on CE80: ${device.address}")
        connectedDevice = device
        notificationsEnabled = false
        buttonSequenceNumber = 0
        gattServer?.connect(device, false)
        _state.update {
            it.copy(
                isCameraConnected = true,
                cameraAddress = device.address,
                cameraName = device.name ?: "Insta360 Camera",
                lastError = null
            )
        }
        startGpsStreamLoop()
    }

    private fun handleCameraIncomingPacket(data: ByteArray) {
        val packet = Go2BleProtocol.parseCameraStatusPacket(data) ?: return
        when (packet) {
            is Go2BleProtocol.CameraStatusPacket.SerialHandshake -> {
                Log.i(TAG, "Camera serial handshake received: ${packet.serial}")
                _state.update { it.copy(cameraSerial = packet.serial) }
            }
            is Go2BleProtocol.CameraStatusPacket.DisplayString -> {
                Log.d(TAG, "Camera display string: ${packet.text}, recording=${packet.isRecording}")
                _state.update {
                    it.copy(
                        isRecording = packet.isRecording,
                        recordingDurationSeconds = packet.durationSeconds ?: it.recordingDurationSeconds,
                        cameraDisplayString = packet.text,
                        cameraModeString = if (packet.isModeString) packet.text else it.cameraModeString
                    )
                }
            }
            is Go2BleProtocol.CameraStatusPacket.StatusWord -> {
                Log.d(TAG, "Camera status word received: len=${packet.payload.size}")
            }
            is Go2BleProtocol.CameraStatusPacket.Unknown -> {
                Log.d(TAG, "Camera unknown packet: type=${packet.type}, len=${packet.payload.size}")
            }
        }
    }

    /**
     * Starts the GATT Server and advertises as 'Insta360 GPS Remote'.
     */
    @SuppressLint("MissingPermission")
    fun startServer(): Boolean {
        if (!canConnect() || !canAdvertise()) {
            val error = "Missing Bluetooth permissions to start GATT server"
            Log.e(TAG, error)
            _state.update { it.copy(lastError = error) }
            return false
        }

        val bm = bluetoothManager ?: run {
            _state.update { it.copy(lastError = "BluetoothManager is null") }
            return false
        }

        stopServer()

        val server = bm.openGattServer(context, gattServerCallback) ?: run {
            val error = "Failed to open GATT Server"
            Log.e(TAG, error)
            _state.update { it.copy(lastError = error) }
            return false
        }
        gattServer = server

        // Advertising starts from addNextService once every service is registered.
        _state.update { it.copy(isServerRunning = true, lastError = null) }
        setupGattServices()
        return true
    }

    /**
     * Adds queued services one at a time. BluetoothGattServer tracks a single pending service, so
     * a second addService before onServiceAdded leaves it unable to route any read or write.
     */
    @SuppressLint("MissingPermission")
    private fun addNextService() {
        val next = pendingServices.poll()
        if (next != null) {
            gattServer?.addService(next)
        } else {
            startAdvertising()
        }
    }

    private fun setupGattServices() {
        // Primary Service: 0xCE80
        val serviceCe80 = BluetoothGattService(SERVICE_CE80, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val charCe81 = BluetoothGattCharacteristic(
            CHAR_WRITE_CE81,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        serviceCe80.addCharacteristic(charCe81)

        val charCe82 = BluetoothGattCharacteristic(
            CHAR_NOTIFY_CE82,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        charCe82.addDescriptor(cccd)
        serviceCe80.addCharacteristic(charCe82)
        notifyCharacteristicCe82 = charCe82

        val charCe83 = BluetoothGattCharacteristic(
            CHAR_READ_CE83,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        serviceCe80.addCharacteristic(charCe83)

        // Second service 0xD0FF. Registered as primary: an un-included secondary service
        // cannot be found by service discovery.
        val serviceD0ff = BluetoothGattService(SERVICE_D0FF, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFD1, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFD2, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFD3, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFD4, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFD5, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFD8, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFF1, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFF2, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        serviceD0ff.addCharacteristic(BluetoothGattCharacteristic(CHAR_FFE0, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))

        pendingServices.clear()
        pendingServices.add(serviceCe80)
        pendingServices.add(serviceD0ff)
        addNextService()
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (!canAdvertise()) return
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv

        try {
            if (canConnect()) {
                if (originalDeviceName == null) {
                    originalDeviceName = bluetoothAdapter?.name
                }
                bluetoothAdapter?.name = REMOTE_NAME
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set BluetoothAdapter name: ${e.message}")
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Same layout as the real remote: the ORBIT beacon carrying the camera serial is the
        // connectable advertisement and the name sits in the scan response. With flags it fills
        // the 31-byte advertisement exactly, so the service UUID goes in the scan response too.
        val data = AdvertiseData.Builder()
            .addManufacturerData(0x004C, buildOrbitPayload(beaconSerialSuffix))
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_CE80))
            .setIncludeDeviceName(true)
            .build()

        try {
            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
        _state.update { it.copy(isAdvertising = false) }
    }

    /**
     * Restarts advertising with the ORBIT beacon for [serialSuffix]. A sleeping camera powers on
     * when it sees its own serial in this beacon.
     */
    fun startWakeBeacon(serialSuffix: String = "8JY3HF") {
        beaconSerialSuffix = serialSuffix
        startAdvertising()
    }

    /**
     * Builds the 24-byte ORBIT manufacturer payload (iBeacon envelope) for a camera serial suffix.
     */
    private fun buildOrbitPayload(serialSuffix: String): ByteArray {
        return ByteArray(24).apply {
            this[0] = 0x02
            this[1] = 0x15
            this[2] = 0x09
            this[3] = 'O'.code.toByte()
            this[4] = 'R'.code.toByte()
            this[5] = 'B'.code.toByte()
            this[6] = 'I'.code.toByte()
            this[7] = 'T'.code.toByte()
            this[8] = 0x09
            this[9] = 0xFF.toByte()
            this[10] = 0x0F
            this[11] = 0x00
            val suffixBytes = serialSuffix.take(6).toByteArray(Charsets.US_ASCII)
            for (i in suffixBytes.indices) {
                this[12 + i] = suffixBytes[i]
            }
            this[22] = 0xE4.toByte()
            this[23] = 0x01
        }
    }

    /**
     * Updates latest GPS telemetry streamed to camera at 10 Hz.
     */
    fun updateGpsTelemetry(telemetry: GpsTelemetry) {
        latestTelemetry = telemetry
    }

    private fun startGpsStreamLoop() {
        gpsStreamJob?.cancel()
        gpsStreamJob = scope.launch {
            while (isActive) {
                val dev = connectedDevice
                val char = notifyCharacteristicCe82
                if (dev != null && char != null && notificationsEnabled) {
                    val t = latestTelemetry
                    val frame = Go2BleProtocol.buildGpsNmeaFrame(
                        latitude = t.latitude,
                        longitude = t.longitude,
                        altitudeMeters = t.altitudeMeters,
                        speedKnots = t.speedKnots,
                        bearingDegrees = t.bearingDegrees.toDouble(),
                        timestampMs = System.currentTimeMillis(),
                        hasFix = t.hasFix
                    )
                    notifyCharacteristic(dev, char, frame)
                    _state.update { it.copy(lastGpsSentTimeMs = System.currentTimeMillis()) }
                }
                delay(100) // 10 Hz (100 ms)
            }
        }
    }

    private fun stopGpsStreamLoop() {
        gpsStreamJob?.cancel()
        gpsStreamJob = null
    }

    @SuppressLint("MissingPermission")
    private fun notifyCharacteristic(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val server = gattServer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun nextButtonSeq(): Int {
        val s = buttonSequenceNumber
        buttonSequenceNumber = (buttonSequenceNumber + 2) and 0xFF
        return s
    }

    fun sendShutter() {
        val dev = connectedDevice ?: return
        val char = notifyCharacteristicCe82 ?: return
        val frame = Go2BleProtocol.buildRemoteButtonCommand(
            Go2BleProtocol.REMOTE_BTN_SHUTTER,
            Go2BleProtocol.REMOTE_STATE_CLICK,
            nextButtonSeq()
        )
        notifyCharacteristic(dev, char, frame)
    }

    fun sendMode() {
        val dev = connectedDevice ?: return
        val char = notifyCharacteristicCe82 ?: return
        val frame = Go2BleProtocol.buildRemoteButtonCommand(
            Go2BleProtocol.REMOTE_BTN_MODE,
            Go2BleProtocol.REMOTE_STATE_CLICK,
            nextButtonSeq()
        )
        notifyCharacteristic(dev, char, frame)
    }

    fun sendScreenToggle() {
        val dev = connectedDevice ?: return
        val char = notifyCharacteristicCe82 ?: return
        val frame = Go2BleProtocol.buildRemoteButtonCommand(
            Go2BleProtocol.REMOTE_BTN_POWER,
            Go2BleProtocol.REMOTE_STATE_CLICK,
            nextButtonSeq()
        )
        notifyCharacteristic(dev, char, frame)
    }

    fun sendPowerOff() {
        val dev = connectedDevice ?: return
        val char = notifyCharacteristicCe82 ?: return
        scope.launch {
            for (i in 0 until 60) {
                val frame = Go2BleProtocol.buildRemoteButtonCommand(
                    Go2BleProtocol.REMOTE_BTN_POWER,
                    Go2BleProtocol.REMOTE_STATE_HELD,
                    nextButtonSeq()
                )
                notifyCharacteristic(dev, char, frame)
                delay(20)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        stopGpsStreamLoop()
        stopAdvertising()
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        pendingServices.clear()
        notifyCharacteristicCe82 = null
        connectedDevice = null
        notificationsEnabled = false

        // Restore original Bluetooth adapter name
        try {
            if (canConnect() && originalDeviceName != null) {
                bluetoothAdapter?.name = originalDeviceName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore BluetoothAdapter name: ${e.message}")
        }

        _state.update {
            InstaGpsRemoteServerState()
        }
    }
}
