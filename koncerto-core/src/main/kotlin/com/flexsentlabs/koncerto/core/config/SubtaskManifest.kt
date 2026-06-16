package com.flexsentlabs.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class SubtaskManifest(
    val issueId: String,
    val subtasks: List<SubtaskDef>
)

@Serializable
data class SubtaskDef(
    val id: String,
    val description: String,
    val prompt: String,
    val dependsOn: List<String> = emptyList(),
    val fileScope: List<String> = emptyList()
)

enum class SubtaskStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED
}

data class SubtaskState(
    val def: SubtaskDef,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val branchName: String? = null,
    val runId: String? = null,
    val startedAt: java.time.Instant? = null,
    val completedAt: java.time.Instant? = null
)