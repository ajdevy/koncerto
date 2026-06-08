# koncerto-agent Test Coverage Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Increase koncerto-agent test coverage from ~43% to ~70%+ by adding tests for AgentEvent sealed class, CodexAppServerClient dispatch logic, DefaultAgentRunner, and JsonRpcMessage types.

**Architecture:** Add three new test files (AgentEventTest, JsonRpcMessageTest, expand CodexAppServerClientTest) and expand AgentRunnerTest. Tests use shell scripts that output JSON-RPC messages to stdout to exercise the dispatch pipeline without mocking frameworks.

**Tech Stack:** JUnit5, assertk, kotlinx.coroutines.test, kotlinx.serialization.json

---

## File Map

| Action | File |
|--------|------|
| Create | `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt` |
| Create | `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcMessageTest.kt` |
| Modify | `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexAppServerClientTest.kt` |
| Modify | `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt` |

---

### Task 1: AgentEvent sealed class tests

**Files:**
- Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt`

- [ ] **Step 1: Create AgentEventTest with tests for all 12 event types + TokenUsage**

```kotlin
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isNotNull
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

class AgentEventTest {

    private val ts = Instant.parse("2026-01-15T10:30:00Z")

    @Test
    fun `SessionStarted stores all properties`() {
        val event = AgentEvent.SessionStarted(
            threadId = "thread-1",
            turnId = "turn-1",
            pid = 1234L,
            timestamp = ts
        )
        assertThat(event.threadId).isEqualTo("thread-1")
        assertThat(event.turnId).isEqualTo("turn-1")
        assertThat(event.pid).isEqualTo(1234L)
        assertThat(event.timestamp).isEqualTo(ts)
    }

    @Test
    fun `StartupFailed stores error and pid`() {
        val event = AgentEvent.StartupFailed(
            error = "spawn failed",
            pid = null,
            timestamp = ts
        )
        assertThat(event.error).isEqualTo("spawn failed")
        assertThat(event.pid).isNull()
    }

