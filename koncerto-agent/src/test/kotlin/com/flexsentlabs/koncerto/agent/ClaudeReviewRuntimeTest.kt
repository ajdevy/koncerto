package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import org.junit.jupiter.api.Test

class ClaudeReviewRuntimeTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `hasNonZeroCritical empty string returns false`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        assertThat(runtime.hasNonZeroCritical("")).isFalse()
    }

    @Test
    fun `hasNonZeroCritical no critical section returns false`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        assertThat(runtime.hasNonZeroCritical("Everything looks good")).isFalse()
    }

    @Test
    fun `hasNonZeroCritical critical with zero returns false`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        assertThat(runtime.hasNonZeroCritical("""**Critical:** 0 remaining""")).isFalse()
    }

    @Test
    fun `hasNonZeroCritical critical with positive number returns true`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        assertThat(runtime.hasNonZeroCritical("""**Critical:** 3 remaining""")).isTrue()
    }

    @Test
    fun `hasNonZeroCritical critical with no digits returns false`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        assertThat(runtime.hasNonZeroCritical("""**Critical:** none remaining""")).isFalse()
    }

    @Test
    fun `hasNonZeroCritical multi line with positive critical returns true`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        val output = """
            Review results:
            **Critical:** 0 high
            **Critical:** 2 medium
        """.trimIndent()
        assertThat(runtime.hasNonZeroCritical(output)).isTrue()
    }

    @Test
    fun `hasNonZeroCritical ignores digits not in critical lines`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        val output = """
            Found 5 issues
            **Critical:** 0 remaining
        """.trimIndent()
        assertThat(runtime.hasNonZeroCritical(output)).isFalse()
    }

    @Test
    fun `hasNonZeroCritical zero critical with trailing digits does not count trailing digits`() {
        val ws = Files.createTempDirectory("claude-test-")
        val runtime = ClaudeReviewRuntime("echo ok", ws, noopLogger())
        // Old .filter{isDigit()} would extract "042" = 42 > 0 → wrongly return true
        assertThat(runtime.hasNonZeroCritical("**Critical:** 0 issues (42 hints)")).isFalse()
    }
}
