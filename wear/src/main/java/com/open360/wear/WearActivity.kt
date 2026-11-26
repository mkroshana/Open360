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
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.ambient.AmbientLifecycleObserver
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ui.configureOpen360Splash
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
        const val PREFS_NAME = "open360_wear_prefs"
        const val KEY_PERMISSION_DISCLOSURE_ACCEPTED = "wear_permission_disclosure_accepted"
        const val STATE_SHOW_PERMISSION_DISCLOSURE = "show_permission_disclosure"
    }

    private val cameraViewModel: CameraViewModel by viewModels()
    private lateinit var phoneSettings: PhoneSettingsReceiver
    private lateinit var heartRate: WatchHeartRate
    private lateinit var speedUnit: SpeedUnit
    private lateinit var themePreferences: WearThemePreferences
    private val isSplashRevealed = mutableStateOf(false)
    private val showPermissionDisclosure = mutableStateOf(false)

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
    ) { _ ->
        heartRate.onPermissionChanged()
        if (hasLocationPermission()) {
            cameraViewModel.gpsEngine.startTracking()
        }
        if (hasBlePermissions()) {
            // What the view model tried on starting, before it was allowed to. Opening the app
            // links to the camera but never switches it on.
            cameraViewModel.connectBle()
            cameraViewModel.sportsSensors.start()
        } else {
            Toast.makeText(this, "Open360 needs Bluetooth and Location to control the camera", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        isSplashRevealed.value = savedInstanceState != null
        configureOpen360Splash(
            splashScreen = splashScreen,
            savedInstanceState = savedInstanceState,
            coldHoldMs = 300L,
            exitDurationMs = 240L
        ) {
            isSplashRevealed.value = true
        }
        lifecycle.addObserver(ambientObserver)
        phoneSettings = PhoneSettingsReceiver(this, ::applyPhoneSettings)
        heartRate = WatchHeartRate(applicationContext, cameraViewModel.sportsSensors)
        speedUnit = SpeedUnit(applicationContext)
        themePreferences = WearThemePreferences(applicationContext)
        // The watch's heart rate is read while the camera is on over the watch's own link, for its
        // FIT file. Not while the camera is linked through the phone, whose FIT it would not reach.
        lifecycleScope.launch {
            cameraViewModel.bleManager.state
                .map { it.connectionStatus == BleConnectionStatus.CONNECTED && it.isCameraAwake }
                .distinctUntilChanged()
                .collect { heartRate.setWanted(it) }
        }
        lifecycleScope.launch {
            heartRate.state
                .map { it.enabled && it.permitted }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) cameraViewModel.refreshBackgroundServiceTypes()
                }
        }
        // The view model puts the Wake-Up set-up forward on a first run without an ID. On the
        // watch the ID comes from the phone, where the set-up is done, so it is not put forward
        // here; Wake Up pressed without an ID still says where to set it up.
        if (savedInstanceState == null && cameraViewModel.uiState.value.wakeUpSetupOpen) {
            cameraViewModel.closeWakeUpSetup()
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val disclosureAccepted = prefs.getBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, false)
        showPermissionDisclosure.value = savedInstanceState?.getBoolean(STATE_SHOW_PERMISSION_DISCLOSURE) ?: false

        val missing = missingPermissions()
        if (savedInstanceState == null && missing.isNotEmpty()) {
            if (disclosureAccepted) {
                permissionLauncher.launch(missing.toTypedArray())
            } else {
                showPermissionDisclosure.value = true
            }
        }
        storeWakeAuthorizationId(intent)
        runDebugRequest(intent)

        setContent {
            WearApp(
                viewModel = cameraViewModel,
                heartRate = heartRate,
                speedUnit = speedUnit,
                themePreferences = themePreferences,
                isAmbient = isAmbient.value,
                revealed = isSplashRevealed.value,
                showPermissionDisclosure = showPermissionDisclosure.value,
                onAcceptPermissionDisclosure = {
                    prefs.edit { putBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, true) }
                    showPermissionDisclosure.value = false
                    val stillMissing = missingPermissions()
                    if (stillMissing.isNotEmpty()) {
                        permissionLauncher.launch(stillMissing.toTypedArray())
                    }
                },
                onSkipPermissionDisclosure = {
                    showPermissionDisclosure.value = false
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_PERMISSION_DISCLOSURE, showPermissionDisclosure.value)
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
     * `adb shell am start -n com.mkroshana.open360/com.open360.wear.WearActivity --es wake_authorization_id <id>`.
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        hasLocationPermission()
    }

    private fun missingPermissions(): List<String> {
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
        return wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }
}
