package com.anomaly.koncerto.notifications

import com.anomaly.koncerto.agent.TokenUsage

sealed class NotificationEvent {
    abstract val projectSlug: String
    abstract val issueId: String
    abstract val issueIdentifier: String
    abstract val title: String

    data class AgentCompleted(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val tokenUsage: TokenUsage?
    ) : NotificationEvent()

    data class AgentFailed(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val error: String
    ) : NotificationEvent()

    data class AgentStalled(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val stallDurationMs: Long
    ) : NotificationEvent()

    data class ClarificationRequested(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String
    ) : NotificationEvent()

    data class LimitDetected(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val errorType: String,
        val summary: String,
        val retryAfterMs: Long?
    ) : NotificationEvent()
}
