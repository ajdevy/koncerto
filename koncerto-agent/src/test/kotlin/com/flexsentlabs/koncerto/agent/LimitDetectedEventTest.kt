package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.errors.AgentError
import com.flexsentlabs.koncerto.core.errors.AgentErrorType
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test

class LimitDetectedEventTest {

    @Test
    fun `limit detected event stores agent error`() {
        val agentError = AgentError(
            type = AgentErrorType.RateLimitError(details = "429", retryAfterMs = 60_000),
            message = "HTTP 429 Too Many Requests",
            source = "stderr"
        )
        val event = AgentEvent.LimitDetected(
            agentError = agentError,
            issueId = "ABC-1",
            line = "[stderr] HTTP 429 Too Many Requests"
        )
        assertThat(event.agentError.type).isInstanceOf(AgentErrorType.RateLimitError::class)
        assertThat(event.issueId).isEqualTo("ABC-1")
        assertThat(event.line).isEqualTo("[stderr] HTTP 429 Too Many Requests")
    }

    @Test
    fun `limit detected event defaults timestamp`() {
        val event = AgentEvent.LimitDetected(
            agentError = AgentError(
                type = AgentErrorType.RateLimitError(),
                message = "rate limit"
            ),
            issueId = "ABC-1",
            line = "rate limit"
        )
        assertThat(event.timestamp).isNotNull()
    }

    @Test
    fun `limit detected event defaults pid to null`() {
        val event = AgentEvent.LimitDetected(
            agentError = AgentError(
                type = AgentErrorType.RateLimitError(),
                message = "rate limit"
            ),
            issueId = "ABC-1",
            line = "rate limit"
        )
        assertThat(event.pid).isNull()
    }

    @Test
    fun `limit detected can be emitted through shared flow tryEmit`() {
        val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
        val event = AgentEvent.LimitDetected(
            agentError = AgentError(
                type = AgentErrorType.TokenQuotaError(details = "max tokens"),
                message = "maximum context length exceeded"
            ),
            issueId = "ABC-1",
            line = "[stderr] maximum context length exceeded"
        )
        val emitted = flow.tryEmit(event)
        assertThat(emitted).isTrue()
    }

    @Test
    fun `classifier detects rate limit from stderr line`() {
        val classifier = PatternErrorClassifier()
        val msg = "HTTP 429 Too Many Requests"
        val classified = classifier.classify("stderr", msg)
        assertThat(classified).isInstanceOf(AgentErrorType.RateLimitError::class)
    }

    @Test
    fun `classifier detects auth error from stderr line`() {
        val classifier = PatternErrorClassifier()
        val classified = classifier.classify("stderr", "Invalid API key provided")
        assertThat(classified).isInstanceOf(AgentErrorType.AuthError::class)
    }

    @Test
    fun `classifier skips normal stderr output`() {
        val classifier = PatternErrorClassifier()
        val classified = classifier.classify("stderr", "Some debug info here")
        assertThat(classified).isInstanceOf(AgentErrorType.UnknownError::class)
    }

    @Test
    fun `classifier detects token quota from stderr line`() {
        val classifier = PatternErrorClassifier()
        val classified = classifier.classify("stderr", "maximum context length exceeded")
        assertThat(classified).isInstanceOf(AgentErrorType.TokenQuotaError::class)
    }

    @Test
    fun `classifier detects transient from stderr line`() {
        val classifier = PatternErrorClassifier()
        val classified = classifier.classify("stderr", "Connection refused")
        assertThat(classified).isInstanceOf(AgentErrorType.TransientError::class)
    }

    @Test
    fun `classifier detects permanent from stderr line`() {
        val classifier = PatternErrorClassifier()
        val classified = classifier.classify("stderr", "HTTP 400 Bad Request")
        assertThat(classified).isInstanceOf(AgentErrorType.PermanentError::class)
    }
}
