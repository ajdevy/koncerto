package com.anomaly.koncerto.metrics

interface MetricsRepository {
    suspend fun updateAfterRun(
        issueId: String,
        issueIdentifier: String,
        projectSlug: String?,
        result: String,
        inputTokens: Long,
        outputTokens: Long,
        totalTokens: Long
    )

    suspend fun findAll(): List<IssueMetrics>

    suspend fun findByProject(projectSlug: String?): List<IssueMetrics>

    suspend fun findById(issueId: String): IssueMetrics?

    suspend fun tokenHistory(days: Int = 30): List<TokenDaySummary>
}
