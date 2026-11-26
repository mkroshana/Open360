package com.open360.wear.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated design-rule checks enforcing Open360 motion, GPU composition, and editorial palette
 * invariants across Wear OS UI sources.
 */
class WearDesignRulesCheckTest {

    private fun wearUiSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java/com/open360/wear/ui"),
            File(cwd, "wear/src/main/java/com/open360/wear/ui")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate wear/src/main/java/com/open360/wear/ui from ${cwd.absolutePath}")
    }

    private fun wearUiKotlinFiles(): List<File> = wearUiSourceRoot()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    private fun stripComments(source: String): String {
        val noBlockComments = Regex("/\\*[\\s\\S]*?\\*/").replace(source) { match ->
            match.value.replace(Regex("[^\\n]"), " ")
        }
        return noBlockComments.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//")) "" else line
        }
    }

    @Test
    fun wearUiMotionUsesWearUiPolishCurvesAndCentralizedPulse() {
        val disallowedEasings = Regex("\\b(FastOutSlowInEasing|FastOutLinearInEasing|LinearOutSlowInEasing)\\b")
        val infiniteTransitionCall = Regex("\\brememberInfiniteTransition\\s*\\(")
        val violations = mutableListOf<String>()

        for (file in wearUiKotlinFiles()) {
            val sanitized = stripComments(file.readText())
            for (match in disallowedEasings.findAll(sanitized)) {
                val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                violations += "${file.name}:$lineNumber uses disallowed easing ${match.value}; use WearEaseOutCubic / WearEaseInOutCubic / WearEaseDrawer"
            }
            if (file.name != "WearUiPolish.kt") {
                for (match in infiniteTransitionCall.findAll(sanitized)) {
                    val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                    violations += "${file.name}:$lineNumber calls rememberInfiniteTransition directly; use rememberWearRecordingPulseAlpha() in WearUiPolish.kt"
                }
            }
        }

        assertTrue(
            "Wear UI motion violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun noAnimatedAlphaPassedToBackgroundCopy() {
        val bgAlphaIdentifier = Regex("""\.background\s*\([^)]*\.copy\s*\(\s*alpha\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)\s*[),]""")
        val violations = mutableListOf<String>()

        for (file in wearUiKotlinFiles()) {
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

    @Test
    fun noLegacyHardcodedIosStatusHexColors() {
        val legacyHexPattern = Regex("\\b0xFF(?:FF3B30|34C759|FF9500)\\b", RegexOption.IGNORE_CASE)
        val violations = mutableListOf<String>()

        for (file in wearUiKotlinFiles()) {
            val sanitized = stripComments(file.readText())
            for (match in legacyHexPattern.findAll(sanitized)) {
                val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                violations += "${file.name}:$lineNumber uses legacy hardcoded status hex ${match.value}; use MaterialTheme.wearStatusColors"
            }
        }

        assertTrue(
            "Legacy status color violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun findEnclosingCallSpan(text: String, callStartIndex: Int): IntRange? {
        var parenDepth = 0
        var openParen = -1
        for (i in callStartIndex - 1 downTo 0) {
            when (text[i]) {
                ')' -> parenDepth++
                '(' -> if (parenDepth == 0) {
                    openParen = i
                    break
                } else {
                    parenDepth--
                }
            }
        }
        if (openParen < 0) return null
        var forwardDepth = 1
        for (i in openParen + 1 until text.length) {
            when (text[i]) {
                '(' -> forwardDepth++
                ')' -> {
                    forwardDepth--
                    if (forwardDepth == 0) return openParen..i
                }
            }
        }
        return null
    }

    @Test
    fun transformedHeightMustPairWithSurfaceTransformation() {
        val transformedHeightCall = Regex("\\btransformedHeight\\s*\\(")
        val violations = mutableListOf<String>()

        for (file in wearUiKotlinFiles()) {
            val sanitized = stripComments(file.readText())
            for (match in transformedHeightCall.findAll(sanitized)) {
                val lineNumber = sanitized.substring(0, match.range.first).count { it == '\n' } + 1
                val callSpan = findEnclosingCallSpan(sanitized, match.range.first)
                val enclosingArgs = callSpan?.let { sanitized.substring(it.first, it.last + 1) }.orEmpty()
                if (!enclosingArgs.contains("SurfaceTransformation(")) {
                    violations += "${file.name}:$lineNumber uses Modifier.transformedHeight(...) without SurfaceTransformation(...)"
                }
            }
        }

        assertTrue(
            "Bare transformedHeight violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
