package com.anomaly.koncerto.agent

import java.time.Instant

sealed class AgentEvent {
    abstract val timestamp: Instant
    abstract val pid: Long?

    data class SessionStarted(
        val threadId: String,
        val turnId: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class StartupFailed(
        val error: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnCompleted(
        val threadId: String,
        val turnId: String,
        val usage: TokenUsage?,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnFailed(
        val threadId: String,
        val turnId: String,
        val error: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnCancelled(
        val threadId: String,
        val turnId: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnEndedWithError(
        val threadId: String,
        val turnId: String,
        val error: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnInputRequired(
        val threadId: String,
        val turnId: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class ApprovalAutoApproved(
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class UnsupportedToolCall(
        val toolName: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class Notification(
        val method: String,
        val params: kotlinx.serialization.json.JsonElement?,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class OtherMessage(
        val method: String,
        val params: kotlinx.serialization.json.JsonElement?,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class Malformed(
        val raw: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()
}

data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)