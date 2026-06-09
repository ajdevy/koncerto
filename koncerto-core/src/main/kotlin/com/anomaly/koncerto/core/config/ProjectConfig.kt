package com.anomaly.koncerto.core.config

@kotlinx.serialization.Serializable
data class ProjectConfig(
    val tracker: TrackerConfig,
    val workspace: WorkspaceConfig,
    val agent: AgentProjectConfig
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
    val stages: Map<String, StageAgentConfig> = emptyMap()
)
