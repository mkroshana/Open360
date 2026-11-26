package com.open360.wear

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearSnapshotStoreTest {

    @Test
    fun readReturnsDefaultsWhenPreferencesAreEmpty() {
        val prefs = InMemorySharedPreferences()

        assertEquals(WearCameraSnapshot(), WearSnapshotStore.read(prefs))
    }

    @Test
    fun roundTripWriteAndReadPreservesAllFieldsIncludingNullBattery() {
        val prefs = InMemorySharedPreferences()
        val withBattery = WearCameraSnapshot(
            cameraName = "GO 3S 4812",
            statusLabel = "Recording 01:12",
            batteryPercent = 84,
            isRecording = true,
            isConnected = true,
            isAwake = true,
            hasWakeUpId = true,
            updatedAtMs = 12_345L
        )

        assertTrue(WearSnapshotStore.writeIfChanged(prefs, withBattery))
        assertEquals(withBattery, WearSnapshotStore.read(prefs))

        val withoutBattery = withBattery.copy(
            statusLabel = "Camera Is Off",
            batteryPercent = null,
            isRecording = false,
            isAwake = false,
            updatedAtMs = 23_456L
        )
        assertTrue(WearSnapshotStore.writeIfChanged(prefs, withoutBattery))
        val readBack = WearSnapshotStore.read(prefs)
        assertEquals(withoutBattery, readBack)
        assertNull(readBack.batteryPercent)
    }

    @Test
    fun writeIfChangedReturnsFalseWhenOnlyUpdatedAtDiffersAndTrueWhenVisibleFieldsChange() {
        val prefs = InMemorySharedPreferences()
        val initial = WearCameraSnapshot(
            cameraName = "Insta360 X4",
            statusLabel = "Ready",
            batteryPercent = 76,
            isRecording = false,
            isConnected = true,
            isAwake = true,
            hasWakeUpId = true,
            updatedAtMs = 1_000L
        )

        assertTrue(WearSnapshotStore.writeIfChanged(prefs, initial))
        assertFalse(WearSnapshotStore.writeIfChanged(prefs, initial.copy(updatedAtMs = 9_999L)))
        // Timestamp stays at the last notified write because nothing visible changed.
        assertEquals(1_000L, WearSnapshotStore.read(prefs).updatedAtMs)

        val transitions = listOf(
            initial.copy(cameraName = "GO 3S", updatedAtMs = 2_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", updatedAtMs = 3_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", batteryPercent = 75, updatedAtMs = 4_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", batteryPercent = null, updatedAtMs = 5_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", batteryPercent = null, isRecording = true, updatedAtMs = 6_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", batteryPercent = null, isRecording = true, isConnected = false, updatedAtMs = 7_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", batteryPercent = null, isRecording = true, isConnected = false, isAwake = false, updatedAtMs = 8_000L),
            initial.copy(cameraName = "GO 3S", statusLabel = "Recording 00:05", batteryPercent = null, isRecording = true, isConnected = false, isAwake = false, hasWakeUpId = false, updatedAtMs = 9_000L)
        )
        for (next in transitions) {
            assertTrue("Expected writeIfChanged=true for $next", WearSnapshotStore.writeIfChanged(prefs, next))
            assertEquals(next, WearSnapshotStore.read(prefs))
            assertFalse(WearSnapshotStore.writeIfChanged(prefs, next.copy(updatedAtMs = next.updatedAtMs + 1L)))
        }
    }

    @Test
    fun tileActionLabelMatchesCameraAndWakeUpState() {
        assertEquals(
            "Open Remote",
            WearSnapshotStore.tileActionLabel(WearCameraSnapshot(isRecording = true, hasWakeUpId = true))
        )
        assertEquals(
            "Open Remote",
            WearSnapshotStore.tileActionLabel(WearCameraSnapshot(isConnected = true, isAwake = true, hasWakeUpId = true))
        )
        assertEquals(
            "Wake Up",
            WearSnapshotStore.tileActionLabel(WearCameraSnapshot(isConnected = true, isAwake = false, hasWakeUpId = true))
        )
        assertEquals(
            "Wake Up",
            WearSnapshotStore.tileActionLabel(WearCameraSnapshot(isConnected = false, isAwake = false, hasWakeUpId = true))
        )
        assertEquals(
            "Connect",
            WearSnapshotStore.tileActionLabel(WearCameraSnapshot(isConnected = true, isAwake = false, hasWakeUpId = false))
        )
        assertEquals(
            "Connect",
            WearSnapshotStore.tileActionLabel(WearCameraSnapshot())
        )
    }

    @Test
    fun complicationShortTextReflectsPriorityOrder() {
        assertEquals(
            "REC",
            WearSnapshotStore.complicationShortText(
                WearCameraSnapshot(isRecording = true, isConnected = true, isAwake = true, batteryPercent = 90)
            )
        )
        assertEquals(
            "82%",
            WearSnapshotStore.complicationShortText(
                WearCameraSnapshot(isConnected = true, isAwake = true, batteryPercent = 82)
            )
        )
        assertEquals(
            "Ready",
            WearSnapshotStore.complicationShortText(
                WearCameraSnapshot(isConnected = true, isAwake = true, batteryPercent = null)
            )
        )
        assertEquals(
            "Off",
            WearSnapshotStore.complicationShortText(
                WearCameraSnapshot(isConnected = true, isAwake = false, batteryPercent = 82)
            )
        )
        assertEquals(
            "Open",
            WearSnapshotStore.complicationShortText(
                WearCameraSnapshot(isConnected = false, isAwake = false, batteryPercent = 82)
            )
        )
    }

    @Test
    fun shouldUseWakeUpActionOnlyWhenNotControllingAndWakeUpIdExists() {
        assertFalse(WearSnapshotStore.shouldUseWakeUpAction(WearCameraSnapshot(isRecording = true, hasWakeUpId = true)))
        assertFalse(WearSnapshotStore.shouldUseWakeUpAction(WearCameraSnapshot(isConnected = true, isAwake = true, hasWakeUpId = true)))
        assertTrue(WearSnapshotStore.shouldUseWakeUpAction(WearCameraSnapshot(isConnected = true, isAwake = false, hasWakeUpId = true)))
        assertTrue(WearSnapshotStore.shouldUseWakeUpAction(WearCameraSnapshot(isConnected = false, isAwake = false, hasWakeUpId = true)))
        assertFalse(WearSnapshotStore.shouldUseWakeUpAction(WearCameraSnapshot(isConnected = false, isAwake = false, hasWakeUpId = false)))
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
