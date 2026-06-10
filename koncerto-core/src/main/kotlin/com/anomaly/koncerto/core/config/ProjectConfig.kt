package com.anomaly.koncerto.core.config

@kotlinx.serialization.Serializable
data class ProjectConfig(
    val tracker: TrackerConfig,
    val workspace: WorkspaceConfig,
    val agent: AgentProjectConfig,
    val rateLimiter: RateLimiterConfig? = null,
    val circuitBreaker: CircuitBreakerConfig? = null,
    val notifications: NotificationsConfig = NotificationsConfig(onCompleted = false, onFailed = false, onStalled = false, onClarification = false)
)

@kotlinx.serialization.Serializable
data class TrackerConfig(
    val kind: String,
    val endpoint: String,
    val apiKey: String,
    val projectSlug: String,
    val requiredLabels: List<String> = emptyList(),
    val activeStates: List<String> = listOf("Todo", "In Progress"),
    val terminalStates: List<String> = listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done"),
    val blockedState: String = "Blocked",
    val projectAdmin: String? = null
)

@kotlinx.serialization.Serializable
data class WorkspaceConfig(val root: String)

@kotlinx.serialization.Serializable
data class RoutingRule(
    val ifLabel: String? = null,
    val ifLabelPrefix: String? = null,
    val ifState: String? = null,
    val ifPriority: Int? = null,
    val ifPriorityMax: Int? = null,
    val useAgent: String,
    val priority: Int = 0
)

@kotlinx.serialization.Serializable
data class AgentProjectConfig(
    val kind: String = "opencode",
    val command: String? = null,
    val maxConcurrentAgents: Int = 2,
    val maxTurns: Int = 20,
    val maxRetryBackoffMs: Long = 300000,
    val maxConcurrentAgentsByState: Map<String, Int> = emptyMap(),
    val turnTimeoutMs: Long = 3600000,
    val readTimeoutMs: Long = 5000,
    val stallTimeoutMs: Long = 300000,
    val heartbeatIntervalMs: Long = 30_000L,
    val heartbeatTimeoutMs: Long = 90_000L,
    val stages: Map<String, StageAgentConfig> = emptyMap(),
    val agents: Map<String, AgentProviderConfig> = emptyMap(),
    val routingRules: List<RoutingRule> = emptyList()
)

@kotlinx.serialization.Serializable
data class RateLimiterConfig(
    val requestsPerSecond: Int = 10,
    val maxBurst: Int = 20
)

@kotlinx.serialization.Serializable
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val resetTimeoutMs: Long = 30_000
)

@kotlinx.serialization.Serializable
data class NotificationsConfig(
    val onCompleted: Boolean = true,
    val onFailed: Boolean = true,
    val onStalled: Boolean = true,
    val onClarification: Boolean = true,
    val telegram: TelegramConfig? = null,
    val email: EmailConfig? = null,
    val webhook: WebhookConfig? = null
)

@kotlinx.serialization.Serializable
data class TelegramConfig(
    val botToken: String,
    val chatId: String
)

@kotlinx.serialization.Serializable
data class EmailConfig(
    val smtpHost: String,
    val smtpPort: Int = 587,
    val username: String?,
    val password: String?,
    val from: String,
    val to: String
)

@kotlinx.serialization.Serializable
data class WebhookConfig(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)
