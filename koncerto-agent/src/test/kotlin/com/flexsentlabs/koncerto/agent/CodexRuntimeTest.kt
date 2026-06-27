package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CodexRuntimeTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `client spawns and receives stdout as events`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            assertThat(client.start()).isEqualTo(true)
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch turn_completed notification with usage`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1","usage":{"input_tokens":100,"output_tokens":50,"total_tokens":150}}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isEqualTo("t1")
        assertThat(event.usage).isNotNull()
        assertThat(event.usage!!.inputTokens).isEqualTo(100L)
    }

    @Test
    fun `dispatch turn_failed notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/failed","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.error).isEqualTo("agent_reported_failure")
    }

    @Test
    fun `dispatch turn_cancelled notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/cancelled","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(
            client,
            until = { events -> events.any { it is AgentEvent.TurnCancelled } },
        ) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCancelled>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isEqualTo("t1")
    }

    @Test
    fun `dispatch turn_input_required notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/input_required","params":{}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.TurnInputRequired>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch approval_auto_approved notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"approval/auto_approved","params":{}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.ApprovalAutoApproved>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch unsupported_tool_call notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"unsupported_tool_call","params":{}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.UnsupportedToolCall>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch unknown notification method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"custom/unknown_event","params":{"foo":"bar"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.Notification>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.method).isEqualTo("custom/unknown_event")
    }

    @Test
    fun `dispatch response with session started method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"session/started","thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isEqualTo("t1")
    }

    @Test
    fun `dispatch response with turn completed method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"turn/completed","thread_id":"t1","turn_id":"u1","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage).isNotNull()
        assertThat(event.usage!!.inputTokens).isEqualTo(10L)
    }

    @Test
    fun `dispatch response with turn failed method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"turn/failed","thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `dispatch response with turn cancelled method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"turn/cancelled","thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCancelled>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `dispatch response with unknown method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"custom/result","data":"x"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.OtherMessage>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `malformed json emits Malformed event`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            echo 'this is not valid json {{{'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.Malformed>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.raw).isEqualTo("this is not valid json {{{")
    }

    @Test
    fun `startup failure emits StartupFailed event`() {
        val ws = java.nio.file.Path.of("/nonexistent/workspace/path/that/does/not/exist")
        val client = CodexRuntime("echo hello", ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            assertThat(client.start()).isEqualTo(false)
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.StartupFailed>().firstOrNull()).isNotNull()
    }

    @Test
    fun `stop closes event channel`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        runBlocking { client.start() }
        client.stop()
        assertThat(client.isAlive()).isFalse()
    }

    @Test
    fun `usage extraction with missing total_tokens falls back to input plus output`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1","usage":{"input_tokens":30,"output_tokens":20}}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(
            client,
            until = { events -> events.any { it is AgentEvent.TurnCompleted } },
        ) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage).isNotNull()
        assertThat(event.usage!!.totalTokens).isEqualTo(50L)
    }

    @Test
    fun `usage extraction with non-numeric tokens defaults to zero`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1","usage":{"input_tokens":"abc","output_tokens":"def"}}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage).isNotNull()
        assertThat(event.usage!!.inputTokens).isEqualTo(0L)
        assertThat(event.usage!!.outputTokens).isEqualTo(0L)
    }

    @Test
    fun `session started with missing thread_id generates UUID`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"session/started","params":{"turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isNotNull()
        assertThat(event.threadId.length).isEqualTo(36) // UUID format
    }

    @Test
    fun `response with no method field dispatches as OtherMessage`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"data":"no method here"}}'
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.OtherMessage>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `send returns incrementing request ids`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = "sleep 2"
        val client = CodexRuntime(script, ws, noopLogger())
        runBlocking { client.start() }

        val id1 = client.send("initialize")
        val id2 = client.send("thread/start")
        client.stop()
        assertThat(id1).isEqualTo("1")
        assertThat(id2).isEqualTo("2")
    }

    @Test
    fun `blank lines in stdout are skipped`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            echo ''
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo ''
            sleep 0.5
        """.trimIndent()
        val client = CodexRuntime(script, ws, noopLogger())
        val collected = AgentRuntimeTestSupport.collectEventsDuring(client) {
            client.start()
        }
        client.stop()
        assertThat(collected.size).isEqualTo(1)
        assertThat(collected[0] is AgentEvent.SessionStarted).isEqualTo(true)
    }
}
