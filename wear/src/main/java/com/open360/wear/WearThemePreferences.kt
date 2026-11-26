package com.open360.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watch theme preference stored alongside [SpeedUnit] and [WatchHeartRate] in
 * `open360_watch_settings`. Defaults to Open360's curated editorial dark palette (`false`),
 * letting users opt into watch-face dynamic theming when supported.
 */
class WearThemePreferences internal constructor(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    companion object {
        internal const val PREFS = "open360_watch_settings"
        internal const val PREF_DYNAMIC_COLOR = "dynamic_color"
    }

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(PREF_DYNAMIC_COLOR, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_DYNAMIC_COLOR, enabled) }
        _dynamicColor.value = enabled
    }
}
