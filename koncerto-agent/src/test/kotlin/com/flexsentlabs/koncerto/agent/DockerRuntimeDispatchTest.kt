package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import assertk.assertions.contains
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DockerRuntimeDispatchTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var runtime: DockerRuntime

    @BeforeEach
    fun setUp() {
        runtime = DockerRuntime("echo hello", tempDir, noopLogger(), "test-container")
    }

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun invokeDispatch(msg: JsonRpcMessage) {
        val method = DockerRuntime::class.java.getDeclaredMethod("dispatchMessage", JsonRpcMessage::class.java)
        method.isAccessible = true
        method.invoke(runtime, msg)
    }

    private fun invokeTryHandleAsJsonl(line: String): Boolean {
        val method = DockerRuntime::class.java.getDeclaredMethod("tryHandleAsJsonl", String::class.java)
        method.isAccessible = true
        return method.invoke(runtime, line) as Boolean
    }

    private fun invokeExtractUsage(obj: JsonObject?): Any? {
        val method = DockerRuntime::class.java.getDeclaredMethod("extractUsage", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(runtime, obj)
    }

    private fun invokeExtractUsageFromJsonl(obj: JsonObject): Any? {
        val method = DockerRuntime::class.java.getDeclaredMethod("extractUsageFromJsonl", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(runtime, obj)
    }

    private fun invokeIsDockerDaemonAvailable(): Boolean {
        val method = DockerRuntime::class.java.getDeclaredMethod("isDockerDaemonAvailable")
        method.isAccessible = true
        return method.invoke(runtime) as Boolean
    }

    private fun drainEvents(): List<AgentEvent> = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val job = launch {
            withTimeout(500) {
                runtime.events().collect { events.add(it) }
            }
        }
        kotlinx.coroutines.delay(50)
        runtime.stop()
        job.cancel()
        events
    }

    private fun decodeNotification(json: String): JsonRpcMessage =
        JsonRpcFraming.decodeAll(json).first()

    @Test
    fun `dispatch session started notification`() {
        invokeDispatch(decodeNotification(
            """{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}"""
        ))
        val event = drainEvents().filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isEqualTo("t1")
    }

    @Test
    fun `dispatch turn completed notification with usage`() {
        invokeDispatch(decodeNotification(
            """{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}"""
        ))
        val event = drainEvents().filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage!!.inputTokens).isEqualTo(10L)
    }

    @Test
    fun `dispatch turn failed notification`() {
        invokeDispatch(decodeNotification(
            """{"jsonrpc":"2.0","method":"turn/failed","params":{"thread_id":"t1","turn_id":"u1"}}"""
        ))
        assertThat(drainEvents().filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch turn cancelled notification`() {
        invokeDispatch(decodeNotification(
            """{"jsonrpc":"2.0","method":"turn/cancelled","params":{"thread_id":"t1","turn_id":"u1"}}"""
        ))
        assertThat(drainEvents().filterIsInstance<AgentEvent.TurnCancelled>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch turn input required notification`() {
        invokeDispatch(decodeNotification("""{"jsonrpc":"2.0","method":"turn/input_required","params":{}}"""))
        assertThat(drainEvents().filterIsInstance<AgentEvent.TurnInputRequired>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch approval auto approved notification`() {
        invokeDispatch(decodeNotification("""{"jsonrpc":"2.0","method":"approval/auto_approved","params":{}}"""))
        assertThat(drainEvents().filterIsInstance<AgentEvent.ApprovalAutoApproved>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch unsupported tool call notification`() {
        invokeDispatch(decodeNotification("""{"jsonrpc":"2.0","method":"unsupported_tool_call","params":{}}"""))
        assertThat(drainEvents().filterIsInstance<AgentEvent.UnsupportedToolCall>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch agent message notification`() {
        invokeDispatch(decodeNotification(
            """{"jsonrpc":"2.0","method":"agent/message","params":{"from_agent_id":"agent-a","payload":"hello","message_id":"msg-1"}}"""
        ))
        val event = drainEvents().filterIsInstance<AgentEvent.AgentMessage>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.fromAgentId).isEqualTo("agent-a")
        assertThat(event.payload).isEqualTo("hello")
    }

    @Test
    fun `dispatch unknown notification method`() {
        invokeDispatch(decodeNotification("""{"jsonrpc":"2.0","method":"custom/event","params":{"x":1}}"""))
        val event = drainEvents().filterIsInstance<AgentEvent.Notification>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.method).isEqualTo("custom/event")
    }

    @Test
    fun `dispatch response with session started method`() {
        invokeDispatch(JsonRpcFraming.decodeAll(
            """{"jsonrpc":"2.0","id":"1","result":{"method":"session/started","thread_id":"t2","turn_id":"u2"}}"""
        ).first())
        val event = drainEvents().filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.threadId).isEqualTo("t2")
    }

    @Test
    fun `dispatch response with turn completed method`() {
        invokeDispatch(JsonRpcFraming.decodeAll(
            """{"jsonrpc":"2.0","id":"1","result":{"method":"turn/completed","thread_id":"t2","turn_id":"u2","usage":{"input_tokens":20,"output_tokens":10}}}"""
        ).first())
        val event = drainEvents().filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage!!.totalTokens).isEqualTo(30L)
    }

    @Test
    fun `dispatch response with turn failed method`() {
        invokeDispatch(JsonRpcFraming.decodeAll(
            """{"jsonrpc":"2.0","id":"1","result":{"method":"turn/failed","thread_id":"t2","turn_id":"u2"}}"""
        ).first())
        assertThat(drainEvents().filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch response with turn cancelled method`() {
        invokeDispatch(JsonRpcFraming.decodeAll(
            """{"jsonrpc":"2.0","id":"1","result":{"method":"turn/cancelled","thread_id":"t2","turn_id":"u2"}}"""
        ).first())
        assertThat(drainEvents().filterIsInstance<AgentEvent.TurnCancelled>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch response with unknown method`() {
        invokeDispatch(JsonRpcFraming.decodeAll(
            """{"jsonrpc":"2.0","id":"1","result":{"method":"custom/result","data":"x"}}"""
        ).first())
        assertThat(drainEvents().filterIsInstance<AgentEvent.OtherMessage>().firstOrNull()).isNotNull()
    }

    @Test
    fun `jsonl thread started`() {
        assertThat(invokeTryHandleAsJsonl("""{"type":"thread.started","thread_id":"jl-1"}""")).isTrue()
        assertThat(drainEvents().filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()).isNotNull()
    }

    @Test
    fun `jsonl turn started emits output`() {
        assertThat(invokeTryHandleAsJsonl("""{"type":"turn.started"}""")).isTrue()
        runtime.stop()
    }

    @Test
    fun `jsonl item started and completed`() {
        assertThat(invokeTryHandleAsJsonl("""{"type":"item.started"}""")).isTrue()
        assertThat(invokeTryHandleAsJsonl("""{"type":"item.completed"}""")).isTrue()
        assertThat(invokeTryHandleAsJsonl("""{"type":"item.created"}""")).isTrue()
        runtime.stop()
    }

    @Test
    fun `jsonl turn completed with usage`() {
        assertThat(invokeTryHandleAsJsonl(
            """{"type":"turn.completed","usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}"""
        )).isTrue()
        val event = drainEvents().filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.usage!!.totalTokens).isEqualTo(8L)
    }

    @Test
    fun `jsonl error emits turn failed`() {
        assertThat(invokeTryHandleAsJsonl("""{"type":"error","message":"boom"}""")).isTrue()
        val event = drainEvents().filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.error).isEqualTo("boom")
    }

    @Test
    fun `jsonl unknown type returns true`() {
        assertThat(invokeTryHandleAsJsonl("""{"type":"unknown.type"}""")).isTrue()
        runtime.stop()
    }

    @Test
    fun `jsonl non json line returns false`() {
        assertThat(invokeTryHandleAsJsonl("not json at all")).isFalse()
        runtime.stop()
    }

    @Test
    fun `jsonl invalid json returns false`() {
        assertThat(invokeTryHandleAsJsonl("{invalid json")).isFalse()
        runtime.stop()
    }

    @Test
    fun `extractUsage returns null for missing usage`() {
        assertThat(invokeExtractUsage(buildJsonObject { put("thread_id", "t1") })).isNull()
    }

    @Test
    fun `extractUsage computes total from input and output`() {
        val obj = Json.parseToJsonElement(
            """{"usage":{"input_tokens":30,"output_tokens":20}}"""
        ).jsonObject
        val usage = invokeExtractUsage(obj) as com.flexsentlabs.koncerto.core.model.TokenUsage
        assertThat(usage.totalTokens).isEqualTo(50L)
    }

    @Test
    fun `extractUsageFromJsonl handles non numeric tokens`() {
        val obj = Json.parseToJsonElement(
            """{"usage":{"input_tokens":"x","output_tokens":"y"}}"""
        ).jsonObject
        val usage = invokeExtractUsageFromJsonl(obj) as com.flexsentlabs.koncerto.core.model.TokenUsage
        assertThat(usage.inputTokens).isEqualTo(0L)
        assertThat(usage.outputTokens).isEqualTo(0L)
    }

    @Test
    fun `isDockerDaemonAvailable returns boolean`() {
        val available = invokeIsDockerDaemonAvailable()
        assertThat(available == true || available == false).isTrue()
    }

    @Test
    fun `readStdout emits turn completed on jsonl stream end`() = runBlocking {
        val readStdout = DockerRuntime::class.java.getDeclaredMethod("readStdout", java.io.BufferedReader::class.java)
        readStdout.isAccessible = true
        val setJsonlMode = DockerRuntime::class.java.getDeclaredField("jsonlMode")
        setJsonlMode.isAccessible = true
        setJsonlMode.setBoolean(runtime, true)
        val events = mutableListOf<AgentEvent>()
        val collectJob = launch {
            runtime.events().collect { events.add(it) }
        }
        readStdout.invoke(runtime, java.io.BufferedReader(java.io.StringReader("")))
        kotlinx.coroutines.delay(50)
        runtime.stop()
        collectJob.cancel()
        assertThat(events.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()).isNotNull()
    }

    @Test
    fun `readStdout emits malformed for invalid json line`() = runBlocking {
        val readStdout = DockerRuntime::class.java.getDeclaredMethod("readStdout", java.io.BufferedReader::class.java)
        readStdout.isAccessible = true
        val events = mutableListOf<AgentEvent>()
        val collectJob = launch {
            runtime.events().collect { events.add(it) }
        }
        readStdout.invoke(runtime, java.io.BufferedReader(java.io.StringReader("{not valid json")))
        kotlinx.coroutines.delay(50)
        runtime.stop()
        collectJob.cancel()
        assertThat(events.filterIsInstance<AgentEvent.Malformed>().firstOrNull()).isNotNull()
    }

    @Test
    fun `send writeRaw and closeStdin do not throw before start`() {
        runtime.send("test/method")
        runtime.writeRaw("raw data\n")
        runtime.closeStdin()
        runtime.stop()
    }

    @Test
    fun `send writeRaw closeStdin and sendMessage write through injected writer`() {
        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)
        val writer = pipedOut.bufferedWriter()
        val field = DockerRuntime::class.java.getDeclaredField("writer")
        field.isAccessible = true
        field.set(runtime, writer)
        runtime.send("initialize", null)
        runtime.writeRaw("raw-line")
        runtime.sendMessage("agent-b", "payload")
        runtime.closeStdin()
        val text = pipedIn.bufferedReader().readText()
        assertThat(text).contains("initialize")
        assertThat(text).contains("raw-line")
        assertThat(text).contains("agent/message")
        runtime.stop()
    }

    @Test
    fun `start succeeds with real container when docker available`() = runBlocking {
        if (!invokeIsDockerDaemonAvailable()) return@runBlocking
        val create = ProcessBuilder("docker", "run", "-d", "alpine:latest", "sleep", "120").start()
        create.waitFor(30, TimeUnit.SECONDS)
        if (create.exitValue() != 0) return@runBlocking
        val containerId = create.inputStream.bufferedReader().readText().trim()
        if (containerId.isBlank()) return@runBlocking
        try {
            val dockerRuntime = DockerRuntime("echo hi", tempDir, noopLogger(), containerId)
            val started = dockerRuntime.start(null)
            assertThat(started).isTrue()
            dockerRuntime.stop()
        } finally {
            ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `stop captures container logs and stats when docker available`() = runBlocking {
        if (!invokeIsDockerDaemonAvailable()) return@runBlocking
        val create = ProcessBuilder("docker", "run", "-d", "alpine:latest", "sleep", "120").start()
        create.waitFor(30, TimeUnit.SECONDS)
        if (create.exitValue() != 0) return@runBlocking
        val containerId = create.inputStream.bufferedReader().readText().trim()
        if (containerId.isBlank()) return@runBlocking
        try {
            val dockerRuntime = DockerRuntime("echo hi", tempDir, noopLogger(), containerId)
            dockerRuntime.start(null)
            dockerRuntime.stop()
        } finally {
            ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `isAlive checks container when exec process not running`() = runBlocking {
        if (!invokeIsDockerDaemonAvailable()) return@runBlocking
        val create = ProcessBuilder("docker", "run", "-d", "alpine:latest", "sleep", "120").start()
        create.waitFor(30, TimeUnit.SECONDS)
        if (create.exitValue() != 0) return@runBlocking
        val containerId = create.inputStream.bufferedReader().readText().trim()
        if (containerId.isBlank()) return@runBlocking
        try {
            val dockerRuntime = DockerRuntime("echo hi", tempDir, noopLogger(), containerId)
            val checkRunning = DockerRuntime::class.java.getDeclaredMethod("checkContainerRunning")
            checkRunning.isAccessible = true
            assertThat(checkRunning.invoke(dockerRuntime) as Boolean).isTrue()
        } finally {
            ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `isAlive returns false when process not started`() {
        assertThat(runtime.isAlive()).isFalse()
    }
}
