package com.open360.wear.ui

import android.animation.ValueAnimator
import android.net.Uri
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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
fun WearApp(
    viewModel: CameraViewModel,
    heartRate: WatchHeartRate,
    speedUnit: SpeedUnit,
    isAmbient: Boolean,
    revealed: Boolean = true,
    showPermissionDisclosure: Boolean = false,
    onAcceptPermissionDisclosure: () -> Unit = {},
    onSkipPermissionDisclosure: () -> Unit = {}
) {
    val context = LocalContext.current
    val animatorsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val easeOutCubic = remember { CubicBezierEasing(0.23f, 1f, 0.32f, 1f) }
    val revealAlpha = animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (animatorsEnabled) 240 else 140,
            easing = easeOutCubic
        ),
        label = "wearSplashRevealAlpha"
    )
    val revealScale = animateFloatAsState(
        targetValue = if (revealed || !animatorsEnabled) 1f else 0.96f,
        animationSpec = if (!animatorsEnabled) snap() else tween(durationMillis = 240, easing = easeOutCubic),
        label = "wearSplashRevealScale"
    )
    // The colours the watch face has picked, where the watch offers them
    MaterialTheme(colorScheme = dynamicColorScheme(context) ?: ColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = revealAlpha.value
                        scaleX = revealScale.value
                        scaleY = revealScale.value
                    }
            ) {
                AppScaffold {
                    val nav = rememberSwipeDismissableNavController()
                    if (isAmbient) {
                        // Back on the screen that was open once the watch wakes
                        AmbientRemote(viewModel)
                        return@AppScaffold
                    }
                    if (showPermissionDisclosure) {
                        WearPermissionDisclosureScreen(
                            onContinue = onAcceptPermissionDisclosure,
                            onSkip = onSkipPermissionDisclosure
                        )
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

@Composable
private fun WearPermissionDisclosureScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) { Text("Permissions") }
            }
            item {
                DisclosureNote(
                    title = "Location & Background GPS",
                    detail = "Logs GPS speed and routes for camera clips, including when connected with the screen off."
                )
            }
            item {
                DisclosureNote(
                    title = "Body Sensors",
                    detail = "Reads watch heart rate during recordings for local FIT telemetry."
                )
            }
            item {
                DisclosureNote(
                    title = "Bluetooth & Notifications",
                    detail = "Connects to your Insta360 camera and sensors, and keeps the link active."
                )
            }
            item {
                Text(
                    text = "All data stays on your watch, phone, and camera; nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            item {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text("Continue") }
                )
            }
            item {
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text("Skip") }
                )
            }
        }
    }
}

@Composable
private fun DisclosureNote(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
