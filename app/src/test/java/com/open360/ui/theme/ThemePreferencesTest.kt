package com.open360.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferencesTest {

    @Test
    fun fromStorageKeyParsesKnownAndUnknownValues() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageKey("system"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStorageKey("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorageKey("dark"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageKey(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageKey("unexpected"))
    }
}
