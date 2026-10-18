package com.open360.wear

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import com.open360.core.ble.BleConnectionStatus
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.ui.WearApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The watch app. It links to the camera and sends it the watch's own GPS through the same
 * CameraViewModel as the phone app; only the connection settings come from the phone.
 */
class WearActivity : ComponentActivity() {
    private companion object {
        const val EXTRA_WAKE_AUTHORIZATION_ID = "wake_authorization_id"
    }

    private val cameraViewModel: CameraViewModel by viewModels()
    private lateinit var phoneSettings: PhoneSettingsReceiver
    private lateinit var heartRate: WatchHeartRate

    // When the screen dims the remote stays up in the watch's low-power always-on mode, so a
    // glance shows whether the camera is recording
    private val isAmbient = mutableStateOf(false)
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient.value = true
            }

            override fun onExitAmbient() {
                isAmbient.value = false
            }
        }
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        heartRate.onPermissionChanged()
        // The heart rate is optional; the camera needs the rest
        if (results.filterKeys { it != Manifest.permission.BODY_SENSORS }.values.all { it }) {
            // What the view model tried on starting, before it was allowed to. Opening the app
            // links to the camera but never switches it on.
            cameraViewModel.gpsEngine.startTracking()
            cameraViewModel.connectBle()
            cameraViewModel.sportsSensors.start()
        } else {
            Toast.makeText(this, "Open360 needs Bluetooth and Location to control the camera", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        phoneSettings = PhoneSettingsReceiver(this, ::applyPhoneSettings)
        heartRate = WatchHeartRate(applicationContext, cameraViewModel.sportsSensors)
        // The watch's heart rate is read while the camera is on, for the FIT file
        lifecycleScope.launch {
            cameraViewModel.uiState
                .map { it.bleState.connectionStatus == BleConnectionStatus.CONNECTED && it.bleState.isCameraAwake }
                .distinctUntilChanged()
                .collect { heartRate.setWanted(it) }
        }
        // The view model puts the Wake-Up set-up forward on a first run without an ID. On the
        // watch the ID comes from the phone, where the set-up is done, so it is not put forward
        // here; Wake Up pressed without an ID still says where to set it up.
        if (savedInstanceState == null && cameraViewModel.uiState.value.wakeUpSetupOpen) {
            cameraViewModel.closeWakeUpSetup()
        }

        requestMissingPermissions()
        storeWakeAuthorizationId(intent)
        runDebugRequest(intent)

        setContent {
            WearApp(viewModel = cameraViewModel, heartRate = heartRate, isAmbient = isAmbient.value)
        }
    }

    override fun onStart() {
        super.onStart()
        cameraViewModel.setAppVisible(true)
        phoneSettings.start()
    }

    override fun onStop() {
        super.onStop()
        cameraViewModel.setAppVisible(false)
        phoneSettings.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartRate.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        storeWakeAuthorizationId(intent)
        runDebugRequest(intent)
    }

    /**
     * Takes the phone's wake-up ID, which the camera already allows, and its camera. An ID the
     * watch already holds is left alone, as storing one asks a camera that is on about it.
     */
    private fun applyPhoneSettings(wakeUpId: String?, serialSuffix: String?) {
        serialSuffix?.let { cameraViewModel.bleManager.saveSerialSuffix(it) }
        if (wakeUpId != null && wakeUpId != cameraViewModel.uiState.value.wakeUpId) {
            cameraViewModel.saveWakeUpId(wakeUpId)
        }
    }

    /**
     * Stores a wake-up ID given on starting, for when the phone's cannot be handed over:
     * `adb shell am start -n com.open360/com.open360.wear.WearActivity --es wake_authorization_id <id>`.
     */
    private fun storeWakeAuthorizationId(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_WAKE_AUTHORIZATION_ID) ?: return
        val saved = cameraViewModel.saveWakeUpId(id)
        Toast.makeText(this, if (saved) "Wake-Up ID Saved" else "That Is Not a Valid Wake-Up ID", Toast.LENGTH_SHORT).show()
    }

    /**
     * Debug builds only, as in the phone app: `--ez debug_wake true` presses Wake Up, and
     * `--ei debug_command <code>` with an optional `--es debug_payload <hex>` sends the camera
     * that command.
     */
    private fun runDebugRequest(intent: Intent?) {
        if (intent == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.getBooleanExtra("debug_wake", false)) cameraViewModel.wakeUpCamera()
        val command = intent.getIntExtra("debug_command", -1)
        if (command >= 0) {
            cameraViewModel.bleManager.sendCommandForTesting(command, intent.getStringExtra("debug_payload").orEmpty())
        }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.BODY_SENSORS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
