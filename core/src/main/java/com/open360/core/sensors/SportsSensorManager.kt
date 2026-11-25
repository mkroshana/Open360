package com.open360.core.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SensorKind { HEART_RATE, CADENCE, POWER }

/** A sports sensor the app knows: its address, its name and what it measures. */
data class SensorDevice(val address: String, val name: String, val kinds: Set<SensorKind>)

/** A saved sensor as it is now: whether it is linked and its latest readings. */
data class SensorStatus(
    val device: SensorDevice,
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null
)

data class SportsSensorsState(
    val saved: List<SensorStatus> = emptyList(),
    val scanning: Boolean = false,
    val found: List<SensorDevice> = emptyList()
)

/**
 * Keeps links to the sports sensors the user has added (heart rate, cadence, power, over the
 * standard Bluetooth profiles in [SensorProfiles]) and logs their readings with the time they
 * arrived, so a recording's FIT file can be given the readings taken during it.
 *
 * Each saved sensor gets its own link, opened with autoConnect so Android links again whenever
 * it is in range and sending. These are separate from the camera's link. [reconnect] tries a
 * direct link instead, which links within seconds when the sensor is sending.
 */
class SportsSensorManager(private val context: Context) {

    companion object {
        private const val TAG = "SportsSensors"
        private const val PREFS = "open360_sensors"
        private const val PREF_SAVED = "saved_sensors"
        private const val SCAN_MS = 20_000L

        // Readings are kept this long, enough for any recording
        private const val KEEP_READINGS_MS = 4 * 60 * 60 * 1000L

        // A reading older than this no longer shows as the sensor's current value
        private const val CURRENT_FOR_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SportsSensorsState(saved = loadSaved().map { SensorStatus(it) }))
    val state: StateFlow<SportsSensorsState> = _state.asStateFlow()

    // Opened on the main thread, looked at from Bluetooth callbacks
    private val links = ConcurrentHashMap<String, Link>()
    private val lastReadingMs = ConcurrentHashMap<Pair<String, SensorKind>, Long>()
    private var started = false
    private var scanCallback: ScanCallback? = null
    private val scanTimeout = Runnable { stopScan() }

    // Readings by kind, in time order; guarded by `log`
    private val log = mapOf(
        SensorKind.HEART_RATE to ArrayList<SensorReading>(),
        SensorKind.CADENCE to ArrayList<SensorReading>(),
        SensorKind.POWER to ArrayList<SensorReading>()
    )

    /**
     * Starts linking to the saved sensors. Safe to call again, for example once Bluetooth
     * permission has been given: sensors already being linked to are left as they are.
     */
    fun start() {
        started = true
        _state.value.saved.forEach { connect(it.device) }
    }

    fun stop() {
        started = false
        stopScan()
        handler.removeCallbacksAndMessages(null)
        links.values.forEach { it.close() }
        links.clear()
        lastReadingMs.clear()
        _state.update { s ->
            s.copy(
                saved = s.saved.map {
                    it.copy(
                        connected = false,
                        reconnecting = false,
                        heartRateBpm = null,
                        cadenceRpm = null,
                        powerWatts = null
                    )
                }
            )
        }
    }

    /** The readings taken between [fromElapsedMs] and [toElapsedMs] on the monotonic clock. */
    fun readings(fromElapsedMs: Long, toElapsedMs: Long): SensorTrack = synchronized(log) {
        fun between(kind: SensorKind) = log.getValue(kind).filter { it.elapsedMs in fromElapsedMs..toElapsedMs }
        SensorTrack(between(SensorKind.HEART_RATE), between(SensorKind.CADENCE), between(SensorKind.POWER))
    }

    /** Looks for sensors advertising any of the three services, for [SCAN_MS]. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!canUseBluetooth() || scanCallback != null) return
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                val kinds = kindsOf(uuids)
                if (kinds.isEmpty()) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: result.device.address
                val device = SensorDevice(result.device.address, name, kinds)
                _state.update { s ->
                    val existing = s.found.firstOrNull { it.address == device.address }
                    if (existing == null) {
                        s.copy(found = s.found + device)
                    } else if (device.kinds.all { it in existing.kinds }) {
                        s
                    } else {
                        s.copy(found = s.found.map {
                            if (it.address == device.address) it.copy(kinds = it.kinds + device.kinds) else it
                        })
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Sensor scan failed: $errorCode")
                stopScan()
            }
        }
        val filters = listOf(SensorProfiles.HEART_RATE_SERVICE, SensorProfiles.CSC_SERVICE, SensorProfiles.CYCLING_POWER_SERVICE)
            .map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
            scanCallback = callback
            _state.update { it.copy(scanning = true, found = emptyList()) }
            handler.postDelayed(scanTimeout, SCAN_MS)
            Log.i(TAG, "Looking for sports sensors")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the sensor scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        handler.removeCallbacks(scanTimeout)
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {}
        _state.update { it.copy(scanning = false) }
    }

    /** Saves [device] and links to it. */
    fun add(device: SensorDevice) {
        if (_state.value.saved.any { it.device.address == device.address }) return
        _state.update { s -> s.copy(saved = s.saved + SensorStatus(device)) }
        save()
        if (started) connect(device)
    }

