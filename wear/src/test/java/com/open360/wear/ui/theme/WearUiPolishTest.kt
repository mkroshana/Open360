package com.open360.wear.ui.theme

import com.open360.wear.ui.WearControlsMetrics
import com.open360.wear.ui.WearOfflineMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class WearUiPolishTest {

    @Test
    fun wearControlsMetricsFitCircularChordAndClearanceAcrossRoundWatchSizes() {
        listOf(192, 205, 215, 227, 240).forEach { screenWidthDp ->
            val m = WearControlsMetrics.forScreenWidthDp(screenWidthDp)
            val totalRowWidthDp = m.sideButtonSize.value * 2f + m.shutterSize.value + m.rowGap.value * 2f
            val totalHeightDp = m.aboveHeight.value + m.shutterSize.value + m.belowHeight.value + m.verticalGap.value * 2f
            val clearanceDp = (screenWidthDp - totalHeightDp) / 2f
            assertTrue("Expected >= 16dp vertical clearance on ${screenWidthDp}dp watch, was $clearanceDp", clearanceDp >= 16f)

            val radius = screenWidthDp / 2f
            val sideHalfHeight = m.sideButtonSize.value / 2f
            val chordAtSideCorners = 2f * sqrt(radius * radius - sideHalfHeight * sideHalfHeight)
            assertTrue(
                "Middle row width ${totalRowWidthDp}dp exceeds circular chord ${chordAtSideCorners}dp on ${screenWidthDp}dp watch",
                totalRowWidthDp <= chordAtSideCorners
            )
        }
    }

    @Test
    fun wearOfflineMetricsFitAboveEdgeButtonWithoutScrollingOnStandardRoundWatches() {
        listOf(227, 240).forEach { screenHeightDp ->
            val m = WearOfflineMetrics.forScreenHeightDp(screenHeightDp)
            assertTrue("Expected topPadding >= 20dp to clear TimeText on ${screenHeightDp}dp watch, was ${m.topPadding.value}", m.topPadding.value >= 20f)
            val headerHeight = m.avatarSize.value + m.avatarToPillGap.value + 24f + m.pillToNameGap.value + 16f
            val twoLineHintHeight = 28f
            val settingsRowHeight = m.settingsButtonSize.value
            val edgeButtonHeightPlusPadding = 52f
            val effectiveEdgeButtonSpacing = (m.edgeButtonSpacing.value - 3f).coerceAtLeast(0f)
            val totalHeight = m.topPadding.value + headerHeight + twoLineHintHeight + settingsRowHeight +
                2f * m.itemSpacing.value + edgeButtonHeightPlusPadding + effectiveEdgeButtonSpacing
            val spareDp = screenHeightDp - totalHeight
            assertTrue(
                "Expected >= 6dp spare height on ${screenHeightDp}dp watch (total=$totalHeight, spare=$spareDp)",
                spareDp >= 6f
            )
        }
    }

    @Test
    fun wearOfflinePermissionRecoveryHintStaysWithinTwoLineBudgetOnStandardWatches() {
        val permissionRecoveryHint = "Allow Bluetooth & Location to connect"
        val baselineTwoLineHint = "Switch the camera on, then connect"
        assertTrue(
            "Expected permission recovery hint length (${permissionRecoveryHint.length}) to stay compact like baseline (${baselineTwoLineHint.length})",
            permissionRecoveryHint.length <= baselineTwoLineHint.length + 4
        )
        listOf(227, 240).forEach { screenHeightDp ->
            val m = WearOfflineMetrics.forScreenHeightDp(screenHeightDp)
            assertTrue(m.edgeButtonSpacing.value >= 6f)
        }
    }

    @Test
    fun wearMiddleEllipsizePreservesShortStrings() {
        assertEquals("", wearMiddleEllipsize("", 20))
        assertEquals("short.mp4", wearMiddleEllipsize("short.mp4", 20))
        assertEquals("exact_10ch", wearMiddleEllipsize("exact_10ch", 10))
    }

    @Test
    fun wearMiddleEllipsizeTruncatesLongFilenamesWithWatchDefaultLength() {
        val clipName = "VID_20261124_183022_00_066.mp4" // 30 code points
        val truncated = wearMiddleEllipsize(clipName)
        assertEquals(24, truncated.codePointCount(0, truncated.length))
        assertEquals("VID_20261124…_00_066.mp4", truncated)
        assertEquals("VID_202611…0_066.mp4", wearMiddleEllipsize(clipName, 20))
    }

    @Test
    fun wearMiddleEllipsizeHandlesSurrogatePairsAndBoundaryMaxLengths() {
        val text = "🦊🦊🦊🦊_middle_👩🏽‍💻_end"
        val result = wearMiddleEllipsize(text, 10)
        assertEquals(10, result.codePointCount(0, result.length))
        for (i in result.indices) {
            val ch = result[i]
            if (Character.isHighSurrogate(ch)) {
                assert(i + 1 < result.length && Character.isLowSurrogate(result[i + 1]))
            }
            if (Character.isLowSurrogate(ch)) {
                assert(i - 1 >= 0 && Character.isHighSurrogate(result[i - 1]))
            }
        }
        assertEquals("a…", wearMiddleEllipsize("abcdef", 2))
        assertEquals("…", wearMiddleEllipsize("abcdef", 1))
        assertEquals("", wearMiddleEllipsize("abcdef", 0))
    }
}
