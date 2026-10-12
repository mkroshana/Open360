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
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class BleConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

data class BleCameraState(
    val connectionStatus: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val isRecording: Boolean = false,
    val isCaptureStarting: Boolean = false,
    val isCaptureStopping: Boolean = false,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
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
        private const val CAPTURE_CHANGE_TIMEOUT_MS = 10_000L

        val SERVICE_BE80: UUID = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
        val WRITE_BE81: UUID = UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_BE82: UUID = UUID.fromString("0000be82-0000-1000-8000-00805f9b34fb")

        val SERVICE_CE80: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")
        val WRITE_CE81: UUID = UUID.fromString("0000ce81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CE82: UUID = UUID.fromString("0000ce82-0000-1000-8000-00805f9b34fb")

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
    private val gpsBatch = mutableListOf<ByteArray>()

    init {
        // Start write queue worker loop
        scope.launch {
            for (packet in writeChannel) {
                performGattWrite(packet)
            }
        }
    }

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
            _state.update { it.copy(lastError = "Missing BLUETOOTH_ADVERTISE permission") }
            return
        }

        val targetSuffix = serialSuffix?.takeIf { it.isNotBlank() } ?: getSavedSerialSuffix()

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            _state.update { it.copy(lastError = "Bluetooth advertiser not available") }
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
            .setConnectable(false)
            .setTimeout(6000)
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
                _state.update { it.copy(lastError = "Wake broadcast error: $errorCode") }
            }
        })
    }

    /**
     * Scans for the camera and connects via BLE GATT.
     */
    @SuppressLint("MissingPermission")
    fun startScanAndConnect(targetSerialSuffix: String? = null) {
        if (!canScan()) {
            _state.update { it.copy(lastError = "Missing Bluetooth permissions") }
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _state.update { it.copy(lastError = "Bluetooth scanner not available") }
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
                        lastError = "Scan failed with error $errorCode"
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
                queuePacket(Go2BleProtocol.buildKeepAlivePacket())
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
                synchronized(gpsBatch) { gpsBatch.clear() }
                _state.update {
                    it.copy(
                        connectionStatus = BleConnectionStatus.DISCONNECTED,
                        isRecording = false,
                        isCaptureStarting = false,
                        isCaptureStopping = false
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
                _state.update { it.copy(lastError = "Service discovery failed ($status)") }
                return
            }

            Log.i(TAG, "Discovered ${gatt.services.size} services")
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
            Log.i(TAG, "Descriptor write finished on ${descriptor.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Subscription complete: channel is open for writes
                queryBattery()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { handleIncomingNotification(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingNotification(value)
        }
    }

    private fun handleIncomingNotification(raw: ByteArray) {
        Log.d(TAG, "RX ${raw.toHex()}")
        val decoded = Go2BleProtocol.decodePacket(raw)
        if (decoded == null) {
            // Check for raw sync pattern: "AB BA"
            if (raw.size >= 4 && raw[0] == 0xAB.toByte() && raw[1] == 0xBA.toByte()) {
                Log.i(TAG, "Received raw AB BA sync pattern, sending 7-zero sync response")
                queuePacket(Go2BleProtocol.buildSyncResponsePacket())
            }
            return
        }

        if (decoded.isSync) {
            Log.i(TAG, "Received camera sync packet (${decoded.innerData.size} bytes), replying with sync response")
            queuePacket(Go2BleProtocol.buildSyncResponsePacket())
            return
        }

        if (decoded.isMessage) {
            val inner = Go2BleProtocol.decodeInnerMessage(decoded.innerData) ?: return
            val hexPayload = inner.payload.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Received camera message: code=0x${Integer.toHexString(inner.commandCode)}, seq=${inner.sequence}, size=${inner.payload.size}, payload=$hexPayload")

            when (inner.commandCode) {
                Go2BleProtocol.CMD_CALIBRATE_GYRO -> {
                    parseBatteryUpdate(inner.payload)
                }

                Go2BleProtocol.NOTIF_BATTERY_UPDATE -> {
                    Go2BleProtocol.parseBatteryUpdateNotification(inner.payload)?.let { battery ->
                        _state.update {
                            it.copy(batteryPercent = battery.levelPercent, isCharging = battery.onExternalPower)
                        }
                        Log.i(TAG, "Battery update: ${battery.levelPercent}%, external power=${battery.onExternalPower}")
                    }
                }

                Go2BleProtocol.NOTIF_CURRENT_CAPTURE_STATUS -> {
                    parseCaptureStatus(inner.payload)
                }

                0xC8 -> { // 200 OK Response
                    Log.d(TAG, "Camera returned 200 OK for seq ${inner.sequence}")
                    if (inner.payload.size == 4) {
                        parseBatteryUpdate(inner.payload)
                    }
                }

                in 100..599 -> {
                    Log.w(TAG, "Camera returned status ${inner.commandCode} for seq ${inner.sequence}")
                }
            }
        }
    }

    private fun parseBatteryUpdate(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            var batteryLevel: Int? = null
            var isCharging = false

            var idx = 0
            while (idx < payload.size - 1) {
                val tag = payload[idx].toInt() and 0xFF
                when (tag) {
                    0x08 -> { // Field 1: battery percentage (0-100) on GO 3S
                        val level = payload[idx + 1].toInt() and 0xFF
                        if (level in 0..100) {
                            batteryLevel = level
                        }
                        idx += 2
                    }
                    0x10 -> { // Field 2: charging state (1 = charging, 0 = discharging) on GO 3S
                        isCharging = (payload[idx + 1].toInt() and 0xFF) == 1
                        idx += 2
                    }
                    0x18 -> { // Field 3: charging state on legacy models
                        isCharging = (payload[idx + 1].toInt() and 0xFF) == 1
                        idx += 2
                    }
                    else -> {
                        idx++
                    }
                }
            }

            if (batteryLevel != null) {
                _state.update { it.copy(batteryPercent = batteryLevel, isCharging = isCharging) }
                Log.i(TAG, "Parsed battery update: $batteryLevel%, charging=$isCharging")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing battery update", e)
        }
    }

    private fun parseCaptureStatus(payload: ByteArray) {
        // Notification 0x2010 is CaptureStatus; field 1 (tag 0x08) is the CameraCaptureState.
        if (payload.isEmpty()) return
        try {
            val captureState = if (payload.size >= 2 && payload[0] == 0x08.toByte()) payload[1].toInt() and 0xFF else 0
            val isRecording = Go2BleProtocol.isRecordingCaptureState(captureState)
            captureChangeTimeoutJob?.cancel()
            _state.update { it.copy(isRecording = isRecording, isCaptureStarting = false, isCaptureStopping = false) }
            Log.i(TAG, "Parsed capture status: isRecording=$isRecording (state=$captureState)")
        } catch (_: Exception) {}
    }

    private fun queuePacket(packet: ByteArray) {
        writeChannel.trySend(packet)
    }

    @SuppressLint("MissingPermission")
    private suspend fun performGattWrite(packet: ByteArray) {
        val gatt = activeGatt ?: return
        val char = writeCharacteristic ?: return
        Log.d(TAG, "TX ${packet.toHex()}")

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
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_STOP_CAPTURE, nextSeq())
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)

        if (writeCharacteristic?.uuid == WRITE_CE81) {
            queuePacket(Go2BleProtocol.REMOTE_CMD_SHUTTER)
        }

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
     * Queries camera battery level and charging state.
     */
    fun queryBattery() {
        val inner = Go2BleProtocol.encodeInnerMessage(Go2BleProtocol.CMD_CALIBRATE_GYRO, nextSeq())
        val packet = Go2BleProtocol.encodeMessagePacket(inner)
        queuePacket(packet)
    }

    /**
     * Queues one GPS fix for the camera. Fixes go out as UploadGps batches sized to fit one write.
     */
    fun addGpsFix(
        timestampMs: Long,
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        bearingDegrees: Double,
        altitudeMeters: Double
    ) {
        if (writeCharacteristic == null) return
        val record = Go2BleProtocol.buildGpsRecord(timestampMs, latitude, longitude, speedMps, bearingDegrees, altitudeMeters)
        val batch = synchronized(gpsBatch) {
            gpsBatch.add(record)
            if (gpsBatch.size < Go2BleProtocol.gpsRecordsPerPacket(attMtu)) return
            gpsBatch.toList().also { gpsBatch.clear() }
        }

        val seq = nextSeq()
        val inner = Go2BleProtocol.encodeInnerMessage(
            Go2BleProtocol.CMD_UPLOAD_GPS,
            seq,
            Go2BleProtocol.buildUploadGpsPayload(batch)
        )
        Log.d(TAG, "UploadGps seq=$seq records=${batch.size}")
        queuePacket(Go2BleProtocol.encodeMessagePacket(inner))
        _state.update { it.copy(lastGpsSentTimeMs = System.currentTimeMillis()) }
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
        synchronized(gpsBatch) { gpsBatch.clear() }
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        writeCharacteristic = null
        _state.update {
            it.copy(
                connectionStatus = BleConnectionStatus.DISCONNECTED,
                isRecording = false,
                isCaptureStarting = false,
                isCaptureStopping = false
            )
        }
    }
}
