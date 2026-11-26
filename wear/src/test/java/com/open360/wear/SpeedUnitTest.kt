package com.open360.wear

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedUnitTest {

    @Test
    fun initialSyncFromPhoneAppliesAndWatchOverrideSurvivesRestart() {
        val prefs = InMemorySharedPreferences()
        val speedUnit = SpeedUnit(prefs)

        assertFalse(speedUnit.inMph.value)

        // First phone sync sets mph on the watch.
        speedUnit.applySyncedFromPhone(true)
        assertTrue(speedUnit.inMph.value)
        assertTrue(prefs.getBoolean(SpeedUnit.PREF_MPH, false))
        assertTrue(prefs.getBoolean(SpeedUnit.PREF_LAST_PHONE_MPH, false))

        // Watch user overrides locally back to km/h.
        speedUnit.set(false)
        assertFalse(speedUnit.inMph.value)

        // After restarting the watch app, Data Layer replays the unchanged phone value (true).
        val restarted = SpeedUnit(prefs)
        assertFalse(restarted.inMph.value)
        restarted.applySyncedFromPhone(true)
        assertFalse(restarted.inMph.value)

        // When the phone actually changes to km/h (false) and later back to mph (true), it propagates.
        restarted.applySyncedFromPhone(false)
        assertFalse(restarted.inMph.value)

        restarted.applySyncedFromPhone(true)
        assertTrue(restarted.inMph.value)
    }

    @Test
    fun initialPhoneSyncOfKmhAllowsSubsequentLocalOverrideToPersist() {
        val prefs = InMemorySharedPreferences()
        val speedUnit = SpeedUnit(prefs)

        speedUnit.applySyncedFromPhone(false)
        assertFalse(speedUnit.inMph.value)
        assertTrue(prefs.contains(SpeedUnit.PREF_LAST_PHONE_MPH))

        speedUnit.set(true)
        assertTrue(speedUnit.inMph.value)

        val restarted = SpeedUnit(prefs)
        restarted.applySyncedFromPhone(false)
        assertTrue(restarted.inMph.value)
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
