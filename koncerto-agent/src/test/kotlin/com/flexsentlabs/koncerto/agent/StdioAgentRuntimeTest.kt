package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class StdioAgentRuntimeTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun collectEvents(runtime: OpencodeRuntime, timeoutMs: Long = 5_000): List<AgentEvent> {
        val collected = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val collector = thread(start = true, isDaemon = true, name = "stdio-events") {
            runBlocking {
                runtime.events().collect { ev ->
                    collected += ev
                    cancel()
                }
            }
        }
        collector.join(timeoutMs)
        if (collector.isAlive) collector.interrupt()
        return collected.toList()
    }

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
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val outputLines = mutableListOf<String>()
        val collector = thread(start = true, isDaemon = true) {
            runBlocking {
                runtime.output.collect { line -> outputLines += line }
            }
        }

        runBlocking { runtime.start() }
        Thread.sleep(500)
        runtime.stop()
        collector.interrupt()

        assertThat(outputLines.any { it.contains("stdout line") }).isTrue()
        assertThat(outputLines.any { it.contains("stderr line") }).isTrue()
    }

    @Test
    fun `writeRaw sends data to process`() {
        val ws = Files.createTempDirectory("stdio-write-")
        val runtime = OpencodeRuntime("cat", ws, noopLogger())
        runBlocking { runtime.start() }

        val outputLines = mutableListOf<String>()
        val collector = thread(start = true, isDaemon = true) {
            runBlocking {
                runtime.output.collect { line -> outputLines += line }
            }
        }

        runtime.writeRaw("test-data")
        Thread.sleep(200)
        runtime.closeStdin()
        Thread.sleep(200)
        runtime.stop()
        collector.interrupt()

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
        runBlocking { runtime.start() }

        val outputLines = mutableListOf<String>()
        val collector = thread(start = true, isDaemon = true) {
            runBlocking {
                runtime.output.collect { line -> outputLines += line }
            }
        }

        runtime.sendMessage("target-agent", "test payload")
        Thread.sleep(300)
        runtime.closeStdin()
        Thread.sleep(300)
        runtime.stop()
        collector.interrupt()

        assertThat(outputLines.any { it.contains("\"method\":\"agent/message\"") }).isTrue()
        assertThat(outputLines.any { it.contains("\"to_agent_id\":\"target-agent\"") }).isTrue()
    }

    @Test
    fun `agent message notification produces AgentMessage event`() {
        val ws = Files.createTempDirectory("stdio-amsg-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"agent/message","params":{"from_agent_id":"agent-1","payload":"hello","message_id":"msg-1"}}'
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())
        runBlocking { runtime.start() }

        val collected = collectEvents(runtime)
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
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())
        runBlocking { runtime.start() }

        val collected = collectEvents(runtime)
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
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())
        runBlocking { runtime.start() }

        val collected = collectEvents(runtime)
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
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())
        runBlocking { runtime.start() }

        val collected = collectEvents(runtime)
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
            sleep 0.2
        """.trimIndent()
        val runtime = OpencodeRuntime(script, ws, noopLogger())

        val outputLines = mutableListOf<String>()
        val collector = thread(start = true, isDaemon = true) {
            runBlocking {
                runtime.output.collect { line -> outputLines += line }
            }
        }

        runBlocking { runtime.start() }
        Thread.sleep(500)
        runtime.stop()
        collector.interrupt()

        assertThat(outputLines.any { it.contains("turn.started") }).isTrue()
        assertThat(outputLines.any { it.contains("item.started") }).isTrue()
        assertThat(outputLines.any { it.contains("item.completed") }).isTrue()
    }

    @Test
    fun `stop closes event channel and stops process`() {
        val ws = Files.createTempDirectory("stdio-stop-")
        val runtime = OpencodeRuntime("echo hello", ws, noopLogger())
        runBlocking { runtime.start() }
        assertThat(runtime.isAlive()).isTrue()
        runtime.stop()
        assertThat(runtime.isAlive()).isFalse()
        val collected = collectEvents(runtime, timeoutMs = 2_000)
        assertThat(collected).isNotNull()
    }
}
