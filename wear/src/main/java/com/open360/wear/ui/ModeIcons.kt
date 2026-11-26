package com.open360.wear.ui

import androidx.annotation.DrawableRes
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.core.ui.Insta360Icons

/** The monochrome glyph resource for each of the camera's modes. */
@DrawableRes
fun modeDrawableRes(mode: CameraMode): Int = Insta360Icons.modeDrawableRes(mode)

/** The monochrome glyph resource for Video or Photo, before the camera reports its sub-mode. */
@DrawableRes
fun modeDrawableRes(choice: CaptureMode): Int = Insta360Icons.captureModeDrawableRes(choice)
