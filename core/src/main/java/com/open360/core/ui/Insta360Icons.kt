package com.open360.core.ui

import androidx.annotation.DrawableRes
import com.open360.core.R
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import com.open360.core.model.Insta360CameraModel

/**
 * Maps Open360 capture mode, status, and camera hardware vector drawables in `:core`
 * to drawable resource IDs for use across `:app`.
 */
object Insta360Icons {
    @DrawableRes val icBattery: Int = R.drawable.ic_insta_battery
    @DrawableRes val icSdOk: Int = R.drawable.ic_insta_sd_ok
    @DrawableRes val icSdError: Int = R.drawable.ic_insta_sd_error
    @DrawableRes val icBluetooth: Int = R.drawable.ic_insta_bluetooth

    @DrawableRes val icModeBurst: Int = R.drawable.ic_mode_burst
    @DrawableRes val icModeBulletTime: Int = R.drawable.ic_mode_bullettime
    @DrawableRes val icModeHdrVideo: Int = R.drawable.ic_mode_hdr_video
    @DrawableRes val icModePureVideo: Int = R.drawable.ic_mode_pure_video

    /** Returns the tintable monochrome mode glyph for any [CameraMode]. */
    @DrawableRes
    fun modeDrawableRes(mode: CameraMode): Int = when (mode) {
        CameraMode.VIDEO -> R.drawable.ic_mode_video
        CameraMode.FREEFRAME_PRO -> R.drawable.ic_mode_freeframe
        CameraMode.TIMESHIFT -> R.drawable.ic_mode_timeshift
        CameraMode.TIMELAPSE -> R.drawable.ic_mode_timelapse
        CameraMode.SLOW_MOTION -> R.drawable.ic_mode_slow_motion
        CameraMode.LOOP_RECORDING -> R.drawable.ic_mode_loop_recording
        CameraMode.PHOTO -> R.drawable.ic_mode_photo
        CameraMode.HDR_PHOTO -> R.drawable.ic_mode_hdr_photo
        CameraMode.INTERVAL_PHOTO -> R.drawable.ic_mode_interval_photo
        CameraMode.STARLAPSE -> R.drawable.ic_mode_starlapse
    }

    /** Returns the tintable monochrome glyph for [CaptureMode]. */
    @DrawableRes
    fun captureModeDrawableRes(mode: CaptureMode): Int = when (mode) {
        CaptureMode.VIDEO -> R.drawable.ic_mode_video
        CaptureMode.PHOTO -> R.drawable.ic_mode_photo
    }

    /** Returns the theme-aware camera hardware vector silhouette for any [Insta360CameraModel]. */
    @DrawableRes
    fun cameraDrawableRes(
        model: Insta360CameraModel,
        preferBlackVariant: Boolean = false
    ): Int = when (model) {
        Insta360CameraModel.GO_3S -> if (preferBlackVariant) R.drawable.cam_go3s_black else R.drawable.cam_go3s
        Insta360CameraModel.GO_ULTRA -> if (preferBlackVariant) R.drawable.cam_goultra_black else R.drawable.cam_goultra
        Insta360CameraModel.GO_3 -> if (preferBlackVariant) R.drawable.cam_go3_black else R.drawable.cam_go3
        Insta360CameraModel.GO_2 -> R.drawable.cam_go2
        Insta360CameraModel.X5 -> R.drawable.cam_x5
        Insta360CameraModel.X6 -> R.drawable.cam_x6
        Insta360CameraModel.X4_AIR -> R.drawable.cam_x4_air
        Insta360CameraModel.X4 -> R.drawable.cam_x4
        Insta360CameraModel.X3 -> R.drawable.cam_x3
        Insta360CameraModel.ONE_X2 -> R.drawable.cam_onex2
        Insta360CameraModel.ONE_X -> R.drawable.cam_onex
        Insta360CameraModel.ONE_RS -> R.drawable.cam_oners
        Insta360CameraModel.ONE_R -> R.drawable.cam_oner
        Insta360CameraModel.ACE_PRO_3 -> R.drawable.cam_ace_pro_3
        Insta360CameraModel.ACE_PRO_2 -> R.drawable.cam_ace_pro_2
        Insta360CameraModel.ACE_PRO -> R.drawable.cam_ace_pro
        Insta360CameraModel.ACE -> R.drawable.cam_ace
        Insta360CameraModel.SPHERE -> R.drawable.cam_sphere
        Insta360CameraModel.LUNA_ULTRA -> R.drawable.cam_luna_ultra
        Insta360CameraModel.LUNA_PRO -> R.drawable.cam_luna_pro
    }
}
