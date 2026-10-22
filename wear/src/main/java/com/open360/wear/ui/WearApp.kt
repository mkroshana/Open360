package com.open360.wear.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.open360.ui.camera.CameraViewModel
import com.open360.wear.SpeedUnit
import com.open360.wear.WatchHeartRate

private object Routes {
    const val REMOTE = "remote"
    const val MODES = "modes"
    const val SETTINGS = "settings"
    const val ADD_SENSOR = "add_sensor"
    const val SENSOR = "sensor"
}

/**
 * The watch app's screens: the remote, the camera's modes, and the settings with the sensors;
 * while the screen is dimmed, the remote's always-on version.
 */
@Composable
fun WearApp(viewModel: CameraViewModel, heartRate: WatchHeartRate, speedUnit: SpeedUnit, isAmbient: Boolean) {
    val context = LocalContext.current
    // The colours the watch face has picked, where the watch offers them
    MaterialTheme(colorScheme = dynamicColorScheme(context) ?: ColorScheme()) {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            if (isAmbient) {
                // Back on the screen that was open once the watch wakes
                AmbientRemote(viewModel)
                return@AppScaffold
            }
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.REMOTE) {
                composable(Routes.REMOTE) {
                    RemoteScreen(
                        viewModel = viewModel,
                        heartRate = heartRate,
                        speedUnit = speedUnit,
                        onOpenModes = { nav.navigate(Routes.MODES) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                    )
                }
                composable(Routes.MODES) {
                    ModesScreen(viewModel = viewModel, onDone = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        heartRate = heartRate,
                        speedUnit = speedUnit,
                        onAddSensor = { nav.navigate(Routes.ADD_SENSOR) },
                        onOpenSensor = { address -> nav.navigate("${Routes.SENSOR}/${Uri.encode(address)}") }
                    )
                }
                composable(Routes.ADD_SENSOR) {
                    AddSensorScreen(sensors = viewModel.sportsSensors, onDone = { nav.popBackStack() })
                }
                composable("${Routes.SENSOR}/{address}") { entry ->
                    SensorScreen(
                        sensors = viewModel.sportsSensors,
                        address = entry.arguments?.getString("address").orEmpty(),
                        onDone = { nav.popBackStack() }
                    )
                }
            }
            WakeUpOnPhoneDialog(viewModel)
            CameraConfirmations(viewModel)
        }
    }
}

/**
 * Wake Up pressed without a wake-up ID opens the set-up. On the watch that is done on the phone,
 * whose ID the watch then takes, so this only says so, and closes once an ID arrives.
 */
@Composable
private fun WakeUpOnPhoneDialog(viewModel: CameraViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val open = uiState.wakeUpSetupOpen
    LaunchedEffect(open, uiState.wakeUpId) {
        if (open && uiState.wakeUpId != null) viewModel.closeWakeUpSetup()
    }
    AlertDialog(
        visible = open && uiState.wakeUpId == null,
        onDismissRequest = { viewModel.closeWakeUpSetup() },
        title = { Text("Set Up Wake Up on Your Phone") },
        text = { Text("Set up Wake Up in Open360 on your phone. The watch takes the same wake-up ID.") },
        // Information only, so one button that closes it
        edgeButton = { AlertDialogDefaults.EdgeButton(onClick = { viewModel.closeWakeUpSetup() }) }
    )
}
