package com.open360.core.ble

import com.open360.core.ble.ReconnectPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    private fun policy() = ReconnectPolicy(earlyDropMs = 60_000L, maxInARow = 3)

    @Test
    fun testADropSoonAfterTheCameraAnsweredIsReconnected() {
        // As on 2026-10-15: the camera answered, and the link timed out 7 s later
        assertEquals(Action.RECONNECT, policy().onDrop(nowMs = 107_000L, answeredAtMs = 100_000L, linkWasReconnect = false))
    }

    @Test
    fun testADropAfterTheLinkHeldIsLeftAlone() {
        assertEquals(Action.STAY_DISCONNECTED, policy().onDrop(nowMs = 171_000L, answeredAtMs = 100_000L, linkWasReconnect = false))
    }

    @Test
    fun testALinkOnWhichTheCameraNeverAnsweredIsLeftAlone() {
        // A link to a camera that is off, opened from the app
        assertEquals(Action.STAY_DISCONNECTED, policy().onDrop(nowMs = 100_000L, answeredAtMs = null, linkWasReconnect = false))
    }

    @Test
    fun testACameraThatIsGoneIsTriedThreeTimesInAll() {
        val policy = policy()
        assertEquals(Action.RECONNECT, policy.onDrop(nowMs = 105_000L, answeredAtMs = 100_000L, linkWasReconnect = false))
        // Each reconnection fails before the camera answers
        assertEquals(Action.RECONNECT, policy.onDrop(nowMs = 136_000L, answeredAtMs = null, linkWasReconnect = true))
        assertEquals(Action.RECONNECT, policy.onDrop(nowMs = 167_000L, answeredAtMs = null, linkWasReconnect = true))
        assertEquals(Action.GIVE_UP, policy.onDrop(nowMs = 198_000L, answeredAtMs = null, linkWasReconnect = true))
        assertEquals(0, policy.reconnectsLeft)
    }

    @Test
    fun testLinksThatKeepDroppingStopAfterThreeReconnections() {
        val policy = policy()
        var answeredAt = 100_000L
        repeat(3) {
            assertEquals(Action.RECONNECT, policy.onDrop(nowMs = answeredAt + 5_000L, answeredAtMs = answeredAt, linkWasReconnect = it > 0))
            answeredAt += 10_000L
        }
        assertEquals(Action.GIVE_UP, policy.onDrop(nowMs = answeredAt + 5_000L, answeredAtMs = answeredAt, linkWasReconnect = true))
    }

    @Test
    fun testALinkThatHeldMakesTheReconnectionsAvailableAgain() {
        val policy = policy()
        policy.onDrop(nowMs = 105_000L, answeredAtMs = 100_000L, linkWasReconnect = false)
        policy.onDrop(nowMs = 115_000L, answeredAtMs = 110_000L, linkWasReconnect = true)
        assertEquals(1, policy.reconnectsLeft)
        // The next link held for ten minutes before it dropped
        assertEquals(Action.STAY_DISCONNECTED, policy.onDrop(nowMs = 720_000L, answeredAtMs = 120_000L, linkWasReconnect = true))
        assertEquals(3, policy.reconnectsLeft)
    }

    @Test
    fun testConnectingFromTheAppMakesTheReconnectionsAvailableAgain() {
        val policy = policy()
        repeat(3) { policy.onDrop(nowMs = 105_000L, answeredAtMs = 100_000L, linkWasReconnect = false) }
        assertEquals(0, policy.reconnectsLeft)
        policy.reset()
        assertEquals(Action.RECONNECT, policy.onDrop(nowMs = 205_000L, answeredAtMs = 200_000L, linkWasReconnect = false))
    }
}
