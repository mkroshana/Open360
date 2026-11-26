package com.open360.core.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCompanionStateTest {

    @Test
    fun unsupportedFossStateReportsNotSupportedAndNoConnectedWatches() {
        val state = WatchCompanionState(isSupported = false)

        assertFalse(state.isSupported)
        assertFalse(state.hasConnectedWatch)
        assertFalse(state.allConnectedInstalled)
        assertTrue(state.watchesNeedingInstall.isEmpty())
        assertTrue(state.targetWatchesForPlayStore.isEmpty())
        assertEquals("Open360 for Wear OS", state.rowTitle())
        assertFalse(state.isProblemDetail())
    }

    @Test
    fun checkingStateWithNoConnectedWatchesShowsCheckingMessage() {
        val state = WatchCompanionState(isSupported = true, isChecking = true)

        assertEquals("Open360 for Wear OS", state.rowTitle())
        assertEquals("Checking for connected Wear OS watches...", state.rowDetail())
        assertFalse(state.isProblemDetail())
    }

    @Test
    fun idleStateWithNoConnectedWatchesPromptsTapToCheckAgain() {
        val state = WatchCompanionState(isSupported = true, isChecking = false)

        assertFalse(state.hasConnectedWatch)
        assertEquals("Open360 for Wear OS", state.rowTitle())
        assertEquals("No Wear OS watch connected • Tap to check again", state.rowDetail())
        assertFalse(state.isProblemDetail())
    }

    @Test
    fun singleConnectedWatchMissingAppPromptsInstallOnWatch() {
        val watch = WatchNodeInfo(id = "watch-1", displayName = "Pixel Watch 4", isNearby = true)
        val state = WatchCompanionState(
            isSupported = true,
            connectedWatches = listOf(watch),
            installedWatchIds = emptySet()
        )

        assertTrue(state.hasConnectedWatch)
        assertFalse(state.allConnectedInstalled)
        assertEquals(listOf(watch), state.watchesNeedingInstall)
        assertEquals(listOf(watch), state.targetWatchesForPlayStore)
        assertEquals("Install on Watch", state.rowTitle())
        assertEquals("Tap to open Google Play on Pixel Watch 4", state.rowDetail())
        assertFalse(state.isProblemDetail())
    }

    @Test
    fun connectedWatchWithAppInstalledShowsInstalledAndTargetsWatchForUpdates() {
        val watch = WatchNodeInfo(id = "watch-1", displayName = "Pixel Watch 4", isNearby = true)
        val state = WatchCompanionState(
            isSupported = true,
            connectedWatches = listOf(watch),
            installedWatchIds = setOf("watch-1")
        )

        assertTrue(state.hasConnectedWatch)
        assertTrue(state.allConnectedInstalled)
        assertTrue(state.watchesNeedingInstall.isEmpty())
        assertEquals(listOf(watch), state.targetWatchesForPlayStore)
        assertEquals("Wear OS App Installed", state.rowTitle())
        assertEquals(
            "Installed on Pixel Watch 4 • Tap to open on watch Play Store",
            state.rowDetail()
        )
    }

    @Test
    fun multiWatchTargetsOnlyWatchesMissingCompanionApp() {
        val installedWatch = WatchNodeInfo(id = "watch-1", displayName = "Pixel Watch 3", isNearby = false)
        val missingWatch = WatchNodeInfo(id = "watch-2", displayName = "Pixel Watch 4", isNearby = true)
        val state = WatchCompanionState(
            isSupported = true,
            connectedWatches = listOf(missingWatch, installedWatch),
            installedWatchIds = setOf("watch-1")
        )

        assertFalse(state.allConnectedInstalled)
        assertEquals(listOf(missingWatch), state.watchesNeedingInstall)
        assertEquals(listOf(missingWatch), state.targetWatchesForPlayStore)
        assertEquals("Install on Watch", state.rowTitle())
        assertEquals("Tap to open Google Play on Pixel Watch 4", state.rowDetail())
    }

    @Test
    fun launchStatesUpdateDetailAndProblemFlag() {
        val watch = WatchNodeInfo(id = "watch-1", displayName = "Pixel Watch 4")
        val base = WatchCompanionState(
            isSupported = true,
            connectedWatches = listOf(watch),
            installedWatchIds = emptySet()
        )

        val launching = base.copy(launchState = WatchInstallLaunchState.Launching)
        assertEquals("Opening Google Play on Pixel Watch 4...", launching.rowDetail())
        assertFalse(launching.isProblemDetail())

        val opened = base.copy(launchState = WatchInstallLaunchState.Opened("Pixel Watch 4"))
        assertEquals(
            "Google Play opened on Pixel Watch 4 — tap Install on your watch",
            opened.rowDetail()
        )
        assertFalse(opened.isProblemDetail())

        val failed = base.copy(
            launchState = WatchInstallLaunchState.Failed(
                "Could not open Play Store on watch. Make sure your watch is nearby and unlocked."
            )
        )
        assertEquals(
            "Could not open Play Store on watch. Make sure your watch is nearby and unlocked.",
            failed.rowDetail()
        )
        assertTrue(failed.isProblemDetail())
    }
}
