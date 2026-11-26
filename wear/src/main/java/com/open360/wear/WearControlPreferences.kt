package com.open360.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watch control preference stored alongside [SpeedUnit], [WatchHeartRate], and
 * [WearThemePreferences] in `open360_watch_settings`. Defaults to `true` so a physical watch
 * hardware button triggers the shutter unless turned off in Settings.
 */
class WearControlPreferences internal constructor(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        internal const val PREFS_NAME = "open360_watch_settings"
        internal const val PREF_HARDWARE_BUTTON_SHUTTER = "hardware_button_shutter"
    }

    private val _hardwareButtonShutter = MutableStateFlow(
        prefs.getBoolean(PREF_HARDWARE_BUTTON_SHUTTER, true)
    )
    val hardwareButtonShutter: StateFlow<Boolean> = _hardwareButtonShutter.asStateFlow()

    fun setHardwareButtonShutter(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_HARDWARE_BUTTON_SHUTTER, enabled) }
        _hardwareButtonShutter.value = enabled
    }
}
