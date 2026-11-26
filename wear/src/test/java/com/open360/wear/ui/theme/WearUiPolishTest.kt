package com.open360.wear.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class WearUiPolishTest {

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
