package com.open360.ui.liveview

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.protocol.CameraNotification
import com.open360.core.protocol.CameraTransportClient
import com.open360.core.protocol.LiveStreamProtocol
import com.open360.core.protocol.NalStreamParser.VideoCodec
import com.open360.core.protocol.PacketFramer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class InMemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { if (key != null) map[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) map[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) map[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) map[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) map[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) map[key] = value }
            override fun remove(key: String?) = apply { if (key != null) map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeApplication : Application() {
        private val prefs = HashMap<String, InMemoryPrefs>()
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            prefs.getOrPut(name) { InMemoryPrefs() }
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private class FakeTransportClient : CameraTransportClient {
        override var onStreamPacket: ((LiveStreamProtocol.StreamPacket) -> Unit)? = null
        private val _notifications = MutableSharedFlow<CameraNotification>(extraBufferCapacity = 32)
        override val notifications: SharedFlow<CameraNotification> = _notifications.asSharedFlow()
        private val _isConnected = MutableSharedFlow<Boolean>(replay = 1)
        override val isConnected: SharedFlow<Boolean> = _isConnected.asSharedFlow()
        private var seq = 1

        override suspend fun connect(scope: CoroutineScope) {
            _isConnected.emit(true)
        }

        override suspend fun request(
            messageCode: Int,
            protobufBytes: ByteArray,
            timeoutMs: Long
        ): PacketFramer.Message = PacketFramer.Message(200, seq++, ByteArray(0))

        override suspend fun sendCommand(messageCode: Int, sequenceNumber: Int, protobufBytes: ByteArray) = Unit

        override fun disconnect() {
            _isConnected.tryEmit(false)
        }

        fun emitNotification(notif: CameraNotification) {
            _notifications.tryEmit(notif)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun encodeVarintField(field: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeVarint(v0: Int) {
            var v = v0
            while ((v and 0x7F.inv()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
        }
        writeVarint(field shl 3)
        writeVarint(value)
        return out.toByteArray()
    }

    @Test
    fun tcpSubModeAndCaptureTimeLeftNotificationsUpdateConnectedBleManager() = runTest {
        val app = FakeApplication()
        val bleManager = GpsRemoteBleManager(app)
        bleManager.setConnectionStatusForTesting(BleConnectionStatus.CONNECTED, isCameraAwake = true)

        val transport = FakeTransportClient()
        val viewModel = LiveViewViewModel(app).apply {
            cameraLink = bleManager
            socketClientOverrideForTesting = transport
        }

        viewModel.startLiveView()

        // Emit 0x2053 for HDR Photo (photo=1, video=100)
        transport.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE,
                encodeVarintField(1, 1) + encodeVarintField(2, 100)
            )
        )
        assertEquals(Go2BleProtocol.SubModes(1, 100), bleManager.state.value.details.subModes)

        // Emit 0x2021 for 1,105 photos remaining
        transport.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT,
                encodeVarintField(1, 1105)
            )
        )
        assertEquals(1105, bleManager.state.value.details.captureSecondsLeft)

        // Emit 0x2053 for FreeFrame Video (photo=100, video=5) and 0x2021 for 4796s
        transport.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE,
                encodeVarintField(1, 100) + encodeVarintField(2, 5)
            )
        )
        transport.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT,
                encodeVarintField(1, 4796)
            )
        )
        assertEquals(Go2BleProtocol.SubModes(100, 5), bleManager.state.value.details.subModes)
        assertEquals(4796, bleManager.state.value.details.captureSecondsLeft)

        viewModel.stopLiveView()
    }

    @Test
    fun tcpNotificationsDoNotMutateDisconnectedBleManager() = runTest {
        val app = FakeApplication()
        val bleManager = GpsRemoteBleManager(app)
        val transport = FakeTransportClient()
        val viewModel = LiveViewViewModel(app).apply {
            cameraLink = bleManager
            socketClientOverrideForTesting = transport
        }

        viewModel.startLiveView()
        transport.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_SUB_MODE_CHANGE,
                encodeVarintField(1, 1) + encodeVarintField(2, 100)
            )
        )
        transport.emitNotification(
            CameraNotification.RawNotification(
                Go2BleProtocol.NOTIF_CAPTURE_TIME_LEFT,
                encodeVarintField(1, 900)
            )
        )

        assertNull(bleManager.state.value.details.subModes)
        assertNull(bleManager.state.value.details.captureSecondsLeft)

        viewModel.stopLiveView()
    }

    @Test
    fun decoderVideoFormatChangesDynamicallyUpdateAspectDimensions() = runTest {
        val app = FakeApplication()
        val transport = FakeTransportClient()
        val viewModel = LiveViewViewModel(app).apply {
            socketClientOverrideForTesting = transport
        }
        viewModel.startLiveView()

        viewModel.handleVideoFormatChanged(640, 384, VideoCodec.H264)
        assertEquals(640, viewModel.uiState.value.videoWidth)
        assertEquals(384, viewModel.uiState.value.videoHeight)

        viewModel.handleVideoFormatChanged(640, 480, VideoCodec.H264)
        assertEquals(640, viewModel.uiState.value.videoWidth)
        assertEquals(480, viewModel.uiState.value.videoHeight)

        viewModel.handleVideoFormatChanged(640, 384, VideoCodec.H264)
        assertEquals(640, viewModel.uiState.value.videoWidth)
        assertEquals(384, viewModel.uiState.value.videoHeight)

        viewModel.stopLiveView()
    }
}
