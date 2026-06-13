package com.anomaly.koncerto.core.errors

import com.anomaly.koncerto.core.retry.RetryConfig

sealed class RetryDecision {
    data object NoRetry : RetryDecision()
    data class RetryWithDelay(val delayMs: Long) : RetryDecision()
    data class RetryWithBackoff(val config: RetryConfig = RetryConfig()) : RetryDecision()
}

object RetryDecisionMaker {
    fun decide(errorType: AgentErrorType, attempt: Int = 0): RetryDecision {
        return when (errorType) {
            is AgentErrorType.RateLimitError -> {
                val delayMs = if (errorType.retryAfterMs != null && errorType.retryAfterMs > 0) {
                    errorType.retryAfterMs
                } else {
                    60_000L
                }
                RetryDecision.RetryWithDelay(delayMs)
            }
            is AgentErrorType.TokenQuotaError -> RetryDecision.NoRetry
            is AgentErrorType.AuthError -> RetryDecision.NoRetry
            is AgentErrorType.TransientError -> RetryDecision.RetryWithBackoff()
            is AgentErrorType.PermanentError -> RetryDecision.NoRetry
            is AgentErrorType.UnknownError -> RetryDecision.RetryWithBackoff()
        }
    }
}
