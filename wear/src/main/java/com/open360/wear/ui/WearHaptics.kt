package com.open360.wear.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Distinct eyes-free haptic cues for watch remote actions and camera confirmations. */
enum class WearHapticPattern {
    SHUTTER_TAP,
    RECORDING_STARTED,
    CLIP_SAVED,
    PHOTO_TAKEN,
    FAILURE,
    LINK_ACTION,
    UNLOCK
}

/** Plays custom vibration waveforms on the watch so confirmations can be felt without looking. */
object WearHaptics {

    fun waveformFor(pattern: WearHapticPattern): Pair<LongArray, IntArray> = when (pattern) {
        WearHapticPattern.SHUTTER_TAP -> longArrayOf(0L, 28L) to intArrayOf(0, 180)
        WearHapticPattern.RECORDING_STARTED -> longArrayOf(0L, 45L, 70L, 65L) to intArrayOf(0, 210, 0, 255)
        WearHapticPattern.CLIP_SAVED -> longArrayOf(0L, 75L) to intArrayOf(0, 230)
        WearHapticPattern.PHOTO_TAKEN -> longArrayOf(0L, 35L) to intArrayOf(0, 220)
        WearHapticPattern.FAILURE -> longArrayOf(0L, 55L, 55L, 55L, 55L, 80L) to intArrayOf(0, 255, 0, 255, 0, 255)
        WearHapticPattern.LINK_ACTION -> longArrayOf(0L, 25L) to intArrayOf(0, 150)
        WearHapticPattern.UNLOCK -> longArrayOf(0L, 25L, 45L, 45L) to intArrayOf(0, 140, 0, 210)
    }

    fun trigger(context: Context, pattern: WearHapticPattern) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return@runCatching
            val (timings, amplitudes) = waveformFor(pattern)
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            vibrator.vibrate(effect)
        }
    }
}
