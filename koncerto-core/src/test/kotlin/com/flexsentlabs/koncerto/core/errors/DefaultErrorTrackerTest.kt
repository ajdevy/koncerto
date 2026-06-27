package com.flexsentlabs.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class DefaultErrorTrackerTest {

    private val tracker = DefaultErrorTracker()

    @Test
    fun `recordError records and increments count`() {
        tracker.recordError("key1", "error msg", "network")
        assertThat(tracker.getErrorCount("key1")).isEqualTo(1)
        tracker.recordError("key1", "error msg", "network")
        assertThat(tracker.getErrorCount("key1")).isEqualTo(2)
    }

    @Test
    fun `getErrorCount returns 0 for unknown key`() {
        assertThat(tracker.getErrorCount("unknown")).isEqualTo(0)
    }

    @Test
    fun `getErrorCount returns correct count`() {
        tracker.recordError("test-key", "something failed", "io")
        assertThat(tracker.getErrorCount("test-key")).isEqualTo(1)
    }

    @Test
    fun `getErrorsByCategory filters correctly`() {
        tracker.recordError("e1", "err", "network")
        tracker.recordError("e2", "err", "io")
        tracker.recordError("e3", "err", "network")
        val networkErrors = tracker.getErrorsByCategory("network")
        assertThat(networkErrors.size).isEqualTo(2)
        val ioErrors = tracker.getErrorsByCategory("io")
        assertThat(ioErrors.size).isEqualTo(1)
    }

    @Test
    fun `getAllErrors returns all`() {
        tracker.recordError("a", "msg", "cat")
        tracker.recordError("b", "msg", "cat")
        assertThat(tracker.getAllErrors().size).isEqualTo(2)
    }

    @Test
    fun `resetCounter removes the entry`() {
        tracker.recordError("reset-me", "msg", "cat")
        assertThat(tracker.getErrorCount("reset-me")).isEqualTo(1)
        tracker.resetCounter("reset-me")
        assertThat(tracker.getErrorCount("reset-me")).isEqualTo(0)
    }

    @Test
    fun `getTotalErrorCount sums all counts`() {
        tracker.recordError("t1", "msg", "cat")
        tracker.recordError("t1", "msg", "cat")
        tracker.recordError("t2", "msg", "cat")
        assertThat(tracker.getTotalErrorCount()).isEqualTo(3)
    }

    @Test
    fun `getTopErrors returns top N by count`() {
        tracker.recordError("high", "msg", "cat")
        tracker.recordError("high", "msg", "cat")
        tracker.recordError("low", "msg", "cat")
        val top = tracker.getTopErrors(1)
        assertThat(top.size).isEqualTo(1)
        assertThat(top[0].key).isEqualTo("high")
    }

    @Test
    fun `RetryDecisionMaker returns RetryWithDelay for RateLimitError`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.RateLimitError(retryAfterMs = 30_000))
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithDelay::class)
        val rwd = decision as RetryDecision.RetryWithDelay
        assertThat(rwd.delayMs).isEqualTo(30_000)
    }

    @Test
    fun `RetryDecisionMaker returns NoRetry for TokenQuotaError`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.TokenQuotaError())
        assertThat(decision).isInstanceOf(RetryDecision.NoRetry::class)
    }

    @Test
    fun `RetryDecisionMaker returns NoRetry for AuthError`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.AuthError())
        assertThat(decision).isInstanceOf(RetryDecision.NoRetry::class)
    }

    @Test
    fun `RetryDecisionMaker returns RetryWithBackoff for TransientError`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.TransientError())
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithBackoff::class)
    }

    @Test
    fun `RetryDecisionMaker returns NoRetry for PermanentError`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.PermanentError())
        assertThat(decision).isInstanceOf(RetryDecision.NoRetry::class)
    }

    @Test
    fun `RetryDecisionMaker returns RetryWithBackoff for UnknownError`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.UnknownError())
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithBackoff::class)
    }
}
