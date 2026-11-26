package com.open360.wear

import androidx.wear.ongoing.Status
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOngoingStatusTest {

    @Test
    fun templateForCoversAllConnectionAndRecordingStates() {
        assertEquals(
            "REC #time#",
            WearOngoingStatus.templateFor(
                WearOngoingStatus.Snapshot(
                    isConnected = true,
                    isAwake = true,
                    isRecording = true,
                    recordingStartedElapsedMs = 12_000L
                )
            )
        )
        assertEquals(
            "Recording",
            WearOngoingStatus.templateFor(
                WearOngoingStatus.Snapshot(
                    isConnected = true,
                    isAwake = true,
                    isRecording = true,
                    recordingStartedElapsedMs = null
                )
            )
        )
        assertEquals(
            "Through Phone",
            WearOngoingStatus.templateFor(
                WearOngoingStatus.Snapshot(
                    isConnected = true,
                    isAwake = true,
                    linkedThroughPeer = true
                )
            )
        )
        assertEquals(
            "Camera Ready",
            WearOngoingStatus.templateFor(
                WearOngoingStatus.Snapshot(
                    isConnected = true,
                    isAwake = true,
                    linkedThroughPeer = false
                )
            )
        )
        assertEquals(
            "Camera Is Off",
            WearOngoingStatus.templateFor(
                WearOngoingStatus.Snapshot(
                    isConnected = true,
                    isAwake = false
                )
            )
        )
        assertEquals(
            "Camera Connected",
            WearOngoingStatus.templateFor(WearOngoingStatus.Snapshot())
        )
    }

    @Test
    fun buildStatusIncludesStopwatchPartOnlyWhenRecordingWithStartTime() {
        val readyStatus = WearOngoingStatus.buildStatus(
            WearOngoingStatus.Snapshot(isConnected = true, isAwake = true)
        )
        assertEquals(listOf("Camera Ready"), readyStatus.templates.map { it.toString() })
        assertTrue(readyStatus.partNames.isEmpty())

        val fallbackRecordingStatus = WearOngoingStatus.buildStatus(
            WearOngoingStatus.Snapshot(
                isConnected = true,
                isAwake = true,
                isRecording = true,
                recordingStartedElapsedMs = null
            )
        )
        assertEquals(listOf("Recording"), fallbackRecordingStatus.templates.map { it.toString() })
        assertTrue(fallbackRecordingStatus.partNames.isEmpty())

        val startedMs = 45_000L
        val recordingStatus = WearOngoingStatus.buildStatus(
            WearOngoingStatus.Snapshot(
                isConnected = true,
                isAwake = true,
                isRecording = true,
                recordingStartedElapsedMs = startedMs
            )
        )
        assertEquals(listOf("REC #time#"), recordingStatus.templates.map { it.toString() })
        assertEquals(setOf("time"), recordingStatus.partNames)
        val stopwatch = recordingStatus.getPart("time") as Status.StopwatchPart
        assertEquals(startedMs, stopwatch.timeZeroMillis)
    }

    @Test
    fun computeNextSnapshotKeepsStopwatchStartStableAndResetsOnExcessDrift() {
        val connectedAwake = BleCameraState(
            connectionStatus = BleConnectionStatus.CONNECTED,
            isCameraAwake = true
        )

        val started = WearOngoingStatus.computeNextSnapshot(
            previous = WearOngoingStatus.Snapshot(isConnected = true, isAwake = true),
            ble = connectedAwake,
            isRecording = true,
            recordingDurationSeconds = 5L,
            linkedThroughPeer = false,
            nowElapsedMs = 100_000L
        )
        assertEquals(95_000L, started.recordingStartedElapsedMs)

        // Small jitter within 3000 ms preserves the original stopwatch start anchor.
        val jitterWithinThreshold = WearOngoingStatus.computeNextSnapshot(
            previous = started,
            ble = connectedAwake,
            isRecording = true,
            recordingDurationSeconds = 6L,
            linkedThroughPeer = false,
            nowElapsedMs = 104_000L // expectedStartMs = 98_000L (3_000L drift)
        )
        assertEquals(95_000L, jitterWithinThreshold.recordingStartedElapsedMs)

        // Drift strictly over 3000 ms re-anchors the stopwatch start time.
        val driftOverThreshold = WearOngoingStatus.computeNextSnapshot(
            previous = jitterWithinThreshold,
            ble = connectedAwake,
            isRecording = true,
            recordingDurationSeconds = 6L,
            linkedThroughPeer = false,
            nowElapsedMs = 104_001L // expectedStartMs = 98_001L (3_001L drift)
        )
        assertEquals(98_001L, driftOverThreshold.recordingStartedElapsedMs)

        // Stopping clears the stopwatch anchor.
        val stopped = WearOngoingStatus.computeNextSnapshot(
            previous = driftOverThreshold,
            ble = connectedAwake,
            isRecording = false,
            recordingDurationSeconds = 0L,
            linkedThroughPeer = false,
            nowElapsedMs = 105_000L
        )
        assertNull(stopped.recordingStartedElapsedMs)
    }

    @Test
    fun computeNextSnapshotTreatsPeerLinkAsConnectedAndClampsNegativeDuration() {
        val peerAwake = BleCameraState(
            connectionStatus = BleConnectionStatus.DISCONNECTED,
            isCameraAwake = true
        )
        val next = WearOngoingStatus.computeNextSnapshot(
            previous = WearOngoingStatus.Snapshot(),
            ble = peerAwake,
            isRecording = true,
            recordingDurationSeconds = -4L,
            linkedThroughPeer = true,
            nowElapsedMs = 50_000L
        )
        assertTrue(next.isConnected)
        assertTrue(next.isAwake)
        assertTrue(next.linkedThroughPeer)
        assertEquals(50_000L, next.recordingStartedElapsedMs)
        assertEquals("REC #time#", WearOngoingStatus.templateFor(next))
    }
}
