package com.flexsentlabs.koncerto.core.errors

data class AgentError(
    val type: AgentErrorType,
    val message: String,
    val source: String = "unknown",
    val timestamp: Long = System.currentTimeMillis()
)

sealed class AgentErrorType {
    data class RateLimitError(
        val details: String = "",
        val retryAfterMs: Long? = null
    ) : AgentErrorType()

    data class TokenQuotaError(
        val details: String = "",
        val tokensAvailable: Long? = null,
        val tokensRequested: Long? = null
    ) : AgentErrorType()

    data class SubscriptionLimitError(
        val details: String = "",
        val provider: String? = null,
        val resetAtMs: Long? = null
    ) : AgentErrorType()

    data class AuthError(
        val details: String = ""
    ) : AgentErrorType()

    data class TransientError(
        val details: String = ""
    ) : AgentErrorType()

    data class PermanentError(
        val details: String = ""
    ) : AgentErrorType()

    data class UnknownError(
        val details: String = ""
    ) : AgentErrorType()
}
