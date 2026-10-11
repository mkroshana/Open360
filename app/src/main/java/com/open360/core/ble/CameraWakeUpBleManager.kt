package com.open360.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import com.open360.core.protocol.PacketFramer
import java.util.UUID

class CameraWakeUpBleManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraWakeUpBle"

        // Insta360 GO 3 / GO 3S / X3 / X4 BLE Service & Characteristic UUIDs
        val SERVICE_CE80: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")
        val WRITE_CE81: UUID = UUID.fromString("0000ce81-0000-1000-8000-00805f9b34fb")
        val NOTIFY_CE82: UUID = UUID.fromString("0000ce82-0000-1000-8000-00805f9b34fb")

        // Legacy Insta360 BLE Service & Characteristic UUIDs (fallback)
        val SERVICE_BE80: UUID = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")
        val WRITE_BE81: UUID = UUID.fromString("0000be81-0000-1000-8000-00805f9b34fb")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isAdvertising = false

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canAdvertise(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    private fun canScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Builds the Insta360 'ORBIT' wake-up manufacturer packet (24 bytes).
     * Android adds the 2-byte Manufacturer ID (0x004c) automatically.
     * Total on-air payload = 26 bytes.
     * @param serialSuffix 6-character camera serial suffix (e.g. "8JY3HF")
     */
    fun buildWakeUpPayload(serialSuffix: String = "8JY3HF"): ByteArray {
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

        // Serial suffix (6 characters in ASCII)
        val suffixBytes = serialSuffix.take(6).toByteArray(Charsets.US_ASCII)
        for (i in suffixBytes.indices) {
            payload[12 + i] = suffixBytes[i]
        }

        payload[18] = 0x00
        payload[19] = 0x00
        payload[20] = 0x00
        payload[21] = 0x00
        payload[22] = 0xe4.toByte()
        payload[23] = 0x01
        return payload
    }

    /**
     * Broadcasts the BLE wake-up packet to power on camera and turn on Wi-Fi.
     */
    @SuppressLint("MissingPermission")
    fun startWakeUpBroadcast(serialSuffix: String = "8JY3HF", onComplete: (() -> Unit)? = null) {
        if (!canAdvertise()) {
            Log.w(TAG, "Cannot advertise: missing BLUETOOTH_ADVERTISE permission")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled or unavailable")
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(8000)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x004c, buildWakeUpPayload(serialSuffix))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                Log.i(TAG, "BLE wake-up broadcast started successfully")
                onComplete?.invoke()
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                Log.e(TAG, "BLE wake-up broadcast failed with error $errorCode")
            }
        }

        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising: check permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
        }
    }

    /**
     * Scans for the camera via BLE GATT and writes the wake-up / screen-on sequence.
     */
    @SuppressLint("MissingPermission")
    fun scanAndWakeCamera(onCameraWoken: (() -> Unit)? = null) {
        if (!canScan()) {
            Log.w(TAG, "Cannot scan: missing BLUETOOTH_SCAN/CONNECT permissions")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled or unavailable")
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BluetoothLeScanner not available")
            return
        }

        if (isScanning) return
        isScanning = true

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_CE80)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_BE80)).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                Log.i(TAG, "Found Insta360 camera BLE: ${device.name ?: device.address}")

                try {
                    scanner.stopScan(this)
                    isScanning = false
                } catch (_: Exception) {}

                // Connect GATT
                activeGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            Log.i(TAG, "Connected to camera GATT, discovering services...")
                            gatt?.discoverServices()
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                            Log.e(TAG, "GATT service discovery failed with status $status")
                            return
                        }

                        Log.i(TAG, "=== Discovered ${gatt.services.size} GATT Services ===")
                        gatt.services.forEach { s ->
                            Log.i(TAG, "Service: ${s.uuid}")
                            s.characteristics.forEach { c ->
                                Log.i(TAG, "  Char: ${c.uuid} (props: 0x${Integer.toHexString(c.properties)})")
                            }
                        }

                        // Try CE80 (GO 3/3S modern) first, then BE80 (legacy)
                        val ce80Service = gatt.getService(SERVICE_CE80)
                        val be80Service = gatt.getService(SERVICE_BE80)

                        val writeChar = ce80Service?.getCharacteristic(WRITE_CE81)
                            ?: be80Service?.getCharacteristic(WRITE_BE81)

                        val notifyChar = ce80Service?.getCharacteristic(NOTIFY_CE82)

                        if (notifyChar != null) {
                            gatt.setCharacteristicNotification(notifyChar, true)
                            val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                Log.i(TAG, "Enabled BLE notifications on: ${notifyChar.uuid}")
                            }
                        }

                        if (writeChar != null) {
                            // Wake-up screen / power command: fc ef fe 86 00 03 01 00 00
                            val wakeCmd = byteArrayOf(
                                0xfc.toByte(), 0xef.toByte(), 0xfe.toByte(),
                                0x86.toByte(), 0x00, 0x03, 0x01, 0x00, 0x00
                            )
                            writeChar.value = wakeCmd
                            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            gatt.writeCharacteristic(writeChar)
                            Log.i(TAG, "Sent GATT wake-up command to camera (${writeChar.uuid})")
                            onCameraWoken?.invoke()
                        } else {
                            Log.w(TAG, "No writable characteristic found in CE80 or BE80")
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        Log.i(TAG, "GATT write finished on ${characteristic.uuid}, status: $status")
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                    ) {
                        Log.i(TAG, "GATT notification on ${characteristic.uuid}: ${characteristic.value?.size} bytes")
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                    ) {
                        Log.i(TAG, "GATT notification on ${characteristic.uuid}: ${value.size} bytes")
                    }
                })
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                Log.e(TAG, "BLE scan failed with error $errorCode")
            }
        }

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            isScanning = false
            Log.e(TAG, "SecurityException during BLE scan", e)
        } catch (e: Exception) {
            isScanning = false
            Log.e(TAG, "Failed to start BLE scan", e)
        }
    }
}
