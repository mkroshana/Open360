package com.open360.wear

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearThemePreferencesTest {

    @Test
    fun dynamicColorDefaultsToFalseAndPersistsChanges() {
        val prefs = InMemorySharedPreferences()
        val themePrefs = WearThemePreferences(prefs)

        assertFalse(themePrefs.dynamicColor.value)

        themePrefs.setDynamicColor(true)
        assertTrue(themePrefs.dynamicColor.value)
        assertTrue(prefs.getBoolean(WearThemePreferences.PREF_DYNAMIC_COLOR, false))

        // Re-reading from the same SharedPreferences picks up the persisted setting.
        val reloaded = WearThemePreferences(prefs)
        assertTrue(reloaded.dynamicColor.value)

        reloaded.setDynamicColor(false)
        assertFalse(reloaded.dynamicColor.value)
        assertFalse(prefs.getBoolean(WearThemePreferences.PREF_DYNAMIC_COLOR, true))
        assertEquals(false, prefs.all[WearThemePreferences.PREF_DYNAMIC_COLOR])
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            (values[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners -= listener
        }

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toMutableSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                val changedKeys = mutableListOf<String>()
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                    changedKeys += k
                }
                for (listener in listeners.toList()) {
                    for (k in changedKeys) {
                        listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, k)
                    }
                }
            }
        }
    }
}
