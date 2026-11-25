package com.open360

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.open360.core.connection.ConnectionPreferences
import com.open360.core.connection.DirectCameraControlBridge
import com.open360.core.ui.configureOpen360Splash
import com.open360.core.watch.WatchSettingsPublisher
import com.open360.ui.Open360App
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.footage.FootageViewModel
import com.open360.ui.liveview.LiveViewViewModel
import com.open360.ui.theme.AppThemeMode
import com.open360.ui.theme.Open360Theme
import com.open360.ui.theme.SplashRevealContainer
import com.open360.ui.theme.ThemePreferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object {
        const val EXTRA_WAKE_AUTHORIZATION_ID = "wake_authorization_id"
        const val STATE_SHOW_PERMISSION_DISCLOSURE = "show_permission_disclosure"
    }

    private val cameraViewModel: CameraViewModel by viewModels()
    private val footageViewModel: FootageViewModel by viewModels()
    private val liveViewViewModel: LiveViewViewModel by viewModels()
    private lateinit var connectionPreferences: ConnectionPreferences
    private lateinit var directBridge: DirectCameraControlBridge
    private lateinit var themePrefs: ThemePreferences
    private val isSplashRevealed = mutableStateOf(false)
    private val showPermissionDisclosure = mutableStateOf(false)

    private var pendingCameraAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasLocationPermissions()) {
            cameraViewModel.gpsEngine.startTracking()
        }
        val pending = pendingCameraAction
        pendingCameraAction = null
        if (hasBlePermissions()) {
            cameraViewModel.sportsSensors.start()
            if (pending != null) {
                pending()
            } else {
                cameraViewModel.connectBle()
            }
        } else if (pending != null) {
            Toast.makeText(
                this,
                "Bluetooth permission is required to connect to the camera",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        isSplashRevealed.value = savedInstanceState != null
        configureOpen360Splash(splashScreen, savedInstanceState) {
            isSplashRevealed.value = true
        }
        enableEdgeToEdge()
        connectionPreferences = ConnectionPreferences(applicationContext)
        directBridge = DirectCameraControlBridge(
            applicationContext,
            footageViewModel.wifiConnector,
            connectionPreferences
        )
        footageViewModel.cameraLink = cameraViewModel.bleManager
        footageViewModel.connectionPreferences = connectionPreferences
        liveViewViewModel.cameraLink = cameraViewModel.bleManager
        liveViewViewModel.wifiConnector = footageViewModel.wifiConnector
        liveViewViewModel.connectionPreferences = connectionPreferences
        liveViewViewModel.directBridge = directBridge

        themePrefs = ThemePreferences(this)
        showPermissionDisclosure.value = savedInstanceState?.getBoolean(STATE_SHOW_PERMISSION_DISCLOSURE) ?: false

        val missing = missingPermissions()
        if (savedInstanceState == null) {
            handleUsbAttachedIntent(intent)
            if (missing.isNotEmpty()) {
                if (themePrefs.permissionDisclosureAccepted) {
                    permissionLauncher.launch(missing.toTypedArray())
                } else {
                    showPermissionDisclosure.value = true
                }
            }
        }
        storeWakeAuthorizationId(intent)
        runDebugRequest(intent)
        shareConnectionSettingsWithWatch()

        setContent {
            var themeMode by remember { mutableStateOf(themePrefs.themeMode) }
            var dynamicColor by remember { mutableStateOf(themePrefs.dynamicColor) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }

            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                )
                onDispose {}
            }

            Open360Theme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                SplashRevealContainer(revealed = isSplashRevealed.value) {
                    Open360App(
                        cameraViewModel = cameraViewModel,
                        footageViewModel = footageViewModel,
                        liveViewViewModel = liveViewViewModel,
                        connectionPreferences = connectionPreferences,
                        directBridge = directBridge,
                        themeMode = themeMode,
                        dynamicColor = dynamicColor,
                        onSetThemeMode = { mode ->
                            themeMode = mode
                            themePrefs.themeMode = mode
                        },
                        onToggleDynamicColor = { enabled ->
                            dynamicColor = enabled
                            themePrefs.dynamicColor = enabled
                        },
                        onEnsureCameraPermissions = { action -> ensureCameraPermissions(action) },
                        showPermissionDisclosure = showPermissionDisclosure.value,
                        onAcceptPermissionDisclosure = {
                            themePrefs.permissionDisclosureAccepted = true
                            showPermissionDisclosure.value = false
                            val stillMissing = missingPermissions()
                            if (stillMissing.isNotEmpty()) {
                                permissionLauncher.launch(stillMissing.toTypedArray())
                            } else {
                                val pending = pendingCameraAction
                                pendingCameraAction = null
                                if (hasLocationPermissions()) {
                                    cameraViewModel.gpsEngine.startTracking()
                                }
                                if (hasBlePermissions()) {
                                    cameraViewModel.sportsSensors.start()
                                    pending?.invoke()
                                }
                            }
                        },
                        onDismissPermissionDisclosure = {
                            pendingCameraAction = null
                            showPermissionDisclosure.value = false
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_PERMISSION_DISCLOSURE, showPermissionDisclosure.value)
    }

    override fun onStart() {
        super.onStart()
        cameraViewModel.setAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            cameraViewModel.setAppVisible(false)
            liveViewViewModel.stopLiveView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            directBridge.disconnect()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachedIntent(intent)
        storeWakeAuthorizationId(intent)
        runDebugRequest(intent)
    }

    private fun handleUsbAttachedIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val label = runCatching { device?.productName }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: device?.let { String.format(Locale.US, "USB %04X:%04X", it.vendorId, it.productId) }
            ?: "USB camera device"
        Toast.makeText(this, "$label attached", Toast.LENGTH_SHORT).show()
    }

    /**
     * Debug builds only, for trying things out from a PC: `--ez debug_wake true` presses Wake Up,
     * and `--ei debug_command <code>` with an optional `--es debug_payload <hex>` sends the
     * camera that command.
     */
    private fun runDebugRequest(intent: Intent?) {
        if (intent == null || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.getBooleanExtra("debug_wake", false)) onWakeUpRequested()
        val command = intent.getIntExtra("debug_command", -1)
        if (command >= 0) {
            cameraViewModel.bleManager.sendCommandForTesting(command, intent.getStringExtra("debug_payload").orEmpty())
        }
    }

    /**
     * Stores the wake-up authorization id when the app is started with one:
     * `adb shell am start -n com.mkroshana.open360/com.open360.MainActivity --es wake_authorization_id <id>`.
     * It can also be entered on the Wake-Up ID card.
     */
    private fun storeWakeAuthorizationId(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_WAKE_AUTHORIZATION_ID) ?: return
        val saved = cameraViewModel.saveWakeUpId(id)
        Toast.makeText(
            this,
            if (saved) "Wake-Up ID Saved" else "That Is Not a Valid Wake-Up ID",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Hands the wake-up ID and the camera's serial suffix to the watch app whenever either may
     * have changed: the ID when it is set or replaced, the serial when a camera is linked to.
     */
    private fun shareConnectionSettingsWithWatch() {
        val publisher = WatchSettingsPublisher(this)
        val ble = cameraViewModel.bleManager
        lifecycleScope.launch {
            cameraViewModel.uiState
                .map { it.wakeUpId to it.bleState.deviceName }
                .distinctUntilChanged()
                .collect { (wakeUpId, _) -> publisher.publish(wakeUpId, ble.getSavedSerialSuffix()) }
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
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return permissions
    }

    private fun hasLocationPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        hasLocationPermissions()
    }

    private fun missingPermissions(): List<String> = getRequiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun ensureCameraPermissions(action: () -> Unit) {
        if (hasBlePermissions()) {
            action()
            return
        }
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            pendingCameraAction = action
            if (themePrefs.permissionDisclosureAccepted) {
                permissionLauncher.launch(needed.toTypedArray())
            } else {
                showPermissionDisclosure.value = true
            }
        } else {
            action()
        }
    }

    private fun onWakeUpRequested() {
        ensureCameraPermissions { wakeUpAndConnect() }
    }

    private fun wakeUpAndConnect() {
        // Without a wake-up ID the camera is only looked for, and the set-up is shown
        if (cameraViewModel.uiState.value.wakeUpId != null) {
            Toast.makeText(this, "Waking the Camera...", Toast.LENGTH_SHORT).show()
        }
        cameraViewModel.wakeUpCamera()
    }
}
