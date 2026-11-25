package com.open360.ui.theme

import android.content.Context
import androidx.core.content.edit

enum class AppThemeMode(val storageKey: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorageKey(key: String?): AppThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: AppThemeMode
        get() = AppThemeMode.fromStorageKey(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit { putString(KEY_THEME_MODE, value.storageKey) }
        }

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) {
            prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, value) }
        }

    var permissionDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, value) }
        }

    private companion object {
        const val PREFS_NAME = "open360_ui_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_PERMISSION_DISCLOSURE_ACCEPTED = "permission_disclosure_accepted"
    }
}
