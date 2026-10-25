package com.open360.core.peer

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.gps.GpsTelemetry
import com.open360.core.model.CaptureMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSharingTest {

    private val none = BleCameraState()
    private val looking = BleCameraState(connectionStatus = BleConnectionStatus.SCANNING)
    private val linked = BleCameraState(connectionStatus = BleConnectionStatus.CONNECTED, isCameraAwake = true)
    private val recording = linked.copy(isRecording = true)

    private fun from(device: PeerDevice, camera: BleCameraState) =
        PeerSnapshot(device, camera, CaptureMode.VIDEO, recordingSeconds = 0, gps = GpsTelemetry(), canTakeLink = true)

    @Test
    fun testTheCameraIsControlledThroughTheAppThatHasIt() {
        assertTrue(LinkSharing.isLinkedThrough(none, from(PeerDevice.PHONE, linked), takingLink = false))
        // Still looking for it counts, so this app does not look as well
        assertTrue(LinkSharing.isLinkedThrough(none, from(PeerDevice.PHONE, looking), takingLink = false))
    }

    @Test
    fun testNotThroughAnAppWithoutTheLinkOrWhenThisOneHasIt() {
        assertFalse(LinkSharing.isLinkedThrough(none, null, takingLink = false))
        assertFalse(LinkSharing.isLinkedThrough(none, from(PeerDevice.PHONE, none), takingLink = false))
        assertFalse(LinkSharing.isLinkedThrough(linked, from(PeerDevice.PHONE, linked), takingLink = false))
    }

    @Test
    fun testNotThroughTheOtherAppWhileTakingTheLinkFromIt() {
        // Its last snapshot from before it let go may still say it has it
        assertFalse(LinkSharing.isLinkedThrough(none, from(PeerDevice.PHONE, linked), takingLink = true))
    }

    @Test
    fun testAnAppStillLookingLetsGoOfOneThatHasTheLink() {
        assertTrue(LinkSharing.shouldLetGo(looking, from(PeerDevice.WATCH, linked), PeerDevice.PHONE, takingLink = false))
        assertFalse(LinkSharing.shouldLetGo(linked, from(PeerDevice.WATCH, looking), PeerDevice.PHONE, takingLink = false))
    }

    @Test
    fun testOfTwoEquallyFarAlongTheWatchLetsGo() {
        assertTrue(LinkSharing.shouldLetGo(looking, from(PeerDevice.PHONE, looking), PeerDevice.WATCH, takingLink = false))
        assertFalse(LinkSharing.shouldLetGo(looking, from(PeerDevice.WATCH, looking), PeerDevice.PHONE, takingLink = false))
        assertTrue(LinkSharing.shouldLetGo(linked, from(PeerDevice.PHONE, linked), PeerDevice.WATCH, takingLink = false))
    }

    @Test
    fun testARecordingKeepsItsLink() {
        // The watch would otherwise let go, being the watch
        assertFalse(LinkSharing.shouldLetGo(recording, from(PeerDevice.PHONE, linked), PeerDevice.WATCH, takingLink = false))
        assertTrue(LinkSharing.shouldLetGo(linked, from(PeerDevice.WATCH, recording), PeerDevice.PHONE, takingLink = false))
    }

    @Test
    fun testNothingIsLetGoWithoutTwoLinksOrWhileTakingOne() {
        assertFalse(LinkSharing.shouldLetGo(none, from(PeerDevice.PHONE, linked), PeerDevice.WATCH, takingLink = false))
        assertFalse(LinkSharing.shouldLetGo(linked, from(PeerDevice.PHONE, none), PeerDevice.WATCH, takingLink = false))
        assertFalse(LinkSharing.shouldLetGo(linked, null, PeerDevice.WATCH, takingLink = false))
        assertFalse(LinkSharing.shouldLetGo(looking, from(PeerDevice.PHONE, linked), PeerDevice.WATCH, takingLink = true))
    }
}
