package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
        s.completed["id1"] = true
        s.completed["id2"] = true
        assertThat(s.completed.containsKey("id1")).isTrue()
        assertThat(s.completed.containsKey("id2")).isTrue()
        assertThat(s.completed.size).isEqualTo(2)
    }

    @Test
    fun `completed set remove clears entry`() {
        val s = RuntimeState()
        s.completed["id1"] = true
        s.completed.remove("id1")
        assertThat(s.completed.containsKey("id1")).isEqualTo(false)
        assertThat(s.completed.size).isEqualTo(0)
    }

    @Test
    fun `completed set is independent of running`() {
        val s = RuntimeState()
        s.running["id1"] = runningEntry("id1", "A-1")
        s.completed["id1"] = true
        assertThat(s.running.containsKey("id1")).isTrue()
        assertThat(s.completed.containsKey("id1")).isTrue()
    }

    @Test
    fun `claimed set add and remove`() {
        val s = RuntimeState()
        s.claimed["id1"] = true
        assertThat(s.claimed.containsKey("id1")).isTrue()
        s.claimed.remove("id1")
        assertThat(s.claimed.containsKey("id1")).isEqualTo(false)
        assertThat(s.claimed.size).isEqualTo(0)
    }

    @Test
    fun `tokenTotals accumulates token counts`() {
        val s = RuntimeState()
        assertThat(s.tokenTotals.inputTokens).isEqualTo(0)
        assertThat(s.tokenTotals.outputTokens).isEqualTo(0)
        assertThat(s.tokenTotals.totalTokens).isEqualTo(0)
        s.tokenTotals = s.tokenTotals.copy(
            inputTokens = s.tokenTotals.inputTokens + 100,
            outputTokens = s.tokenTotals.outputTokens + 50,
            totalTokens = s.tokenTotals.totalTokens + 150
        )
        assertThat(s.tokenTotals.inputTokens).isEqualTo(100)
        assertThat(s.tokenTotals.outputTokens).isEqualTo(50)
        assertThat(s.tokenTotals.totalTokens).isEqualTo(150)
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

    @Test
    fun `RunningEntry stores all default fields`() {
        val entry = runningEntry("1", "A-1")
        assertThat(entry.threadId).isEqualTo("thread-1")
        assertThat(entry.turnId).isEqualTo("turn-1")
        assertThat(entry.inputTokens).isEqualTo(0)
        assertThat(entry.outputTokens).isEqualTo(0)
        assertThat(entry.totalTokens).isEqualTo(0)
        assertThat(entry.lastReportedInput).isEqualTo(0)
        assertThat(entry.lastReportedOutput).isEqualTo(0)
        assertThat(entry.lastReportedTotal).isEqualTo(0)
        assertThat(entry.turnCount).isEqualTo(1)
        assertThat(entry.lastCodexTimestamp).isNull()
    }

    @Test
    fun `RunningEntry with non-default token values`() {
        val entry = RunningEntry(
            issue = Issue("1", "A-1", "t", null, 5, "Todo", null, null, emptyList(), emptyList(), null, null, null),
            threadId = "t1", turnId = "u1", startedAt = Instant.now(), lastCodexTimestamp = null,
            inputTokens = 100, outputTokens = 50, totalTokens = 150,
            lastReportedInput = 50, lastReportedOutput = 25, lastReportedTotal = 75,
            turnCount = 3
        )
        assertThat(entry.inputTokens).isEqualTo(100)
        assertThat(entry.outputTokens).isEqualTo(50)
        assertThat(entry.totalTokens).isEqualTo(150)
        assertThat(entry.lastReportedInput).isEqualTo(50)
        assertThat(entry.lastReportedOutput).isEqualTo(25)
        assertThat(entry.lastReportedTotal).isEqualTo(75)
        assertThat(entry.turnCount).isEqualTo(3)
    }

    @Test
    fun `RunningEntry copy preserves fields`() {
        val entry = runningEntry("1", "A-1")
        val copied = entry.copy(turnCount = 5)
        assertThat(copied.turnCount).isEqualTo(5)
        assertThat(copied.issue.id).isEqualTo("1")
    }

    @Test
    fun `pauseAgent sets paused flag`() {
        val s = RuntimeState()
        s.running["a"] = runningEntry("a", "A-1")
        val result = s.pauseAgent("a")
        assertThat(result).isTrue()
        assertThat(s.running["a"]?.paused).isEqualTo(true)
    }

    @Test
    fun `resumeAgent clears paused flag`() {
        val s = RuntimeState()
        s.running["a"] = runningEntry("a", "A-1").copy(paused = true)
        val result = s.resumeAgent("a")
        assertThat(result).isTrue()
        assertThat(s.running["a"]?.paused).isEqualTo(false)
    }

    @Test
    fun `cancelAgent removes entry`() {
        val s = RuntimeState()
        s.running["a"] = runningEntry("a", "A-1")
        s.claimed["a"] = true
        val result = s.cancelAgent("a")
        assertThat(result).isTrue()
        assertThat(s.running.containsKey("a")).isFalse()
        assertThat(s.claimed.containsKey("a")).isFalse()
    }

    @Test
    fun `cancelAgent removes output`() {
        val s = RuntimeState()
        s.running["a"] = runningEntry("a", "A-1")
        s.appendOutput("a", "line1")
        assertThat(s.outputFlow("a")).isNotNull()
        s.cancelAgent("a")
        assertThat(s.outputFlow("a")).isNull()
    }

    @Test
    fun `pauseAgent returns false for unknown id`() {
        val s = RuntimeState()
        assertThat(s.pauseAgent("nonexistent")).isFalse()
    }

    @Test
    fun `resumeAgent returns false for unknown id`() {
        val s = RuntimeState()
        assertThat(s.resumeAgent("nonexistent")).isFalse()
    }

    @Test
    fun `cancelAgent returns false for unknown id`() {
        val s = RuntimeState()
        assertThat(s.cancelAgent("nonexistent")).isFalse()
    }

    @Test
    fun `RunningEntry defaults paused and cancelled to false`() {
        val entry = runningEntry("1", "A-1")
        assertThat(entry.paused).isFalse()
        assertThat(entry.cancelled).isFalse()
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
