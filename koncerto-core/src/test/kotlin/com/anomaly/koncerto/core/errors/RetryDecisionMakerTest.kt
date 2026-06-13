package com.anomaly.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.anomaly.koncerto.core.retry.RetryConfig
import org.junit.jupiter.api.Test

class RetryDecisionMakerTest {

    @Test
    fun `rate limit returns retry with fixed delay`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.RateLimitError())
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithDelay::class)
        val d = decision as RetryDecision.RetryWithDelay
        assertThat(d.delayMs).isEqualTo(60_000L)
    }

    @Test
    fun `rate limit with retryAfterMs uses custom delay`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.RateLimitError(retryAfterMs = 30_000L))
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithDelay::class)
        val d = decision as RetryDecision.RetryWithDelay
        assertThat(d.delayMs).isEqualTo(30_000L)
    }

    @Test
    fun `token quota returns no retry`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.TokenQuotaError())
        assertThat(decision).isInstanceOf(RetryDecision.NoRetry::class)
    }

    @Test
    fun `auth error returns no retry`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.AuthError())
        assertThat(decision).isInstanceOf(RetryDecision.NoRetry::class)
    }

    @Test
    fun `transient error returns retry with backoff`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.TransientError())
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithBackoff::class)
        val d = decision as RetryDecision.RetryWithBackoff
        assertThat(d.config).isInstanceOf(RetryConfig::class)
    }

    @Test
    fun `permanent error returns no retry`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.PermanentError())
        assertThat(decision).isInstanceOf(RetryDecision.NoRetry::class)
    }

    @Test
    fun `unknown error returns retry with backoff`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.UnknownError())
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithBackoff::class)
    }

    @Test
    fun `transient uses default retry config`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.TransientError()) as RetryDecision.RetryWithBackoff
        assertThat(decision.config.maxRetries).isEqualTo(3)
        assertThat(decision.config.initialDelayMs).isEqualTo(1000)
        assertThat(decision.config.maxDelayMs).isEqualTo(60_000)
        assertThat(decision.config.multiplier).isEqualTo(2.0)
    }

    @Test
    fun `rate limit with zero retryAfterMs still uses 60s default`() {
        val decision = RetryDecisionMaker.decide(AgentErrorType.RateLimitError(retryAfterMs = 0L))
        assertThat(decision).isInstanceOf(RetryDecision.RetryWithDelay::class)
        val d = decision as RetryDecision.RetryWithDelay
        assertThat(d.delayMs).isEqualTo(60_000L)
    }

}