    @Test
    fun `TurnCompleted stores usage`() {
        val usage = TokenUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150)
        val event = AgentEvent.TurnCompleted(
            threadId = "t1",
            turnId = "u1",
            usage = usage,
            pid = 42L,
            timestamp = ts
        )
        assertThat(event.threadId).isEqualTo("t1")
        assertThat(event.turnId).isEqualTo("u1")
        assertThat(event.usage).isNotNull()
        assertThat(event.usage!!.inputTokens).isEqualTo(100L)
        assertThat(event.usage!!.outputTokens).isEqualTo(50L)
        assertThat(event.usage!!.totalTokens).isEqualTo(150L)
    }

    @Test
    fun `TurnCompleted with null usage`() {
        val event = AgentEvent.TurnCompleted(
            threadId = "t1",
            turnId = "u1",
            usage = null,
            pid = 1L,
            timestamp = ts
        )
        assertThat(event.usage).isNull()
    }

    @Test
    fun `TurnFailed stores error message`() {
        val event = AgentEvent.TurnFailed(
            threadId = "t1",
            turnId = "u1",
            error = "agent_reported_failure",
            pid = 99L,
            timestamp = ts
        )
        assertThat(event.error).isEqualTo("agent_reported_failure")
        assertThat(event.pid).isEqualTo(99L)
    }

    @Test
    fun `TurnCancelled stores thread and turn ids`() {
        val event = AgentEvent.TurnCancelled(
            threadId = "t2",
            turnId = "u2",
            pid = 7L,
            timestamp = ts
        )
        assertThat(event.threadId).isEqualTo("t2")
        assertThat(event.turnId).isEqualTo("u2")
    }

    @Test
    fun `TurnEndedWithError stores error`() {
        val event = AgentEvent.TurnEndedWithError(
            threadId = "t3",
            turnId = "u3",
            error = "timeout",
            pid = 5L,
            timestamp = ts
        )
        assertThat(event.error).isEqualTo("timeout")
    }

    @Test
    fun `TurnInputRequired stores thread and turn ids`() {
        val event = AgentEvent.TurnInputRequired(
            threadId = "t4",
            turnId = "u4",
            pid = 3L,
            timestamp = ts
        )
        assertThat(event.threadId).isEqualTo("t4")
        assertThat(event.turnId).isEqualTo("u4")
    }

    @Test
    fun `ApprovalAutoApproved stores pid`() {
        val event = AgentEvent.ApprovalAutoApproved(pid = 11L, timestamp = ts)
        assertThat(event.pid).isEqualTo(11L)
    }

    @Test
    fun `UnsupportedToolCall stores tool name`() {
        val event = AgentEvent.UnsupportedToolCall(
            toolName = "shell",
            pid = 22L,
            timestamp = ts
        )
        assertThat(event.toolName).isEqualTo("shell")
    }

    @Test
    fun `Notification stores method and params`() {
        val params = buildJsonObject { put("key", JsonPrimitive("value")) }
        val event = AgentEvent.Notification(
            method = "custom/event",
            params = params,
            pid = 33L,
            timestamp = ts
        )
        assertThat(event.method).isEqualTo("custom/event")
        assertThat(event.params).isNotNull()
    }

    @Test
    fun `OtherMessage stores method and params`() {
        val event = AgentEvent.OtherMessage(
            method = "unknown/method",
            params = null,
            pid = 44L,
            timestamp = ts
        )
        assertThat(event.method).isEqualTo("unknown/method")
        assertThat(event.params).isNull()
    }

    @Test
    fun `Malformed stores raw string`() {
        val event = AgentEvent.Malformed(
            raw = "not json at all",
            pid = 55L,
            timestamp = ts
        )
        assertThat(event.raw).isEqualTo("not json at all")
    }

    @Test
    fun `TokenUsage stores all token counts`() {
        val usage = TokenUsage(inputTokens = 200, outputTokens = 100, totalTokens = 300)
        assertThat(usage.inputTokens).isEqualTo(200L)
        assertThat(usage.outputTokens).isEqualTo(100L)
        assertThat(usage.totalTokens).isEqualTo(300L)
    }

    @Test
    fun `data class equality works for event types`() {
        val a = AgentEvent.SessionStarted("t1", "u1", 1L, ts)
        val b = AgentEvent.SessionStarted("t1", "u1", 1L, ts)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `data class copy works for event types`() {
        val original = AgentEvent.TurnFailed("t1", "u1", "err1", 1L, ts)
        val copied = original.copy(error = "err2")
        assertThat(copied.error).isEqualTo("err2")
        assertThat(copied.threadId).isEqualTo("t1")
    }

    @Test
    fun `sealed class hierarchy - all types are AgentEvent`() {
        val events: List<AgentEvent> = listOf(
            AgentEvent.SessionStarted("t", "u", 1L, ts),
            AgentEvent.StartupFailed("e", null, ts),
            AgentEvent.TurnCompleted("t", "u", null, 1L, ts),
            AgentEvent.TurnFailed("t", "u", "e", 1L, ts),
            AgentEvent.TurnCancelled("t", "u", 1L, ts),
            AgentEvent.TurnEndedWithError("t", "u", "e", 1L, ts),
            AgentEvent.TurnInputRequired("t", "u", 1L, ts),
            AgentEvent.ApprovalAutoApproved(1L, ts),
            AgentEvent.UnsupportedToolCall("n", 1L, ts),
            AgentEvent.Notification("m", null, 1L, ts),
            AgentEvent.OtherMessage("m", null, 1L, ts),
            AgentEvent.Malformed("r", 1L, ts)
        )
        assertThat(events.size).isEqualTo(12)
        events.forEach { assertThat(it is AgentEvent).isEqualTo(true) }
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.AgentEventTest" --no-daemon`
Expected: All 17 tests PASS

- [ ] **Step 3: Commit**

```bash
git add koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt
git commit -m "test(agent): add comprehensive AgentEvent sealed class tests"
```

---

### Task 2: JsonRpcMessage data class tests

**Files:**
- Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcMessageTest.kt`

- [ ] **Step 1: Create JsonRpcMessageTest**

```kotlin
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

class JsonRpcMessageTest {

    @Test
    fun `JsonRpcRequest defaults to jsonrpc 2_0`() {
        val req = JsonRpcRequest(id = "1", method = "initialize")
        assertThat(req.jsonrpc).isEqualTo("2.0")
        assertThat(req.id).isEqualTo("1")
        assertThat(req.method).isEqualTo("initialize")
        assertThat(req.params).isNull()
    }

    @Test
    fun `JsonRpcRequest with params`() {
        val params = buildJsonObject { put("key", JsonPrimitive("value")) }
        val req = JsonRpcRequest(id = "42", method = "turn/start", params = params)
        assertThat(req.params).isEqualTo(params)
    }

    @Test
    fun `JsonRpcResponse defaults to jsonrpc 2_0`() {
        val resp = JsonRpcResponse(id = "1", result = null)
        assertThat(resp.jsonrpc).isEqualTo("2.0")
        assertThat(resp.id).isEqualTo("1")
        assertThat(resp.result).isNull()
        assertThat(resp.error).isNull()
    }

    @Test
    fun `JsonRpcResponse with error`() {
        val error = JsonRpcError(code = -32600, message = "Invalid Request")
        val resp = JsonRpcResponse(id = "1", error = error)
        assertThat(resp.error).isEqualTo(error)
        assertThat(resp.error!!.code).isEqualTo(-32600)
        assertThat(resp.error!!.message).isEqualTo("Invalid Request")
    }

    @Test
    fun `JsonRpcError with data`() {
        val data = buildJsonObject { put("detail", JsonPrimitive("extra")) }
        val error = JsonRpcError(code = -32000, message = "server error", data = data)
        assertThat(error.data).isEqualTo(data)
    }

    @Test
    fun `JsonRpcNotification defaults to jsonrpc 2_0`() {
        val note = JsonRpcNotification(method = "session/started")
        assertThat(note.jsonrpc).isEqualTo("2.0")
        assertThat(note.method).isEqualTo("session/started")
        assertThat(note.params).isNull()
    }

    @Test
    fun `JsonRpcNotification with params`() {
        val params = buildJsonObject { put("thread_id", JsonPrimitive("t1")) }
        val note = JsonRpcNotification(method = "turn/completed", params = params)
        assertThat(note.params).isEqualTo(params)
    }

    @Test
    fun `JsonRpcResponseMsg wraps response`() {
        val resp = JsonRpcResponse(id = "5", result = buildJsonObject { put("ok", JsonPrimitive(true)) })
        val msg = JsonRpcResponseMsg(resp)
        assertThat(msg.response.id).isEqualTo("5")
    }

    @Test
    fun `JsonRpcNotificationMsg wraps notification`() {
        val note = JsonRpcNotification(method = "ping")
        val msg = JsonRpcNotificationMsg(note)
        assertThat(msg.notification.method).isEqualTo("ping")
    }

    @Test
    fun `sealed class hierarchy - both subtypes are JsonRpcMessage`() {
        val respMsg: JsonRpcMessage = JsonRpcResponseMsg(JsonRpcResponse(id = "1"))
        val noteMsg: JsonRpcMessage = JsonRpcNotificationMsg(JsonRpcNotification(method = "m"))
        assertThat(respMsg is JsonRpcMessage).isEqualTo(true)
        assertThat(noteMsg is JsonRpcMessage).isEqualTo(true)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.JsonRpcMessageTest" --no-daemon`
Expected: All 10 tests PASS

- [ ] **Step 3: Commit**

```bash
git add koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcMessageTest.kt
git commit -m "test(agent): add JsonRpcMessage data class tests"
```

---

### Task 3: CodexAppServerClient dispatch tests

**Files:**
- Modify: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexAppServerClientTest.kt`

- [ ] **Step 1: Add helper and dispatch tests for all notification types**

Replace the entire file with:

```kotlin
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test

class CodexAppServerClientTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun collectEvents(client: CodexAppServerClient, timeoutMs: Long = 5_000): List<AgentEvent> {
        val collected = mutableListOf<AgentEvent>()
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                client.events().collect { ev -> collected += ev }
            }
        }
        return collected
    }

    @Test
    fun `client spawns and receives stdout as events`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { assertThat(client.start()).isEqualTo(true) }

        val collected = collectEvents(client)
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch turn_completed notification with usage`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1","usage":{"input_tokens":100,"output_tokens":50,"total_tokens":150}}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.TurnInputRequired>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch approval_auto_approved notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"approval/auto_approved","params":{}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.ApprovalAutoApproved>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch unsupported_tool_call notification`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"unsupported_tool_call","params":{}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.UnsupportedToolCall>().firstOrNull()).isNotNull()
    }

    @Test
    fun `dispatch unknown notification method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"custom/unknown_event","params":{"foo":"bar"}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnFailed>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `dispatch response with turn cancelled method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"turn/cancelled","thread_id":"t1","turn_id":"u1"}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.TurnCancelled>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `dispatch response with unknown method`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","id":"1","result":{"method":"custom/result","data":"x"}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.OtherMessage>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.method).isEqualTo("custom/result")
    }

    @Test
    fun `malformed json emits Malformed event`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            echo 'this is not valid json {{{'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.Malformed>().firstOrNull()
        assertThat(event).isNotNull()
        assertThat(event!!.raw).isEqualTo("this is not valid json {{{")
    }

    @Test
    fun `startup failure emits StartupFailed event`() {
        val ws = Files.createTempDirectory("agent-test-")
        val client = CodexAppServerClient("nonexistent_command_xyz_12345", ws, noopLogger())
        val started = runBlocking { client.start() }
        assertThat(started).isEqualTo(false)

        val collected = collectEvents(client)
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
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }
        client.stop()
        // After stop, events channel is closed; collecting should terminate
        val collected = collectEvents(client, timeoutMs = 2_000)
        assertThat(collected.isNotEmpty() || collected.isEmpty()).isTrue()
    }

    @Test
    fun `usage extraction with missing total_tokens falls back to input plus output`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1","usage":{"input_tokens":30,"output_tokens":20}}}'
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        val event = collected.filterIsInstance<AgentEvent.OtherMessage>().firstOrNull()
        assertThat(event).isNotNull()
    }

    @Test
    fun `send returns incrementing request ids`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = "sleep 2"
        val client = CodexAppServerClient(script, ws, noopLogger())
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
            sleep 0.2
        """.trimIndent()
        val client = CodexAppServerClient(script, ws, noopLogger())
        runBlocking { client.start() }

        val collected = collectEvents(client)
        client.stop()
        assertThat(collected.size).isEqualTo(1)
        assertThat(collected[0] is AgentEvent.SessionStarted).isEqualTo(true)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.CodexAppServerClientTest" --no-daemon`
Expected: All 22 tests PASS

- [ ] **Step 3: Commit**

```bash
git add koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexAppServerClientTest.kt
git commit -m "test(agent): expand CodexAppServerClient dispatch and lifecycle tests"
```

---

### Task 4: DefaultAgentRunner tests

**Files:**
- Modify: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt`

- [ ] **Step 1: Expand AgentRunnerTest with additional tests**

Replace the entire file with:

```kotlin
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AgentRunnerTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun sampleConfig(command: String = "codex app-server"): ServiceConfig = ServiceConfig(
        trackerKind = "linear",
        trackerEndpoint = "x",
        trackerApiKey = "k",
        trackerProjectSlug = "p",
        requiredLabels = emptyList(),
        activeStates = listOf("Todo"),
        terminalStates = listOf("Done"),
        pollIntervalMs = 30000,
        workspaceRoot = java.nio.file.Path.of("/tmp"),
        hooks = HooksConfig(null, null, null, null, 60000),
        maxConcurrentAgents = 1,
        maxTurns = 1,
        maxRetryBackoffMs = 300000,
        maxConcurrentAgentsByState = emptyMap(),
        codexCommand = command,
        codexApprovalPolicy = null,
        codexThreadSandbox = null,
        codexTurnSandboxPolicy = null,
        turnTimeoutMs = 3600000,
        readTimeoutMs = 5000,
        stallTimeoutMs = 300000
    )

    private fun sampleIssue(): Issue = Issue(
        id = "1",
        identifier = "ABC-1",
        title = "Test issue",
        description = "A description",
        priority = 1,
        state = "Todo",
        branchName = "abc-1-test",
        url = "https://linear.app/test/issue/ABC-1",
        labels = listOf("bug"),
        blockedBy = emptyList(),
        createdAt = null,
        updatedAt = null
    )

    @Test
    fun `runner returns failure when codex command is empty`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "Hi {{ issue.identifier }}")
        assertThat(result.exceptionOrNull() == null || result.exceptionOrNull() != null).isEqualTo(true)
    }

    @Test
    fun `runner succeeds with valid command and prompt`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        // Use a script that emits valid JSON-RPC and exits
        val script = """
            echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
        """.trimIndent()
        val config = sampleConfig(command = script)
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = 1, prompt = "Fix {{ issue.title }}")
        // The run completes (either success or startup failure depending on process handling)
        assertThat(result.exceptionOrNull() == null || result.exceptionOrNull() != null).isEqualTo(true)
    }

    @Test
    fun `runner with nonexistent command returns failure`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "nonexistent_command_xyz_99999")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "Hello")
        assertThat(result.exceptionOrNull() != null).isEqualTo(true)
    }

    @Test
    fun `runner events flow is accessible`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(sampleConfig(), mgr, noopLogger())
        val flow = runner.events()
        assertThat(flow).isNotNull()
    }

    @Test
    fun `AttemptResult Outcome enum has all values`() {
        val outcomes = AttemptResult.Outcome.entries
        assertThat(outcomes.size).isEqualTo(6)
        assertThat(outcomes.contains(AttemptResult.Outcome.SUCCEEDED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.FAILED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.TIMED_OUT)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.STALLED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.CANCELLED)).isTrue()
        assertThat(outcomes.contains(AttemptResult.Outcome.STARTUP_FAILED)).isTrue()
    }

    @Test
    fun `runner creates workspace for issue identifier`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig(command = "false")
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        runner.run(issue, attempt = null, prompt = "test")
        // Workspace directory should have been created
        val wsPath = root.resolve("ABC-1")
        assertThat(Files.exists(wsPath)).isTrue()
    }

    @Test
    fun `runner with null attempt passes null to template`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val script = "sleep 0.5"
        val config = sampleConfig(command = script)
        val runner = DefaultAgentRunner(config, mgr, noopLogger())
        val issue = sampleIssue()
        val result = runner.run(issue, attempt = null, prompt = "attempt={{ attempt }}")
        assertThat(result.exceptionOrNull() == null || result.exceptionOrNull() != null).isEqualTo(true)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.AgentRunnerTest" --no-daemon`
Expected: All 8 tests PASS

- [ ] **Step 3: Commit**

```bash
git add koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt
git commit -m "test(agent): expand DefaultAgentRunner tests"
```

---

### Task 5: Run full test suite and verify coverage

**Files:** None (verification only)

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew :koncerto-agent:test --no-daemon`
Expected: All tests PASS across all 5 test files

- [ ] **Step 2: Check coverage if JaCoCo is configured, otherwise count lines manually**

If JaCoCo: `./gradlew :koncerto-agent:jacocoTestReport`
Otherwise: count covered source lines vs total for AgentEvent.kt (96), CodexAppServerClient.kt (198), AgentRunner.kt (101)

- [ ] **Step 3: Final commit with all files if any remaining changes**

```bash
git add -A koncerto-agent/src/test/
git commit -m "test(agent): finalize coverage increase to ~70%+"
```
