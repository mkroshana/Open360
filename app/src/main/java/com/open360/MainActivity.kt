package com.open360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.open360.ui.camera.CameraScreen
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.theme.Open360Theme

class MainActivity : ComponentActivity() {
    private companion object {
        const val EXTRA_WAKE_AUTHORIZATION_ID = "wake_authorization_id"
    }

    private val cameraViewModel: CameraViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            wakeUpAndConnect()
        } else {
            Toast.makeText(
                this,
                "Bluetooth and Location permissions required to control camera and record GPS",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestPermissions()
        storeWakeAuthorizationId(intent)

        setContent {
            Open360Theme {
                CameraScreen(
                    viewModel = cameraViewModel,
                    onWakeUpClick = { onWakeUpRequested() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        storeWakeAuthorizationId(intent)
    }

    /**
     * Stores the wake-up authorization id when the app is started with one:
     * `adb shell am start -n com.open360/.MainActivity --es wake_authorization_id <id>`.
     * There is no screen for entering it yet.
     */
    private fun storeWakeAuthorizationId(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_WAKE_AUTHORIZATION_ID) ?: return
        val saved = cameraViewModel.bleManager.saveWakeAuthorizationId(id)
        Toast.makeText(
            this,
            if (saved) "Wake-Up ID Saved" else "That Is Not a Valid Wake-Up ID",
            Toast.LENGTH_SHORT
        ).show()
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
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
        Toast.makeText(this, "Waking the Camera...", Toast.LENGTH_SHORT).show()
        cameraViewModel.wakeUpCamera()
    }
}
