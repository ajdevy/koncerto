package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.config.SubtaskManifest
import com.anomaly.koncerto.core.errors.AgentError
import com.anomaly.koncerto.core.model.TokenUsage
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

    data class AgentMessage(
        val messageId: String,
        val fromAgentId: String,
        val toAgentId: String,
        val payload: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class WorkplanReady(
        val manifest: SubtaskManifest,
        val issueId: String,
        override val pid: Long? = null,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class SubtaskStarted(
        val subtaskId: String,
        val issueId: String,
        override val pid: Long? = null,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class SubtaskCompleted(
        val subtaskId: String,
        val issueId: String,
        override val pid: Long? = null,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class SubtaskFailed(
        val subtaskId: String,
        val issueId: String,
        val error: String,
        override val pid: Long? = null,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class MergeConflict(
        val subtaskId: String,
        val branch: String,
        val issueId: String,
        override val pid: Long? = null,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class LimitDetected(
        val agentError: AgentError,
        val issueId: String,
        val line: String,
        override val pid: Long? = null,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()
}