    /**
     * Drops [address]'s link and opens a direct one, for a sensor that has not linked again by
     * itself. If that fails (Android gives up after about 30 s) it goes back to waiting in the background.
     */
    fun reconnect(address: String) {
        val device = _state.value.saved.firstOrNull { it.device.address == address }?.device ?: return
        started = true
        links.remove(address)?.close()
        setConnected(address, false)
        if (connect(device, direct = true)) setReconnecting(address, true)
    }

    fun remove(address: String) {
        links.remove(address)?.close()
        _state.update { s -> s.copy(saved = s.saved.filterNot { it.device.address == address }) }
        save()
    }

    private fun kindsOf(services: List<UUID>): Set<SensorKind> = buildSet {
        if (SensorProfiles.HEART_RATE_SERVICE in services) add(SensorKind.HEART_RATE)
        if (SensorProfiles.CSC_SERVICE in services) add(SensorKind.CADENCE)
        if (SensorProfiles.CYCLING_POWER_SERVICE in services) {
            add(SensorKind.POWER)
            add(SensorKind.CADENCE)
        }
    }

    private fun canUseBluetooth(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Opens a link to [device] unless one is open already; true if it opened one. */
    @SuppressLint("MissingPermission")
    private fun connect(device: SensorDevice, direct: Boolean = false): Boolean {
        if (!canUseBluetooth() || links.containsKey(device.address)) return false
        val remote = try {
            bluetoothManager?.adapter?.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false
        links[device.address] = Link(device, remote, direct)
        return true
    }

    /**
     * Logs a reading from a sensor of the device itself, such as a watch's heart rate, with the
     * Bluetooth sensors' readings, so a recording's FIT file is given it the same way.
     */
    fun recordOwn(kind: SensorKind, value: Int) {
        addToLog(kind, value)
    }

    private fun addToLog(kind: SensorKind, value: Int) {
        val now = SystemClock.elapsedRealtime()
        synchronized(log) {
            val list = log.getValue(kind)
            list += SensorReading(now, value)
            while (list.isNotEmpty() && now - list.first().elapsedMs > KEEP_READINGS_MS) list.removeAt(0)
        }
    }

    private fun record(kind: SensorKind, address: String, value: Int) {
        lastReadingMs[address to kind] = SystemClock.elapsedRealtime()
        addToLog(kind, value)
        _state.update { s ->
            s.copy(saved = s.saved.map { status ->
                if (status.device.address != address) status else when (kind) {
                    SensorKind.HEART_RATE -> status.copy(heartRateBpm = value)
                    SensorKind.CADENCE -> status.copy(cadenceRpm = value)
                    SensorKind.POWER -> status.copy(powerWatts = value)
                }
            })
        }
        // A value stops showing once the sensor has gone quiet
        handler.postDelayed({ clearIfStale(kind, address) }, CURRENT_FOR_MS + 100)
    }

    private fun clearIfStale(kind: SensorKind, address: String) {
        val newest = lastReadingMs[address to kind] ?: return
        if (SystemClock.elapsedRealtime() - newest < CURRENT_FOR_MS) return
        _state.update { s ->
            s.copy(saved = s.saved.map { status ->
                if (status.device.address != address) status else when (kind) {
                    SensorKind.HEART_RATE -> status.copy(heartRateBpm = null)
                    SensorKind.CADENCE -> status.copy(cadenceRpm = null)
                    SensorKind.POWER -> status.copy(powerWatts = null)
                }
            })
        }
    }

    private fun setConnected(address: String, connected: Boolean) {
        _state.update { s ->
            s.copy(saved = s.saved.map {
                if (it.device.address == address) {
                    if (connected) it.copy(connected = true)
                    else it.copy(connected = false, heartRateBpm = null, cadenceRpm = null, powerWatts = null)
                } else it
            })
        }
    }

    private fun setReconnecting(address: String, reconnecting: Boolean) {
        _state.update { s ->
            s.copy(saved = s.saved.map {
                if (it.device.address == address) it.copy(reconnecting = reconnecting) else it
            })
        }
    }

    private fun loadSaved(): List<SensorDevice> =
        prefs.getStringSet(PREF_SAVED, emptySet()).orEmpty().mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 3) return@mapNotNull null
            val kinds = parts[2].split(',').mapNotNull { name -> SensorKind.entries.firstOrNull { it.name == name } }.toSet()
            SensorDevice(parts[0], parts[1], kinds)
        }.sortedBy { it.name }

