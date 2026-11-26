package com.open360.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The unit the watch shows speed in, chosen in Settings and remembered. */
class SpeedUnit internal constructor(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    companion object {
        // Shared with WatchHeartRate's setting
        internal const val PREFS = "open360_watch_settings"
        internal const val PREF_MPH = "speed_in_mph"
        internal const val PREF_LAST_PHONE_MPH = "last_phone_speed_in_mph"
    }

    private val _inMph = MutableStateFlow(prefs.getBoolean(PREF_MPH, false))
    val inMph: StateFlow<Boolean> = _inMph.asStateFlow()

    fun set(inMph: Boolean) {
        prefs.edit { putBoolean(PREF_MPH, inMph) }
        _inMph.value = inMph
    }

    /**
     * Applies a speed unit synced from the phone app while keeping any local watch override
     * intact across restarts when the phone re-publishes the same value.
     */
    fun applySyncedFromPhone(inMph: Boolean) {
        val hasLastPhoneValue = prefs.contains(PREF_LAST_PHONE_MPH)
        val lastPhoneValue = prefs.getBoolean(PREF_LAST_PHONE_MPH, false)
        val shouldUpdateLocal = !hasLastPhoneValue || inMph != lastPhoneValue
        prefs.edit {
            putBoolean(PREF_LAST_PHONE_MPH, inMph)
            if (shouldUpdateLocal) {
                putBoolean(PREF_MPH, inMph)
            }
        }
        if (shouldUpdateLocal) {
            _inMph.value = inMph
        }
    }
}
