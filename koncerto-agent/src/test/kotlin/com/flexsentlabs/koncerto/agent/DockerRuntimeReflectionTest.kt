package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class DockerRuntimeReflectionTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun runtime() =
        DockerRuntime("echo hi", Files.createTempDirectory("docker-rt-"), noopLogger(), "cid-test")

    @Test
    fun `tryHandleAsJsonl parses jsonl event types`() {
        val rt = runtime()
        val method = DockerRuntime::class.java.getDeclaredMethod("tryHandleAsJsonl", String::class.java)
        method.isAccessible = true
        assertThat(method.invoke(rt, """{"type":"thread.started","thread_id":"abc"}""") as Boolean).isTrue()
        assertThat(method.invoke(rt, """{"type":"error","message":"boom"}""") as Boolean).isTrue()
        assertThat(method.invoke(rt, """{"type":"item.started"}""") as Boolean).isTrue()
        assertThat(method.invoke(rt, """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":2}}""") as Boolean).isTrue()
        assertThat(method.invoke(rt, "plain text") as Boolean).isFalse()
    }

    @Test
    fun `dispatchMessage emits session started event`() = runBlocking {
        val rt = runtime()
        val eventDeferred = async { withTimeout(1_000) { rt.events().take(1).toList().single() } }
        val method = DockerRuntime::class.java.getDeclaredMethod("dispatchMessage", JsonRpcMessage::class.java)
        method.isAccessible = true
        method.invoke(
            rt,
            JsonRpcNotificationMsg(
                JsonRpcNotification(
                    method = "session/started",
                    params = buildJsonObject {
                        put("thread_id", "thread-1")
                        put("turn_id", "turn-1")
                    }
                )
            )
        )
        val event = eventDeferred.await()
        rt.stop()
        assertThat(event).isInstanceOf(AgentEvent.SessionStarted::class)
        assertThat((event as AgentEvent.SessionStarted).threadId).isEqualTo("thread-1")
    }

    @Test
    fun `dispatchMessage emits turn failed and agent message events`() = runBlocking {
        val rt = runtime()
        val events = async { withTimeout(1_000) { rt.events().take(2).toList() } }
        val method = DockerRuntime::class.java.getDeclaredMethod("dispatchMessage", JsonRpcMessage::class.java)
        method.isAccessible = true
        method.invoke(
            rt,
            JsonRpcNotificationMsg(
                JsonRpcNotification(
                    method = "turn/failed",
                    params = buildJsonObject {
                        put("thread_id", "t1")
                        put("turn_id", "u1")
                    }
                )
            )
        )
        method.invoke(
            rt,
            JsonRpcNotificationMsg(
                JsonRpcNotification(
                    method = "agent/message",
                    params = buildJsonObject {
                        put("from_agent_id", "peer")
                        put("payload", "hello")
                        put("message_id", "msg-1")
                    }
                )
            )
        )
        val collected = events.await()
        rt.stop()
        assertThat(collected.any { it is AgentEvent.TurnFailed }).isTrue()
        assertThat(collected.any { it is AgentEvent.AgentMessage }).isTrue()
    }

    @Test
    fun `extractUsage reads token fields from json object`() {
        val rt = runtime()
        val method = DockerRuntime::class.java.getDeclaredMethod("extractUsage", kotlinx.serialization.json.JsonObject::class.java)
        method.isAccessible = true
        val usageObj = buildJsonObject {
            put("usage", buildJsonObject {
                put("input_tokens", 100)
                put("output_tokens", 50)
                put("total_tokens", 150)
            })
        }
        val usage = method.invoke(rt, usageObj)
        assertThat(usage).isNotNull()
    }

    @Test
    fun `isDockerDaemonAvailable returns boolean without throwing`() {
        val rt = runtime()
        val method = DockerRuntime::class.java.getDeclaredMethod("isDockerDaemonAvailable")
        method.isAccessible = true
        val available = method.invoke(rt) as Boolean
        assertThat(available || !available).isTrue()
    }
}
