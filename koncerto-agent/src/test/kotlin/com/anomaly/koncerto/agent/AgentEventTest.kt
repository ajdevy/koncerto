package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isNotNull
import com.anomaly.koncerto.core.config.SubtaskDef
import com.anomaly.koncerto.core.config.SubtaskManifest
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
    fun `subtask events carry correct metadata`() {
        val now = Instant.now()
        val started = AgentEvent.SubtaskStarted(subtaskId = "step-1", issueId = "KONC-123")
        assertThat(started.subtaskId).isEqualTo("step-1")
        assertThat(started.issueId).isEqualTo("KONC-123")
    }

    @Test
    fun `workplan ready event carries manifest`() {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(SubtaskDef(id = "s1", description = "Test", prompt = "Do it"))
        )
        val event = AgentEvent.WorkplanReady(manifest = manifest, issueId = "KONC-123")
        assertThat(event.manifest.subtasks.size).isEqualTo(1)
    }

    @Test
    fun `merge conflict event carries branch info`() {
        val event = AgentEvent.MergeConflict(
            subtaskId = "step-2",
            branch = "subtask/KONC-123/step-2",
            issueId = "KONC-123"
        )
        assertThat(event.branch).isEqualTo("subtask/KONC-123/step-2")
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
