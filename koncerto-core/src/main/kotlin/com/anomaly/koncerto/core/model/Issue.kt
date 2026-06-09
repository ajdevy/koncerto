package com.anomaly.koncerto.core.model

import java.time.Instant

data class Issue(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String?,
    val priority: Int?,
    val state: String,
    val branchName: String?,
    val url: String?,
    val labels: List<String>,
    val blockedBy: List<BlockerRef>,
    val creator: UserRef? = null,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    val normalizedState: String get() = state.lowercase()
}

data class BlockerRef(
    val id: String?,
    val identifier: String?,
    val state: String?
)

data class UserRef(
    val id: String,
    val displayName: String,
    val isBot: Boolean
)
