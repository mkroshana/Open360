package com.open360.core.ble

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class GpsRemoteBleManagerTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values
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
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }

    private class FakeBleContext : ContextWrapper(null) {
        private val prefs = HashMap<String, InMemorySharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            prefs.getOrPut(name) { InMemorySharedPreferences() }
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            PackageManager.PERMISSION_GRANTED
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private fun createManager(): Pair<GpsRemoteBleManager, CopyOnWriteArrayList<Go2BleProtocol.DecodedInnerMessage>> {
        val manager = GpsRemoteBleManager(FakeBleContext())
        manager.setConnectionStatusForTesting(BleConnectionStatus.CONNECTED, isCameraAwake = true)
        val queued = CopyOnWriteArrayList<Go2BleProtocol.DecodedInnerMessage>()
        manager.onPacketQueuedForTesting = { packet ->
            val envelope = Go2BleProtocol.decodePacket(packet)
            if (envelope != null && envelope.isMessage) {
                Go2BleProtocol.decodeInnerMessage(envelope.innerData)?.let { queued.add(it) }
            }
        }
        return manager to queued
    }

    private fun bleMessagePacket(commandCode: Int, sequence: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        Go2BleProtocol.encodeMessagePacket(Go2BleProtocol.encodeInnerMessage(commandCode, sequence, payload))

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

    private fun subModesPayload(photo: Int, video: Int): ByteArray =
        encodeVarintField(1, photo) + encodeVarintField(2, video)

    private fun setOptionsMessages(queued: List<Go2BleProtocol.DecodedInnerMessage>): List<Go2BleProtocol.DecodedInnerMessage> =
        queued.filter { it.commandCode == Go2BleProtocol.CMD_SET_OPTIONS }

    @Test
    fun cmdSetOptions200UpdatesSubModesAndRemembersLastSubModePerGroup() {
        val (manager, queued) = createManager()

        // Switch to FreeFrame Video (PRO) -> 0xC8 confirms and updates lastVideoSubMode = 5
        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        val freeframeReq = setOptionsMessages(queued).last()
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.effectiveSubModes())

        manager.handleIncomingNotification(bleMessagePacket(0xC8, freeframeReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.state.value.details.subModes)

        // Switch to HDR Photo -> 0xC8 confirms and updates lastPhotoSubMode = 1
        manager.setCameraMode(CameraMode.HDR_PHOTO)
        val hdrReq = setOptionsMessages(queued).last()
        manager.handleIncomingNotification(bleMessagePacket(0xC8, hdrReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.state.value.details.subModes)

        // Switching capture group back to Video remembers sub-mode 5 (FREEFRAME_PRO)
        manager.setCameraMode(photo = false)
        val backToVideoReq = setOptionsMessages(queued).last()
        assertTrue(Go2BleProtocol.buildSetSubModePayload(false, 5).contentEquals(backToVideoReq.payload))
        manager.handleIncomingNotification(bleMessagePacket(0xC8, backToVideoReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.state.value.details.subModes)

        // Switching capture group back to Photo remembers sub-mode 1 (HDR_PHOTO)
        manager.setCameraMode(photo = true)
        val backToPhotoReq = setOptionsMessages(queued).last()
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 1).contentEquals(backToPhotoReq.payload))
        manager.handleIncomingNotification(bleMessagePacket(0xC8, backToPhotoReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.state.value.details.subModes)
    }

    @Test
    fun non200ReplyDropsPendingWithoutCorruptingSubModesOrRememberedModes() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.VIDEO)
        val initReq = setOptionsMessages(queued).last()
        manager.handleIncomingNotification(bleMessagePacket(0xC8, initReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(100, 0), manager.state.value.details.subModes)

        // Request HDR Photo and simulate camera returning status 500
        manager.setCameraMode(CameraMode.HDR_PHOTO)
        val failedReq = setOptionsMessages(queued).last()
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())

        manager.handleIncomingNotification(bleMessagePacket(500, failedReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(100, 0), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(100, 0), manager.effectiveSubModes())

        // Subsequent late 0xC8 with the removed sequence is ignored
        manager.handleIncomingNotification(bleMessagePacket(0xC8, failedReq.sequence))
        assertEquals(Go2BleProtocol.SubModes(100, 0), manager.state.value.details.subModes)

        // lastPhotoSubMode must still be 0 (not corrupted to 1)
        manager.setCameraMode(photo = true)
        val photoGroupReq = setOptionsMessages(queued).last()
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 0).contentEquals(photoGroupReq.payload))
    }

    @Test
    fun rapidAndOutOfOrderRepliesNeverOverwriteNewerRequestedMode() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        val seq1 = setOptionsMessages(queued).last().sequence

        manager.setCameraMode(CameraMode.HDR_PHOTO)
        val seq2 = setOptionsMessages(queued).last().sequence

        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())

        // Older seq1 reply arriving while seq2 is newer does NOT overwrite effective/confirmed mode
        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq1))
        assertNull(manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())

        // Newer seq2 reply applies HDR_PHOTO
        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq2))
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.state.value.details.subModes)

        // Rapid pair where newer reply arrives first and older reply arrives second
        manager.setCameraMode(CameraMode.TIMELAPSE)
        val seq3 = setOptionsMessages(queued).last().sequence
        manager.setCameraMode(CameraMode.STARLAPSE)
        val seq4 = setOptionsMessages(queued).last().sequence

        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq4))
        assertEquals(Go2BleProtocol.SubModes(7, 100), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(7, 100), manager.effectiveSubModes())

        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq3))
        assertEquals(Go2BleProtocol.SubModes(7, 100), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(7, 100), manager.effectiveSubModes())
    }

    @Test
    fun seq1SuccessThenSeq2ErrorPromotesSeq1ConfirmedMode() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        val seq1 = setOptionsMessages(queued).last().sequence
        manager.setCameraMode(CameraMode.HDR_PHOTO)
        val seq2 = setOptionsMessages(queued).last().sequence

        // Earlier write succeeds while newer write is still in-flight, then newer write fails with 500
        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq1))
        assertNull(manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())

        manager.handleIncomingNotification(bleMessagePacket(500, seq2))
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.effectiveSubModes())
    }

    @Test
    fun seq2ErrorThenSeq1SuccessAppliesSeq1Mode() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        val seq1 = setOptionsMessages(queued).last().sequence
        manager.setCameraMode(CameraMode.HDR_PHOTO)
        val seq2 = setOptionsMessages(queued).last().sequence

        // Newer write fails first, then older write succeeds
        manager.handleIncomingNotification(bleMessagePacket(500, seq2))
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.effectiveSubModes())

        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq1))
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(100, 5), manager.effectiveSubModes())
    }

    @Test
    fun earlierTcpNotificationWhileNewerWriteInFlightPreservesNewerPendingAndAppliesOnConfirm() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        manager.setCameraMode(CameraMode.HDR_PHOTO)
        val seq2 = setOptionsMessages(queued).last().sequence

        // TCP 0x2053 arrives for seq1 (FREEFRAME_PRO) while seq2 (HDR_PHOTO) is still pending
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(100, 5))
        assertNull(manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())

        // Once seq2 confirms 0xC8, details.subModes becomes HDR_PHOTO
        manager.handleIncomingNotification(bleMessagePacket(0xC8, seq2))
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())
    }

    @Test
    fun externalTransportUpdatesAreIgnoredAfterDisconnect() {
        val (manager, _) = createManager()
        manager.disconnect()

        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(1, 100))
        manager.updateCaptureSecondsLeftFromExternalTransport(1105)

        assertNull(manager.state.value.details.subModes)
        assertNull(manager.state.value.details.captureSecondsLeft)
    }

    @Test
    fun sequenceWrapClearsStaleUnansweredPendingWrite() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        val initialSeq = setOptionsMessages(queued).last().sequence

        // Wrap sequence numbers through another 254 operations so initialSeq is recycled
        repeat(254) {
            manager.takePhoto()
        }

        // Receiving 0xC8 on the recycled sequence must NOT confirm the stale FREEFRAME_PRO write
        manager.handleIncomingNotification(bleMessagePacket(0xC8, initialSeq))
        assertNull(manager.state.value.details.subModes)
    }

    @Test
    fun externalTransportUpdatesAreAuthoritativeAndInvalidateOlderPendingWrites() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.FREEFRAME_PRO)
        val pendingSeq = setOptionsMessages(queued).last().sequence

        // External TCP 0x2053 arrives with HDR Photo -> supersedes in-flight BLE write
        manager.updateSubModesFromExternalTransport(Go2BleProtocol.SubModes(1, 100))
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.state.value.details.subModes)
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.effectiveSubModes())

        // Late 0xC8 from the earlier pending write must NOT overwrite HDR Photo
        manager.handleIncomingNotification(bleMessagePacket(0xC8, pendingSeq))
        assertEquals(Go2BleProtocol.SubModes(1, 100), manager.state.value.details.subModes)

        // External TCP 0x2021 updates captureSecondsLeft (and rejects negative values)
        manager.updateCaptureSecondsLeftFromExternalTransport(1105)
        assertEquals(1105, manager.state.value.details.captureSecondsLeft)
        manager.updateCaptureSecondsLeftFromExternalTransport(-1)
        assertEquals(1105, manager.state.value.details.captureSecondsLeft)

        // BLE 0x2053 notification also updates subModes and remembered sub-modes
        manager.handleIncomingNotification(
            bleMessagePacket(Go2BleProtocol.NOTIF_SUB_MODE_CHANGE, 99, subModesPayload(photo = 100, video = 4))
        )
        assertEquals(Go2BleProtocol.SubModes(100, 4), manager.state.value.details.subModes)

        manager.setCameraMode(photo = true)
        val rememberedPhotoReq = setOptionsMessages(queued).last()
        assertTrue(Go2BleProtocol.buildSetSubModePayload(true, 1).contentEquals(rememberedPhotoReq.payload))
    }

    @Test
    fun forgetPendingRepliesClearsPendingSubModeWrites() {
        val (manager, queued) = createManager()

        manager.setCameraMode(CameraMode.INTERVAL_PHOTO)
        val req = setOptionsMessages(queued).last()
        assertNotNull(manager.effectiveSubModes())

        manager.forgetPendingReplies()
        assertNull(manager.effectiveSubModes())

        // Reply arriving after link reset/forget must be ignored
        manager.handleIncomingNotification(bleMessagePacket(0xC8, req.sequence))
        assertNull(manager.state.value.details.subModes)
    }
}
