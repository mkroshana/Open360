package com.open360.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class UiPolishTest {

    @Test
    fun middleEllipsizePreservesShortStrings() {
        assertEquals("", middleEllipsize("", 20))
        assertEquals("short.mp4", middleEllipsize("short.mp4", 20))
        assertEquals("exact_10ch", middleEllipsize("exact_10ch", 10))
    }

    @Test
    fun middleEllipsizePreservesStartAndSuffixOfLongCameraFiles() {
        val clipName = "VID_20261124_183022_00_066.mp4" // 30 chars
        val truncated = middleEllipsize(clipName, 20)
        assertEquals(20, truncated.codePointCount(0, truncated.length))
        assertEquals("VID_202611…0_066.mp4", truncated)
        assertEquals("VID_20261…0_066.mp4", middleEllipsize(clipName, 19))
    }

    @Test
    fun middleEllipsizeHandlesEmojiSurrogatePairsSafely() {
        val text = "🦊🦊🦊🦊_middle_👩🏽‍💻_end"
        val result = middleEllipsize(text, 10)
        assertEquals(10, result.codePointCount(0, result.length))
        // Ensure no lone UTF-16 surrogates remain
        for (i in result.indices) {
            val ch = result[i]
            if (Character.isHighSurrogate(ch)) {
                assert(i + 1 < result.length && Character.isLowSurrogate(result[i + 1]))
            }
            if (Character.isLowSurrogate(ch)) {
                assert(i - 1 >= 0 && Character.isHighSurrogate(result[i - 1]))
            }
        }
        assertEquals("…", middleEllipsize("abcdef", 1))
        assertEquals("", middleEllipsize("abcdef", 0))
    }

    @Test
    fun pluralizeFormatsCountsAndWords() {
        assertEquals("0 points", pluralize(0, "point", "points"))
        assertEquals("1 point", pluralize(1, "point", "points"))
        assertEquals("2 points", pluralize(2, "point", "points"))
        assertEquals("1,284 points", pluralize(1284, "point", "points"))
    }
}
