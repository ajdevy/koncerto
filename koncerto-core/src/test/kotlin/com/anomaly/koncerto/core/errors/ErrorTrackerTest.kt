package com.anomaly.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ErrorTrackerTest {

    @Test
    fun `recordError increments count`() {
        val tracker = DefaultErrorTracker()
        tracker.recordError("key1", "error message", "agent")
        assertThat(tracker.getErrorCount("key1")).isEqualTo(1)
        tracker.recordError("key1", "error message", "agent")
        assertThat(tracker.getErrorCount("key1")).isEqualTo(2)
    }

    @Test
    fun `getErrorsByCategory filters correctly`() {
        val tracker = DefaultErrorTracker()
        tracker.recordError("key1", "err1", "agent")
        tracker.recordError("key2", "err2", "network")
        tracker.recordError("key3", "err3", "agent")
        val agentErrors = tracker.getErrorsByCategory("agent")
        val networkErrors = tracker.getErrorsByCategory("network")
        assertThat(agentErrors.size).isEqualTo(2)
        assertThat(networkErrors.size).isEqualTo(1)
    }

    @Test
    fun `resetCounter clears counter`() {
        val tracker = DefaultErrorTracker()
        tracker.recordError("key1", "error", "agent")
        assertThat(tracker.getErrorCount("key1")).isEqualTo(1)
        tracker.resetCounter("key1")
        assertThat(tracker.getErrorCount("key1")).isEqualTo(0)
    }

    @Test
    fun `getTopErrors returns sorted errors`() {
        val tracker = DefaultErrorTracker()
        tracker.recordError("low", "low error", "agent")
        tracker.recordError("high", "high error", "agent")
        tracker.recordError("high", "high error", "agent")
        tracker.recordError("high", "high error", "agent")
        tracker.recordError("medium", "medium error", "agent")
        tracker.recordError("medium", "medium error", "agent")
        val top = tracker.getTopErrors(2)
        assertThat(top.size).isEqualTo(2)
        assertThat(top[0].key).isEqualTo("high")
        assertThat(top[0].count).isEqualTo(3)
        assertThat(top[1].key).isEqualTo("medium")
        assertThat(top[1].count).isEqualTo(2)
    }

    @Test
    fun `getTotalErrorCount sums across all keys`() {
        val tracker = DefaultErrorTracker()
        tracker.recordError("a", "err a", "agent")
        tracker.recordError("a", "err a", "agent")
        tracker.recordError("b", "err b", "network")
        tracker.recordError("b", "err b", "network")
        tracker.recordError("b", "err b", "network")
        assertThat(tracker.getTotalErrorCount()).isEqualTo(5)
    }
}