    private fun save() {
        val entries = _state.value.saved.map { s ->
            "${s.device.address}|${s.device.name.replace('|', ' ')}|${s.device.kinds.joinToString(",") { it.name }}"
        }.toSet()
        prefs.edit { putStringSet(PREF_SAVED, entries) }
    }

    /**
     * The link to one sensor. It subscribes to each measurement the sensor offers, one at a time
     * as Android allows only one descriptor write at a time, and turns the notifications into readings.
     */
    @SuppressLint("MissingPermission")
    private inner class Link(
        private val device: SensorDevice,
        private val remote: BluetoothDevice,
        private val direct: Boolean
    ) : BluetoothGattCallback() {
        private val gatt: BluetoothGatt? = remote.connectGatt(context, !direct, this, BluetoothDevice.TRANSPORT_LE)
        private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()
        private val cadence = CadenceCalculator()
        @Volatile private var heardFrom = false

        init {
            Log.i(TAG, "Linking to ${device.name} (${device.address})${if (direct) " directly" else ""}")
        }

        fun close() {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {}
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (links[device.address] !== this) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "${device.name} linked")
                setConnected(device.address, true)
                setReconnecting(device.address, false)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "${device.name} unlinked, status $status")
                setConnected(device.address, false)
                setReconnecting(device.address, false)
                if (!started) return
                if (direct) {
                    // A direct link that failed or ended goes back to waiting in the background
                    close()
                    links[device.address] = Link(device, remote, direct = false)
                } else {
                    // With autoConnect the link is opened again when the sensor is back
                    gatt.connect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (links[device.address] !== this || status != BluetoothGatt.GATT_SUCCESS) return
            pendingSubscriptions.clear()
            for ((service, measurement) in SensorProfiles.MEASUREMENTS) {
                gatt.getService(service)?.getCharacteristic(measurement)?.let { pendingSubscriptions += it }
            }
            Log.i(TAG, "${device.name}: ${pendingSubscriptions.size} measurements to subscribe to")
            subscribeNext(gatt)
        }

        // A Pixel Watch can change its services after the link is up, which leaves what was
        // subscribed to on linking out of date, so the services are looked up and subscribed to again
        override fun onServiceChanged(gatt: BluetoothGatt) {
            if (links[device.address] !== this) return
            Log.i(TAG, "${device.name}: services changed, looking again")
            gatt.discoverServices()
        }

        private fun subscribeNext(gatt: BluetoothGatt) {
            val characteristic = pendingSubscriptions.removeFirstOrNull() ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(SensorProfiles.CLIENT_CONFIG) ?: return subscribeNext(gatt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (links[device.address] !== this) return
            Log.i(TAG, "${device.name}: subscribed to ${descriptor.characteristic.uuid}, status $status")
            subscribeNext(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (links[device.address] !== this) return
            handle(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (links[device.address] !== this) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handle(characteristic.uuid, characteristic.value ?: return)
        }

        private fun handle(uuid: UUID, value: ByteArray) {
            val now = SystemClock.elapsedRealtime()
            if (!heardFrom) {
                heardFrom = true
                Log.i(TAG, "${device.name}: first notification on $uuid: ${value.joinToString("") { "%02x".format(it) }}")
            }
            when (uuid) {
                SensorProfiles.HEART_RATE_MEASUREMENT ->
                    SensorProfiles.parseHeartRate(value)?.takeIf { it > 0 }?.let { record(SensorKind.HEART_RATE, device.address, it) }
                SensorProfiles.CSC_MEASUREMENT ->
                    SensorProfiles.parseCscCrank(value)?.let { crank ->
                        cadence.update(crank, now)?.let { record(SensorKind.CADENCE, device.address, it) }
                    }
                SensorProfiles.CYCLING_POWER_MEASUREMENT ->
                    SensorProfiles.parseCyclingPower(value)?.let { power ->
                        record(SensorKind.POWER, device.address, power.watts)
                        power.crank?.let { crank -> cadence.update(crank, now)?.let { record(SensorKind.CADENCE, device.address, it) } }
                    }
            }
        }
    }
}
