package com.open360.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated design-rule checks enforcing the machine-verifiable guarantees documented in DESIGN.md.
 */
class DesignRulesCheckTest {

    private fun uiSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java/com/open360/ui"),
            File(cwd, "app/src/main/java/com/open360/ui")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/open360/ui from ${cwd.absolutePath}")
    }

    private fun uiKotlinFiles(): List<File> = uiSourceRoot()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    private fun stripComments(source: String): String {
        val noBlockComments = Regex("/\\*[\\s\\S]*?\\*/").replace(source, " ")
        return noBlockComments.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//")) "" else line
        }
    }

    private fun extractBalancedCallArgs(text: String, openParenIndex: Int): String {
        var depth = 0
        var inString = false
        var escaped = false
        var i = openParenIndex
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(openParenIndex + 1, i)
                        }
                    }
                }
            }
            i++
        }
        error("Unclosed parenthesis at index $openParenIndex")
    }

    @Test
    fun everySegmentedListItemUsesSettingsSegmentColors() {
        val callPattern = Regex("\\bSegmentedListItem\\s*\\(")
        val colorsPattern = Regex("\\bcolors\\s*=\\s*settingsSegmentColors\\s*\\(\\s*\\)")
        var checkedCallCount = 0
        val violations = mutableListOf<String>()

        for (file in uiKotlinFiles()) {
            val sanitized = stripComments(file.readText())
            for (match in callPattern.findAll(sanitized)) {
                val openParenIndex = match.range.last
                val args = extractBalancedCallArgs(sanitized, openParenIndex)
                checkedCallCount++
                if (!colorsPattern.containsMatchIn(args)) {
                    val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                    violations += "${file.name}:$lineNumber misses colors = settingsSegmentColors()"
                }
            }
        }

        assertTrue("Expected at least one SegmentedListItem call site to check", checkedCallCount > 0)
        assertTrue(
            "SegmentedListItem call sites must pass colors = settingsSegmentColors():\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun uiMotionUsesUiPolishCurvesAndCentralizedPulse() {
        val disallowedEasings = Regex("\\b(FastOutSlowInEasing|FastOutLinearInEasing|LinearOutSlowInEasing)\\b")
        val infiniteTransitionCall = Regex("\\brememberInfiniteTransition\\s*\\(")
        val violations = mutableListOf<String>()

        for (file in uiKotlinFiles()) {
            val sanitized = stripComments(file.readText())
            for (match in disallowedEasings.findAll(sanitized)) {
                val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                violations += "${file.name}:$lineNumber uses disallowed easing ${match.value}; use EaseOutCubic / EaseInOutCubic / EaseDrawer"
            }
            if (file.name != "UiPolish.kt") {
                for (match in infiniteTransitionCall.findAll(sanitized)) {
                    val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                    violations += "${file.name}:$lineNumber calls rememberInfiniteTransition directly; use rememberRecordingPulseAlpha() in UiPolish.kt"
                }
            }
        }

        assertTrue(
            "UI motion violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun noAnimatedAlphaPassedToBackgroundCopy() {
        val bgAlphaIdentifier = Regex("""\.background\s*\([^)]*\.copy\s*\(\s*alpha\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)\s*[),]""")
        val violations = mutableListOf<String>()

        for (file in uiKotlinFiles()) {
            val sanitized = stripComments(file.readText())
            for (match in bgAlphaIdentifier.findAll(sanitized)) {
                val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                violations += "${file.name}:$lineNumber passes non-literal alpha '${match.groupValues[1]}' to .background(...copy); move alpha to Modifier.graphicsLayer"
            }
        }

        assertTrue(
            "Background alpha violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
