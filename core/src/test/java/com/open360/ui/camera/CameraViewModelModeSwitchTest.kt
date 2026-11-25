package com.open360.ui.camera

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraDetails
import com.open360.core.ble.CameraMode
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.model.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CameraViewModelModeSwitchTest {

    private class NoOpPrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = NoOpPrefs()
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private fun setupManager(): Pair<GpsRemoteBleManager, CopyOnWriteArrayList<Go2BleProtocol.DecodedInnerMessage>> {
        val manager = GpsRemoteBleManager(FakeContext())
        manager.setConnectionStatusForTesting(BleConnectionStatus.CONNECTED, isCameraAwake = true)
        val sent = CopyOnWriteArrayList<Go2BleProtocol.DecodedInnerMessage>()
        manager.onPacketQueuedForTesting = { packet ->
            val env = Go2BleProtocol.decodePacket(packet)
            if (env != null && env.isMessage) {
                Go2BleProtocol.decodeInnerMessage(env.innerData)?.let { sent.add(it) }
            }
        }
        return manager to sent
    }

    private fun connectedUiState(
        manager: GpsRemoteBleManager,
        currentMode: CaptureMode,
        isRecording: Boolean = false
    ): RemoteUiState = RemoteUiState(
        bleState = BleCameraState(
            connectionStatus = BleConnectionStatus.CONNECTED,
            isCameraAwake = true,
            isRecording = isRecording,
            details = CameraDetails(subModes = manager.state.value.details.subModes)
        ),
        currentMode = currentMode,
        isConnected = true,
        isRecording = isRecording
    )

    @Test
    fun rapidCrossGroupSwitchWhileInFlightDoesNotDropBackSwitch() {
        val (manager, sent) = setupManager()
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(100, 5))

        var uiMode = CaptureMode.VIDEO
        // 1. Switch Video -> Photo (in-flight, no 0xC8 received yet; details.subModes still Video)
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.PHOTO,
            current = connectedUiState(manager, uiMode),
            bleManager = manager
        ) { uiMode = it }
        assertEquals(CaptureMode.PHOTO, uiMode)
        assertEquals(1, sent.size)
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 0).contentEquals(sent[0].payload))

        // 2. Immediately switch Photo -> Video BEFORE 0xC8 arrives -> must NOT drop!
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.VIDEO,
            current = connectedUiState(manager, uiMode),
            bleManager = manager
        ) { uiMode = it }
        assertEquals(CaptureMode.VIDEO, uiMode)
        assertEquals(2, sent.size)
        assertTrue(Go2BleProtocol.buildSetSubModePayload(false, 5).contentEquals(sent[1].payload))
    }

    @Test
    fun crossGroupSwitchWorksAfterExternalTransportUpdate() {
        val (manager, sent) = setupManager()
        // Pre-Live-View was Video; during Live View TCP 0x2053 moved camera to HDR Photo
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(100, 0))
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(1, 100))

        var uiMode = CaptureMode.PHOTO
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.VIDEO,
            current = connectedUiState(manager, uiMode),
            bleManager = manager
        ) { uiMode = it }

        assertEquals(CaptureMode.VIDEO, uiMode)
        assertEquals(1, sent.size)
        assertTrue(Go2BleProtocol.buildSetSubModePayload(false, 0).contentEquals(sent[0].payload))
    }

    @Test
    fun redundantSameGroupSwitchWithoutPendingDoesNotQueueGattWrite() {
        val (manager, sent) = setupManager()
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(100, 5))

        var uiMode = CaptureMode.VIDEO
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.VIDEO,
            current = connectedUiState(manager, uiMode),
            bleManager = manager
        ) { uiMode = it }

        assertEquals(0, sent.size)
    }

    @Test
    fun directSubModeSwitchFollowedByGroupReturnDoesNotDrop() {
        val (manager, sent) = setupManager()
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(1, 100))

        // Sub-mode chip tapped: FreeFrame Video (in-flight)
        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        assertEquals(1, sent.size)

        // Immediately tap Photo before 0xC8 returns -> effectiveSubModes() is Video, so Photo must send!
        var uiMode = CaptureMode.VIDEO
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.PHOTO,
            current = connectedUiState(manager, uiMode),
            bleManager = manager
        ) { uiMode = it }

        assertEquals(CaptureMode.PHOTO, uiMode)
        assertEquals(2, sent.size)
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 1).contentEquals(sent[1].payload))
    }

    @Test
    fun recordingLocksModeSwitches() {
        val (manager, sent) = setupManager()
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(100, 0))

        var uiMode = CaptureMode.VIDEO
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.PHOTO,
            current = connectedUiState(manager, uiMode, isRecording = true),
            bleManager = manager
        ) { uiMode = it }

        assertEquals(CaptureMode.VIDEO, uiMode)
        assertEquals(0, sent.size)
    }

    @Test
    fun takingPhotoLocksModeSwitches() {
        val (manager, sent) = setupManager()
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(100, 0))

        val baseState = connectedUiState(manager, CaptureMode.PHOTO, isRecording = false)
        val photoBusyState = baseState.copy(bleState = baseState.bleState.copy(isTakingPhoto = true))

        var uiMode = CaptureMode.PHOTO
        CameraViewModel.applyLocalModeSwitch(
            mode = CaptureMode.VIDEO,
            current = photoBusyState,
            bleManager = manager
        ) { uiMode = it }

        assertEquals(CaptureMode.PHOTO, uiMode)
        assertEquals(0, sent.size)
    }
}
