package com.open360.wear

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ui.configureOpen360Splash
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.ui.RemoteText
import com.open360.wear.ui.WearApp
import com.open360.wear.ui.WearHapticPattern
import com.open360.wear.ui.WearHaptics
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
        const val KEY_CORE_PERMISSIONS_PROMPTED = "wear_core_permissions_prompted"
        const val STATE_SHOW_PERMISSION_DISCLOSURE = "show_permission_disclosure"
    }

    private val cameraViewModel: CameraViewModel by viewModels()
    private lateinit var phoneSettings: PhoneSettingsReceiver
    private lateinit var heartRate: WatchHeartRate
    private lateinit var speedUnit: SpeedUnit
    private lateinit var themePreferences: WearThemePreferences
    private lateinit var controlPreferences: WearControlPreferences
    private val isSplashRevealed = mutableStateOf(false)
    private val showPermissionDisclosure = mutableStateOf(false)
    private val missingBlePermissions = mutableStateOf(false)
    private val missingLocationPermission = mutableStateOf(false)
    private var hasPromptedCorePermissions = false

    // When the screen dims the remote stays up in the watch's low-power always-on mode, so a
    // glance shows whether the camera is recording
    private val isAmbient = mutableStateOf(false)
    private val burnInProtection = mutableStateOf(false)
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient.value = true
                burnInProtection.value = ambientDetails.burnInProtectionRequired
            }

            override fun onExitAmbient() {
                isAmbient.value = false
                burnInProtection.value = false
            }
        }
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionStates()
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
        controlPreferences = WearControlPreferences(applicationContext)
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
        lifecycleScope.launch {
            cameraViewModel.uiState.collect { state ->
                WearOngoingStatus.update(
                    applicationContext,
                    state.bleState,
                    state.isRecording,
                    state.recordingDurationSeconds,
                    state.linkedThroughPeer
                )
                val resolvedCameraName = state.bleState.deviceName?.takeIf { it.isNotBlank() }
                    ?: cameraViewModel.bleManager.getSavedSerialSuffix().takeIf { it.isNotBlank() }?.let { "GO 3S $it" }
                    ?: "Open360"
                val statusLabel = if (state.isRecording) {
                    "Recording"
                } else {
                    RemoteText.status(state.bleState, false, 0)
                }
                WearSnapshotStore.writeAndNotify(
                    applicationContext,
                    WearCameraSnapshot(
                        cameraName = resolvedCameraName,
                        statusLabel = statusLabel,
                        batteryPercent = state.bleState.batteryPercent,
                        isRecording = state.isRecording,
                        isConnected = state.bleState.connectionStatus == BleConnectionStatus.CONNECTED || state.linkedThroughPeer,
                        isAwake = state.bleState.isCameraAwake,
                        hasWakeUpId = state.wakeUpId != null,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
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
        hasPromptedCorePermissions = prefs.getBoolean(KEY_CORE_PERMISSIONS_PROMPTED, false)
        showPermissionDisclosure.value = savedInstanceState?.getBoolean(STATE_SHOW_PERMISSION_DISCLOSURE) ?: false

        val missing = missingPermissions()
        if (savedInstanceState == null && missing.isNotEmpty()) {
            if (disclosureAccepted) {
                if (missingCorePermissions().isNotEmpty()) markCorePermissionsPrompted()
                permissionLauncher.launch(missing.toTypedArray())
            } else {
                showPermissionDisclosure.value = true
            }
        }
        refreshPermissionStates()
        storeWakeAuthorizationId(intent)
        runDebugRequest(intent)
        handleQuickAction(intent)

        setContent {
            WearApp(
                viewModel = cameraViewModel,
                heartRate = heartRate,
                speedUnit = speedUnit,
                themePreferences = themePreferences,
                controlPreferences = controlPreferences,
                isAmbient = isAmbient.value,
                burnInProtection = burnInProtection.value,
                revealed = isSplashRevealed.value,
                showPermissionDisclosure = showPermissionDisclosure.value,
                missingBlePermissions = missingBlePermissions.value,
                missingLocationPermission = missingLocationPermission.value,
                onRequestPermissions = ::requestCorePermissions,
                onAcceptPermissionDisclosure = {
                    prefs.edit { putBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, true) }
                    showPermissionDisclosure.value = false
                    val stillMissing = missingPermissions()
                    if (stillMissing.isNotEmpty()) {
                        if (missingCorePermissions().isNotEmpty()) markCorePermissionsPrompted()
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

    override fun onResume() {
        super.onResume()
        val gainedLocation = missingLocationPermission.value && hasLocationPermission()
        val gainedBle = missingBlePermissions.value && hasBlePermissions()
        refreshPermissionStates()
        if (gainedLocation || gainedBle) {
            heartRate.onPermissionChanged()
        }
        if (gainedLocation) {
            cameraViewModel.gpsEngine.startTracking()
        }
        if (gainedBle) {
            cameraViewModel.connectBle()
            cameraViewModel.sportsSensors.start()
        }
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
        handleQuickAction(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (
            keyCode in setOf(KeyEvent.KEYCODE_STEM_1, KeyEvent.KEYCODE_STEM_2, KeyEvent.KEYCODE_STEM_3) &&
            event?.repeatCount == 0 &&
            controlPreferences.hardwareButtonShutter.value &&
            !showPermissionDisclosure.value
        ) {
            val state = cameraViewModel.uiState.value
            val ble = state.bleState
            if ((ble.connectionStatus == BleConnectionStatus.CONNECTED || state.linkedThroughPeer) && ble.isCameraAwake) {
                WearHaptics.trigger(this, WearHapticPattern.SHUTTER_TAP)
                cameraViewModel.onShutterClicked()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Takes the phone's wake-up ID, which the camera already allows, and its camera. An ID the
     * watch already holds is left alone, as storing one asks a camera that is on about it.
     */
    private fun applyPhoneSettings(wakeUpId: String?, serialSuffix: String?, speedInMph: Boolean?) {
        serialSuffix?.let { cameraViewModel.bleManager.saveSerialSuffix(it) }
        if (wakeUpId != null && wakeUpId != cameraViewModel.uiState.value.wakeUpId) {
            cameraViewModel.saveWakeUpId(wakeUpId)
        }
        speedInMph?.let { speedUnit.applySyncedFromPhone(it) }
    }

    private fun refreshPermissionStates() {
        missingBlePermissions.value = !hasBlePermissions()
        missingLocationPermission.value = !hasLocationPermission()
    }

    private fun markCorePermissionsPrompted() {
        hasPromptedCorePermissions = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_CORE_PERMISSIONS_PROMPTED, true)
        }
    }

    private fun requestCorePermissions() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, true)
        }
        showPermissionDisclosure.value = false
        val coreMissing = missingCorePermissions()
        if (coreMissing.isEmpty()) {
            refreshPermissionStates()
            return
        }
        if (hasPromptedCorePermissions && coreMissing.none { shouldShowRequestPermissionRationale(it) }) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } else {
            markCorePermissionsPrompted()
            permissionLauncher.launch(coreMissing.toTypedArray())
        }
    }

    private fun handleQuickAction(intent: Intent?) {
        if (intent?.getStringExtra(WearSnapshotStore.EXTRA_QUICK_ACTION) != WearSnapshotStore.ACTION_WAKE_UP) {
            return
        }
        intent.removeExtra(WearSnapshotStore.EXTRA_QUICK_ACTION)
        if (hasBlePermissions()) {
            cameraViewModel.wakeUpCamera()
        } else {
            requestCorePermissions()
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

    private fun missingCorePermissions(): List<String> {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        return wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
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
