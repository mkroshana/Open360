package com.open360.ui.theme

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer

/**
 * Blurs the screen behind the dialog it is composed in, with Android's window blur behind
 * (`FLAG_BLUR_BEHIND`, Android 12 and later). Android can switch window blurs off at any time,
 * in battery saver, for some video or from Developer options, so the dim is lowered only while
 * blur is on and goes back to the dialog's own otherwise, as Google's guidance asks. Before
 * Android 12, and on devices without window blur, the dialog is left as it was.
 */
@Composable
fun BlurBehindDialog(radius: Dp = 16.dp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    // In pixels; Google advises staying under 150
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }

    DisposableEffect(window, radiusPx) {
        val unblurredDim = window.attributes.dimAmount
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val onBlurEnabled = Consumer<Boolean> { enabled ->
            window.attributes = window.attributes.apply {
                blurBehindRadius = radiusPx
                dimAmount = if (enabled) BLURRED_DIM else unblurredDim
            }
        }
        // Called at once with whether blur is on now, then on each change, on the main thread
        window.windowManager.addCrossWindowBlurEnabledListener(onBlurEnabled)
        onDispose { window.windowManager.removeCrossWindowBlurEnabledListener(onBlurEnabled) }
    }
}

// Google's sample dims by 0.1 over a blur, as the blur already sets the dialog apart
private const val BLURRED_DIM = 0.1f
