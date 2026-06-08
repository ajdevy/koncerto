package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.model.Issue
import java.time.Instant
import org.junit.jupiter.api.Test

class RuntimeStateTest {

    @Test
    fun `available slots is max minus running`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 3
        assertThat(s.availableSlots()).isEqualTo(3)
        s.maxConcurrentAgents = 1
        assertThat(s.availableSlots()).isEqualTo(1)
    }

    @Test
    fun `available slots never negative`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 0
        assertThat(s.availableSlots()).isEqualTo(0)
    }

    @Test
    fun `available slots reduces with running entries`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 5
        s.running["a"] = runningEntry("a", "A-1")
        s.running["b"] = runningEntry("b", "A-2")
        assertThat(s.availableSlots()).isEqualTo(3)
    }

    @Test
    fun `retryAttempts stores and retrieves entries`() {
        val s = RuntimeState()
        val entry = RetryEntry("id1", "A-1", 1, System.currentTimeMillis() + 1000, "timeout")
        s.retryAttempts["id1"] = entry
        assertThat(s.retryAttempts["id1"]?.attempt).isEqualTo(1)
        assertThat(s.retryAttempts["id1"]?.error).isEqualTo("timeout")
    }

    @Test
    fun `retryAttempts overwrites existing entry for same key`() {
        val s = RuntimeState()
        s.retryAttempts["id1"] = RetryEntry("id1", "A-1", 1, System.currentTimeMillis(), "err1")
        s.retryAttempts["id1"] = RetryEntry("id1", "A-1", 2, System.currentTimeMillis(), "err2")
        assertThat(s.retryAttempts["id1"]?.attempt).isEqualTo(2)
        assertThat(s.retryAttempts["id1"]?.error).isEqualTo("err2")
    }

    @Test
    fun `retryAttempts remove clears entry`() {
        val s = RuntimeState()
        s.retryAttempts["id1"] = RetryEntry("id1", "A-1", 1, System.currentTimeMillis(), "err")
        s.retryAttempts.remove("id1")
        assertThat(s.retryAttempts.containsKey("id1")).isEqualTo(false)
        assertThat(s.retryAttempts.size).isEqualTo(0)
    }

    @Test
    fun `completed set add and contains`() {
        val s = RuntimeState()
        s.completed.add("id1")
        s.completed.add("id2")
        assertThat(s.completed.contains("id1")).isTrue()
        assertThat(s.completed.contains("id2")).isTrue()
        assertThat(s.completed.size).isEqualTo(2)
    }

    @Test
    fun `completed set remove clears entry`() {
        val s = RuntimeState()
        s.completed.add("id1")
        s.completed.remove("id1")
        assertThat(s.completed.contains("id1")).isEqualTo(false)
        assertThat(s.completed.size).isEqualTo(0)
    }

    @Test
    fun `completed set is independent of running`() {
        val s = RuntimeState()
        s.running["id1"] = runningEntry("id1", "A-1")
        s.completed.add("id1")
        assertThat(s.running.containsKey("id1")).isTrue()
        assertThat(s.completed.contains("id1")).isTrue()
    }

    @Test
    fun `claimed set add and remove`() {
        val s = RuntimeState()
        s.claimed.add("id1")
        assertThat(s.claimed.contains("id1")).isTrue()
        s.claimed.remove("id1")
        assertThat(s.claimed.contains("id1")).isEqualTo(false)
        assertThat(s.claimed.size).isEqualTo(0)
    }

    @Test
    fun `codexTotals accumulates token counts`() {
        val s = RuntimeState()
        assertThat(s.codexTotals.inputTokens).isEqualTo(0)
        assertThat(s.codexTotals.outputTokens).isEqualTo(0)
        assertThat(s.codexTotals.totalTokens).isEqualTo(0)
        s.codexTotals = s.codexTotals.copy(
            inputTokens = s.codexTotals.inputTokens + 100,
            outputTokens = s.codexTotals.outputTokens + 50,
            totalTokens = s.codexTotals.totalTokens + 150
        )
        assertThat(s.codexTotals.inputTokens).isEqualTo(100)
        assertThat(s.codexTotals.outputTokens).isEqualTo(50)
        assertThat(s.codexTotals.totalTokens).isEqualTo(150)
    }

    @Test
    fun `multiple retry entries for different issues`() {
        val s = RuntimeState()
        s.retryAttempts["a"] = RetryEntry("a", "A-1", 1, System.currentTimeMillis(), "err_a")
        s.retryAttempts["b"] = RetryEntry("b", "B-1", 2, System.currentTimeMillis(), "err_b")
        s.retryAttempts["c"] = RetryEntry("c", "C-1", 3, System.currentTimeMillis(), "err_c")
        assertThat(s.retryAttempts.size).isEqualTo(3)
        assertThat(s.retryAttempts["a"]?.identifier).isEqualTo("A-1")
        assertThat(s.retryAttempts["b"]?.identifier).isEqualTo("B-1")
        assertThat(s.retryAttempts["c"]?.identifier).isEqualTo("C-1")
    }

    private fun runningEntry(id: String, identifier: String) = RunningEntry(
        issue = Issue(
            id = id, identifier = identifier, title = "t", description = null,
            priority = 5, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        ),
        threadId = "thread-$id",
        turnId = "turn-$id",
        startedAt = Instant.now(),
        lastCodexTimestamp = null
    )
}
