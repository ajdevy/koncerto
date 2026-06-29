package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test
    fun `send turn start runs review and writes pass status`() = runBlocking {
        val ws = Files.createTempDirectory("claude-review-")
        val runtime = ClaudeReviewRuntime("cat", ws, noopLogger())
        runtime.start(null)

        val events = AgentRuntimeTestSupport.collectEventsDuring(runtime, timeoutMs = 10_000) {
            runtime.send(
                "turn/start",
                buildJsonObject { put("input", JsonPrimitive("Review this change")) },
            )
        }
        runtime.stop()

        assertThat(events.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()).isNotNull()
        assertThat(Files.readString(ws.resolve(".review-status"))).isEqualTo("pass")
        assertThat(Files.exists(ws.resolve(".review-output"))).isTrue()
    }

    @Test
    fun `send turn start with fail marker writes fail status`() = runBlocking {
        val ws = Files.createTempDirectory("claude-review-fail-")
        val runtime = ClaudeReviewRuntime("""bash -lc 'printf "%s\n" "❌ FAIL" "issue found"'""", ws, noopLogger())
        runtime.start(null)

        AgentRuntimeTestSupport.collectEventsDuring(runtime, timeoutMs = 10_000) {
            runtime.send(
                "turn/start",
                buildJsonObject { put("input", JsonPrimitive("Review this change")) },
            )
        }
        runtime.stop()

        assertThat(Files.readString(ws.resolve(".review-status"))).isEqualTo("fail")
    }

    @Test
    fun `send turn start without input does not crash`() = runBlocking {
        val ws = Files.createTempDirectory("claude-review-no-input-")
        val runtime = ClaudeReviewRuntime("cat", ws, noopLogger())
        runtime.start(null)
        runtime.send("turn/start", null)
        runtime.stop()
    }

    @Test
    fun `send non turn start method returns ok without spawning`() {
        val ws = Files.createTempDirectory("claude-review-other-")
        val runtime = ClaudeReviewRuntime("cat", ws, noopLogger())
        val id = runtime.send("initialize", null)
        assertThat(id).isEqualTo("ok")
        runtime.stop()
    }

    @Test
    fun `isAlive false before review starts`() {
        val ws = Files.createTempDirectory("claude-review-alive-")
        val runtime = ClaudeReviewRuntime("cat", ws, noopLogger())
        assertThat(runtime.isAlive()).isFalse()
        runtime.stop()
    }

    @Test
    fun `start returns true`() = runBlocking {
        val ws = Files.createTempDirectory("claude-review-start-")
        val runtime = ClaudeReviewRuntime("cat", ws, noopLogger())
        assertThat(runtime.start(null)).isTrue()
        runtime.stop()
    }

    @Test
    fun `review output filters claude config noise lines`() = runBlocking {
        val ws = Files.createTempDirectory("claude-review-filter-")
        val runtime = ClaudeReviewRuntime(
            """bash -lc 'printf "%s\n" "Claude configuration file not found at: x" "A backup file exists at: y" "You can manually restore z" "✅ PASS"'""",
            ws,
            noopLogger()
        )
        runtime.start(null)
        AgentRuntimeTestSupport.collectEventsDuring(runtime, timeoutMs = 10_000) {
            runtime.send("turn/start", buildJsonObject { put("input", JsonPrimitive("review")) })
        }
        runtime.stop()
        val output = Files.readString(ws.resolve(".review-output"))
        assertThat(output.contains("configuration file not found")).isFalse()
        assertThat(output.contains("✅ PASS")).isTrue()
    }
}
