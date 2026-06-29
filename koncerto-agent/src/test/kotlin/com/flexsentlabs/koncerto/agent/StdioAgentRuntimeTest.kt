package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.contains
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test

class StdioAgentRuntimeTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `isAlive reflects process lifecycle`() {
        val ws = Files.createTempDirectory("stdio-alive-")
        val runtime = OpencodeRuntime("sleep 2", ws, noopLogger())
        runBlocking { runtime.start() }
        assertThat(runtime.isAlive()).isTrue()
        runtime.stop()
        assertThat(runtime.isAlive()).isFalse()
    }

    @Test
    fun `output flow captures stdout and stderr lines`() {
        val ws = Files.createTempDirectory("stdio-output-")
        val script = """
            echo 'stdout line'
            echo 'stderr line' >&2
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val outputLines = AgentRuntimeTestSupport.collectOutputDuring(runtime) {
            runtime.start()
        }

        runtime.stop()
        assertThat(outputLines.any { it.contains("stdout line") }).isTrue()
        assertThat(outputLines.any { it.contains("stderr line") }).isTrue()
    }

    @Test
    fun `writeRaw sends data to process`() {
        val ws = Files.createTempDirectory("stdio-write-")
        val runtime = OpencodeRuntime("cat", ws, noopLogger())

        val outputLines = AgentRuntimeTestSupport.collectOutputDuring(
            runtime,
            predicate = { lines -> lines.any { it.contains("test-data") } },
        ) {
            runtime.start()
            runtime.writeRaw("test-data")
            runtime.closeStdin()
        }

        runtime.stop()
        assertThat(outputLines.any { it.contains("test-data") }).isTrue()
    }

    @Test
    fun `closeStdin allows process to exit`() {
        val ws = Files.createTempDirectory("stdio-close-")
        val runtime = OpencodeRuntime("cat", ws, noopLogger())
        runBlocking { runtime.start() }
        assertThat(runtime.isAlive()).isTrue()
        runtime.closeStdin()
        val deadline = System.currentTimeMillis() + 5_000
        while (runtime.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertThat(runtime.isAlive()).isFalse()
    }

    @Test
    fun `closeStdin prevents further writes`() {
        val ws = Files.createTempDirectory("stdio-close2-")
        val runtime = OpencodeRuntime("cat", ws, noopLogger())
        runBlocking { runtime.start() }
        runtime.closeStdin()
        runtime.send("ping")
        runtime.stop()
    }

    @Test
    fun `sendMessage sends agent message request`() {
        val ws = Files.createTempDirectory("stdio-sendmsg-")
        val runtime = OpencodeRuntime("cat", ws, noopLogger())

        val outputLines = AgentRuntimeTestSupport.collectOutputDuring(
            runtime,
            predicate = { lines ->
                lines.any { it.contains("\"method\":\"agent/message\"") } &&
                    lines.any { it.contains("\"to_agent_id\":\"target-agent\"") }
            },
        ) {
            runtime.start()
            runtime.sendMessage("target-agent", "test payload")
            runtime.closeStdin()
        }

        runtime.stop()
        assertThat(outputLines.any { it.contains("\"method\":\"agent/message\"") }).isTrue()
        assertThat(outputLines.any { it.contains("\"to_agent_id\":\"target-agent\"") }).isTrue()
    }

    @Test
    fun `agent message notification produces AgentMessage event`() {
        val ws = Files.createTempDirectory("stdio-amsg-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"agent/message","params":{"from_agent_id":"agent-1","payload":"hello","message_id":"msg-1"}}'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()

        val event = collected.filterIsInstance<AgentEvent.AgentMessage>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.fromAgentId).isEqualTo("agent-1")
        assertThat(event.payload).isEqualTo("hello")
        assertThat(event.messageId).isEqualTo("msg-1")
    }

    @Test
    fun `JSONL thread started event parsed correctly`() {
        val ws = Files.createTempDirectory("stdio-jsonl-ts-")
        val script = """
            printf '%s\n' '{"type":"thread.started","thread_id":"th-1"}'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()

        val event = collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isEqualTo("th-1")
    }

    @Test
    fun `JSONL turn completed event with usage parsed correctly`() {
        val ws = Files.createTempDirectory("stdio-jsonl-tc-")
        val script = """
            printf '%s\n' '{"type":"turn.completed","usage":{"input_tokens":50,"output_tokens":25,"total_tokens":75}}'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()

        val event = collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage).isNotNull()
        assertThat(event.usage!!.inputTokens).isEqualTo(50L)
        assertThat(event.usage!!.totalTokens).isEqualTo(75L)
    }

    @Test
    fun `JSONL error event parsed correctly`() {
        val ws = Files.createTempDirectory("stdio-jsonl-err-")
        val script = """
            printf '%s\n' '{"type":"error","message":"something went wrong"}'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()

        val event = collected.filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.error).isEqualTo("something went wrong")
    }

    @Test
    fun `JSONL turn started and item events emit to output flow`() {
        val ws = Files.createTempDirectory("stdio-jsonl-items-")
        val script = """
            printf '%s\n' '{"type":"turn.started"}'
            printf '%s\n' '{"type":"item.started"}'
            printf '%s\n' '{"type":"item.completed"}'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val outputLines = AgentRuntimeTestSupport.collectOutputDuring(
            runtime,
            predicate = { lines ->
                lines.any { it.contains("turn.started") } &&
                    lines.any { it.contains("item.started") } &&
                    lines.any { it.contains("item.completed") }
            },
        ) {
            runtime.start()
        }

        runtime.stop()
        assertThat(outputLines.any { it.contains("turn.started") }).isTrue()
        assertThat(outputLines.any { it.contains("item.started") }).isTrue()
        assertThat(outputLines.any { it.contains("item.completed") }).isTrue()
    }

    @Test
    fun `JSONL stream ending without turn completed emits synthetic TurnCompleted`() {
        val ws = Files.createTempDirectory("stdio-jsonl-synthetic-")
        val script = """
            printf '%s\n' '{"type":"turn.started"}'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()

        assertThat(collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()).isNotNull()
    }

    @Test
    fun `malformed stdout emits Malformed event`() {
        val ws = Files.createTempDirectory("stdio-malformed-")
        val script = """
            echo 'not-json-at-all'
            sleep 0.5
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()

        val event = collected.filterIsInstance<AgentEvent.Malformed>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.raw).isEqualTo("not-json-at-all")
    }

    @Test
    fun `startup failure emits StartupFailed event`() {
        val ws = java.nio.file.Path.of("/nonexistent/workspace/path/that/does/not/exist")
        val runtime = OpencodeRuntime("echo hello", ws, noopLogger())

        val collected = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            val started = runtime.start()
            assertThat(started).isFalse()
        }
        runtime.stop()

        assertThat(collected.filterIsInstance<AgentEvent.StartupFailed>().firstOrNull()).isNotNull()
    }

    @Test
    fun `stop closes event channel and stops process`() {
        val ws = Files.createTempDirectory("stdio-stop-")
        val runtime = OpencodeRuntime("echo hello", ws, noopLogger())
        runBlocking { runtime.start() }
        assertThat(runtime.isAlive()).isTrue()
        runtime.stop()
        assertThat(runtime.isAlive()).isFalse()
    }

    @Test
    fun `jsonl stream end emits synthetic turn completed`() = runBlocking {
        val ws = Files.createTempDirectory("stdio-jsonl-end-")
        val runtime = OpencodeRuntime("sleep 0.1", ws, noopLogger())
        val setJsonlMode = StdioAgentRuntime::class.java.getDeclaredField("jsonlMode")
        setJsonlMode.isAccessible = true
        setJsonlMode.setBoolean(runtime, true)
        val events = mutableListOf<AgentEvent>()
        val job = launch {
            runtime.events().collect { events.add(it) }
        }
        val readStdout = StdioAgentRuntime::class.java.getDeclaredMethod(
            "readStdout", java.io.BufferedReader::class.java
        )
        readStdout.isAccessible = true
        readStdout.invoke(runtime, java.io.BufferedReader(java.io.StringReader("")))
        kotlinx.coroutines.delay(50)
        runtime.stop()
        job.cancel()
        assertThat(events.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()).isNotNull()
    }

    @Test
    fun `sendMessage writes agent message request`() {
        val ws = Files.createTempDirectory("stdio-send-msg-")
        val runtime = OpencodeRuntime("cat", ws, noopLogger())
        runBlocking { runtime.start() }
        runtime.sendMessage("agent-b", "payload")
        runtime.closeStdin()
        runtime.stop()
    }

    @Test
    fun `JSONL unknown event type is emitted to output`() {
        val ws = Files.createTempDirectory("stdio-jsonl-unknown-")
        val script = """
            printf '%s\n' '{"type":"custom.event"}'
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())
        val output = AgentRuntimeTestSupport.collectOutputDuring(runtime) {
            runtime.start()
        }
        runtime.stop()
        assertThat(output.any { it.contains("[jsonl] custom.event") }).isTrue()
    }

    @Test
    fun `turn cancelled notification emits TurnCancelled event`() {
        val ws = Files.createTempDirectory("stdio-turn-cancelled-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/cancelled","params":{"thread_id":"th-1","turn_id":"tu-1"}}'
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())
        val events = AgentRuntimeTestSupport.collectEventsDuring(runtime) {
            runtime.start()
        }
        runtime.stop()
        val cancelled = events.filterIsInstance<AgentEvent.TurnCancelled>().firstOrNull()
        assertThat(cancelled).isNotNull()
        assertThat(cancelled!!.threadId).isEqualTo("th-1")
        assertThat(cancelled.turnId).isEqualTo("tu-1")
    }
}
