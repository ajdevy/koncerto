package com.flexsentlabs.koncerto.metrics

data class IssueMetrics(
    val issueId: String,
    val issueIdentifier: String,
    val projectSlug: String?,
    val totalRuns: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalTokens: Long,
    val lastResult: String?,
    val lastRunAt: String?,
    val createdAt: String,
    val updatedAt: String
)

data class TokenDaySummary(
    val date: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)
