package com.flexsentlabs.koncerto.core.errors

/**
 * Thrown when an agent hits a subscription or plan usage cap (Codex/Claude).
 * Orchestrator resolves [resumeAtMs] via [LimitResetParser] using project config.
 */
class SubscriptionLimitException(
    message: String,
    val provider: String,
    val rawMessage: String = message
) : Exception(message)
