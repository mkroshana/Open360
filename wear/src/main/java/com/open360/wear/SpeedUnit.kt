package com.open360.wear

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The unit the watch shows speed in, chosen in Settings and remembered. */
class SpeedUnit(context: Context) {

    private companion object {
        // Shared with WatchHeartRate's setting
        const val PREFS = "open360_watch_settings"
        const val PREF_MPH = "speed_in_mph"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _inMph = MutableStateFlow(prefs.getBoolean(PREF_MPH, false))
    val inMph: StateFlow<Boolean> = _inMph.asStateFlow()

    fun set(inMph: Boolean) {
        prefs.edit { putBoolean(PREF_MPH, inMph) }
        _inMph.value = inMph
    }
}
