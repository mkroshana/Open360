package com.open360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.open360.core.ble.CameraWakeUpBleManager
import com.open360.core.network.WifiNetworkBinder
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.theme.Open360Theme

class MainActivity : ComponentActivity() {
    private val cameraViewModel: CameraViewModel by viewModels()

    private lateinit var wifiNetworkBinder: WifiNetworkBinder
    private lateinit var bleManager: CameraWakeUpBleManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            wakeUpAndConnect()
        } else {
            Toast.makeText(
                this,
                "Bluetooth and Location permissions required to wake up camera",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        wifiNetworkBinder = WifiNetworkBinder(this)
        bleManager = CameraWakeUpBleManager(this)

        // Automatically bind process whenever Wi-Fi is connected
        wifiNetworkBinder.startListening {
            cameraViewModel.connect()
        }

        checkAndRequestPermissions()

        setContent {
            Open360Theme {
                CameraScreen(
                    viewModel = cameraViewModel,
                    onWakeUpClick = { onWakeUpRequested() },
                    onOpenWifiSettingsClick = {
                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                )
            }
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return permissions
    }

    private fun checkAndRequestPermissions(): Boolean {
        val missing = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun onWakeUpRequested() {
        if (checkAndRequestPermissions()) {
            wakeUpAndConnect()
        }
    }

    private fun wakeUpAndConnect() {
        Toast.makeText(this, "Broadcasting wake-up beacon to GO 3S...", Toast.LENGTH_SHORT).show()
        // Broadcast BLE wake-up packet to wake the camera up
        bleManager.startWakeUpBroadcast(serialSuffix = "8JY3HF")
        bleManager.scanAndWakeCamera()
        cameraViewModel.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiNetworkBinder.stopListening()
    }
}
