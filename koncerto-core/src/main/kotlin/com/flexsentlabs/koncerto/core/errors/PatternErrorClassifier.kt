package com.flexsentlabs.koncerto.core.errors

class PatternErrorClassifier(
    private val patterns: List<ClassificationPattern> = DEFAULT_PATTERNS
) : ErrorClassifier {

    data class ClassificationPattern(
        val regex: Regex,
        val build: (match: MatchResult, message: String) -> AgentErrorType
    )

    override fun classify(source: String, message: String): AgentErrorType {
        for (pattern in patterns) {
            val match = pattern.regex.find(message) ?: continue
            return pattern.build(match, message)
        }
        return AgentErrorType.UnknownError(message)
    }

    companion object {
        val RATE_LIMIT_PATTERN = Regex("""429|rate.limit(?:ed)?|too many requests""", RegexOption.IGNORE_CASE)
        val RETRY_AFTER_PATTERN = Regex("""retry.after.?[:\s]+(\d+)""", RegexOption.IGNORE_CASE)

        val SUBSCRIPTION_USAGE_PATTERN = Regex(
            """hit your usage limit|purchase more credits|API Error:\s*Rate limit reached""",
            RegexOption.IGNORE_CASE
        )

        val DEFAULT_PATTERNS: List<ClassificationPattern> = listOf(
            ClassificationPattern(
                regex = SUBSCRIPTION_USAGE_PATTERN
            ) { match, message ->
                AgentErrorType.SubscriptionLimitError(
                    details = "Subscription limit: ${match.value}",
                    provider = inferProvider(message)
                )
            },
            ClassificationPattern(
                regex = RATE_LIMIT_PATTERN
            ) { match, message ->
                val retryAfter = RETRY_AFTER_PATTERN.find(message)?.groupValues?.get(1)?.toLongOrNull()
                AgentErrorType.RateLimitError(
                    details = "Rate limit detected: ${match.value}",
                    retryAfterMs = retryAfter
                )
            },
            ClassificationPattern(
                regex = Regex("""maximum context length|token.*limit|quota.*exceeded|insufficient.*quota""", RegexOption.IGNORE_CASE)
            ) { match, _ ->
                AgentErrorType.TokenQuotaError(details = "Token/quota limit: ${match.value}")
            },
            ClassificationPattern(
                regex = Regex("""(?:invalid|missing|bad|incorrect).*(?:api.?key|auth|token|credential)|unauthorized|403|401|authentication failed|auth.*error""", RegexOption.IGNORE_CASE)
            ) { match, _ ->
                AgentErrorType.AuthError(details = "Auth error: ${match.value}")
            },
            ClassificationPattern(
                regex = Regex("""timeout|timed? out|connection refused|connection reset|econnrefused|5\d{2}|internal server error|service unavailable|temporarily unavailable|try again later""", RegexOption.IGNORE_CASE)
            ) { match, _ ->
                AgentErrorType.TransientError(details = "Transient error: ${match.value}")
            },
            ClassificationPattern(
                regex = Regex("""4\d{2}|bad request|not found|model.*not found|invalid.*request""", RegexOption.IGNORE_CASE)
            ) { match, _ ->
                AgentErrorType.PermanentError(details = "Permanent error: ${match.value}")
            }
        )

        private fun inferProvider(message: String): String? = when {
            message.contains("codex", ignoreCase = true) -> "codex"
            message.contains("claude", ignoreCase = true) -> "claude"
            message.contains("hit your usage limit", ignoreCase = true) -> "codex"
            message.contains("API Error", ignoreCase = true) -> "claude"
            else -> null
        }
    }
}
