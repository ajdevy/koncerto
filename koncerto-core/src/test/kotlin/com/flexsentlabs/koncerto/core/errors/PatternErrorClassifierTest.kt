package com.flexsentlabs.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class PatternErrorClassifierTest {

    private val classifier = PatternErrorClassifier()

    @Test
    fun `classify subscription limit for codex usage message`() {
        val error = classifier.classify("stdout", "You've hit your usage limit. try again at 3:51 PM")
        assertThat(error).isInstanceOf(AgentErrorType.SubscriptionLimitError::class)
    }

    @Test
    fun `classify subscription limit for claude api error`() {
        val error = classifier.classify("stderr", "API Error: Rate limit reached")
        assertThat(error).isInstanceOf(AgentErrorType.SubscriptionLimitError::class)
    }

    @Test
    fun `classify rate limit by status code`() {
        val error = classifier.classify("stderr", "HTTP 429 Too Many Requests")
        assertThat(error).isInstanceOf(AgentErrorType.RateLimitError::class)
    }

    @Test
    fun `classify rate limit by message`() {
        val error = classifier.classify("stderr", "Error: rate limit exceeded, please slow down")
        assertThat(error).isInstanceOf(AgentErrorType.RateLimitError::class)
    }

    @Test
    fun `classify rate limit by too many requests`() {
        val error = classifier.classify("stderr", "too many requests")
        assertThat(error).isInstanceOf(AgentErrorType.RateLimitError::class)
    }

    @Test
    fun `classify rate limit with retry after`() {
        val error = classifier.classify("stderr", "rate limit exceeded, retry-after: 60")
        assertThat(error).isInstanceOf(AgentErrorType.RateLimitError::class)
        val rl = error as AgentErrorType.RateLimitError
        assertThat(rl.retryAfterMs).isNotNull()
        assertThat(rl.retryAfterMs).isEqualTo(60)
    }

    @Test
    fun `classify rate limit without retry after`() {
        val error = classifier.classify("stderr", "HTTP 429 Too Many Requests")
        assertThat(error).isInstanceOf(AgentErrorType.RateLimitError::class)
        val rl = error as AgentErrorType.RateLimitError
        assertThat(rl.retryAfterMs).isNull()
    }

    @Test
    fun `classify token quota by context length`() {
        val error = classifier.classify("stdout", "This model's maximum context length is 4096 tokens")
        assertThat(error).isInstanceOf(AgentErrorType.TokenQuotaError::class)
    }

    @Test
    fun `classify token quota by quota exceeded`() {
        val error = classifier.classify("stdout", "Quota exceeded for token usage")
        assertThat(error).isInstanceOf(AgentErrorType.TokenQuotaError::class)
    }

    @Test
    fun `classify auth by invalid api key`() {
        val error = classifier.classify("stderr", "Invalid API key provided")
        assertThat(error).isInstanceOf(AgentErrorType.AuthError::class)
    }

    @Test
    fun `classify auth by unauthorized`() {
        val error = classifier.classify("stderr", "Unauthorized - check your credentials")
        assertThat(error).isInstanceOf(AgentErrorType.AuthError::class)
    }

    @Test
    fun `classify auth by 403`() {
        val error = classifier.classify("stderr", "HTTP 403 Forbidden")
        assertThat(error).isInstanceOf(AgentErrorType.AuthError::class)
    }

    @Test
    fun `classify transient by timeout`() {
        val error = classifier.classify("stderr", "Connection timed out after 30s")
        assertThat(error).isInstanceOf(AgentErrorType.TransientError::class)
    }

    @Test
    fun `classify transient by connection refused`() {
        val error = classifier.classify("stderr", "Connection refused: no route to host")
        assertThat(error).isInstanceOf(AgentErrorType.TransientError::class)
    }

    @Test
    fun `classify transient by 503`() {
        val error = classifier.classify("stderr", "HTTP 503 Service Unavailable")
        assertThat(error).isInstanceOf(AgentErrorType.TransientError::class)
    }

    @Test
    fun `classify transient by try again later`() {
        val error = classifier.classify("stderr", "Please try again later")
        assertThat(error).isInstanceOf(AgentErrorType.TransientError::class)
    }

    @Test
    fun `classify permanent by 400`() {
        val error = classifier.classify("stderr", "HTTP 400 Bad Request")
        assertThat(error).isInstanceOf(AgentErrorType.PermanentError::class)
    }

    @Test
    fun `classify permanent by not found`() {
        val error = classifier.classify("stderr", "Model 'gpt-5' not found")
        assertThat(error).isInstanceOf(AgentErrorType.PermanentError::class)
    }

    @Test
    fun `classify unknown for unrecognized error`() {
        val error = classifier.classify("stdout", "Some random output here")
        assertThat(error).isInstanceOf(AgentErrorType.UnknownError::class)
        val ue = error as AgentErrorType.UnknownError
        assertThat(ue.details).isEqualTo("Some random output here")
    }

    @Test
    fun `custom patterns override defaults`() {
        val custom = PatternErrorClassifier(
            listOf(
                PatternErrorClassifier.ClassificationPattern(
                    regex = Regex("custom_error", RegexOption.IGNORE_CASE)
                ) { _, _ -> AgentErrorType.PermanentError("custom match") }
            )
        )
        val error = custom.classify("stderr", "custom_error happened")
        assertThat(error).isInstanceOf(AgentErrorType.PermanentError::class)
        val pe = error as AgentErrorType.PermanentError
        assertThat(pe.details).isEqualTo("custom match")
    }

    @Test
    fun `classify respects source parameter`() {
        val error = classifier.classify("stderr", "HTTP 429 Too Many Requests")
        assertThat(error).isInstanceOf(AgentErrorType.RateLimitError::class)
    }

    @Test
    fun `classify 401 as auth`() {
        val error = classifier.classify("stderr", "HTTP 401 Unauthorized")
        assertThat(error).isInstanceOf(AgentErrorType.AuthError::class)
    }

    @Test
    fun `rate limit pattern matches rate_limited`() {
        val match = PatternErrorClassifier.RATE_LIMIT_PATTERN.find("rate_limited")
        assertThat(match).isNotNull()
    }

    @Test
    fun `retry after pattern matches colon format`() {
        val match = PatternErrorClassifier.RETRY_AFTER_PATTERN.find("retry-after: 120")
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("120")
    }

    @Test
    fun `retry after pattern matches space format`() {
        val match = PatternErrorClassifier.RETRY_AFTER_PATTERN.find("retry after 30")
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("30")
    }

    @Test
    fun `empty message returns unknown error`() {
        val error = classifier.classify("stdout", "")
        assertThat(error).isInstanceOf(AgentErrorType.UnknownError::class)
        val ue = error as AgentErrorType.UnknownError
        assertThat(ue.details).isEqualTo("")
    }
}
