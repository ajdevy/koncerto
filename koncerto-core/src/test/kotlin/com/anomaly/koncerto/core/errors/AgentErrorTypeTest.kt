package com.anomaly.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class AgentErrorTypeTest {

    @Test
    fun `rate limit error stores details`() {
        val err = AgentErrorType.RateLimitError(details = "too fast", retryAfterMs = 30_000)
        assertThat(err.details).isEqualTo("too fast")
        assertThat(err.retryAfterMs).isEqualTo(30_000)
    }

    @Test
    fun `rate limit error defaults`() {
        val err = AgentErrorType.RateLimitError()
        assertThat(err.details).isEqualTo("")
        assertThat(err.retryAfterMs).isNull()
    }

    @Test
    fun `token quota error stores details`() {
        val err = AgentErrorType.TokenQuotaError(details = "out of tokens", tokensAvailable = 100, tokensRequested = 500)
        assertThat(err.details).isEqualTo("out of tokens")
        assertThat(err.tokensAvailable).isEqualTo(100)
        assertThat(err.tokensRequested).isEqualTo(500)
    }

    @Test
    fun `auth error stores details`() {
        val err = AgentErrorType.AuthError(details = "bad key")
        assertThat(err.details).isEqualTo("bad key")
    }

    @Test
    fun `transient error stores details`() {
        val err = AgentErrorType.TransientError(details = "timeout")
        assertThat(err.details).isEqualTo("timeout")
    }

    @Test
    fun `permanent error stores details`() {
        val err = AgentErrorType.PermanentError(details = "invalid model")
        assertThat(err.details).isEqualTo("invalid model")
    }

    @Test
    fun `unknown error stores details`() {
        val err = AgentErrorType.UnknownError(details = "weird stuff")
        assertThat(err.details).isEqualTo("weird stuff")
    }

    @Test
    fun `agent error wraps type and message`() {
        val ae = AgentError(
            type = AgentErrorType.RateLimitError(details = "429"),
            message = "HTTP 429 Too Many Requests",
            source = "stderr"
        )
        assertThat(ae.type).isInstanceOf(AgentErrorType.RateLimitError::class)
        assertThat(ae.message).isEqualTo("HTTP 429 Too Many Requests")
        assertThat(ae.source).isEqualTo("stderr")
    }

    @Test
    fun `agent error defaults`() {
        val ae = AgentError(
            type = AgentErrorType.UnknownError(),
            message = "something happened"
        )
        assertThat(ae.source).isEqualTo("unknown")
        assertThat(ae.timestamp).isNotNull()
    }

    @Test
    fun `error subtypes are distinct`() {
        val rate = AgentErrorType.RateLimitError()
        val token = AgentErrorType.TokenQuotaError()
        val auth = AgentErrorType.AuthError()
        val transient = AgentErrorType.TransientError()
        val permanent = AgentErrorType.PermanentError()
        val unknown = AgentErrorType.UnknownError()
        assertThat(rate::class).isNotEqualTo(token::class)
        assertThat(token::class).isNotEqualTo(auth::class)
        assertThat(auth::class).isNotEqualTo(transient::class)
        assertThat(transient::class).isNotEqualTo(permanent::class)
        assertThat(permanent::class).isNotEqualTo(unknown::class)
    }
}
