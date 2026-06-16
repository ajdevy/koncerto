package com.flexsentlabs.koncerto.core.audit

import kotlinx.serialization.Serializable

@Serializable
data class AuditEvent(
    val timestamp: Long,
    val type: AuditEventType,
    val projectSlug: String,
    val issueId: String,
    val issueIdentifier: String? = null,
    val agentKind: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val error: String? = null,
    val attempt: Int? = null
)

enum class AuditEventType {
    AGENT_DISPATCHED,
    AGENT_COMPLETED,
    AGENT_FAILED,
    AGENT_CANCELLED,
    AGENT_RETRY_SCHEDULED
}
