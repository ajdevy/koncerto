package com.anomaly.koncerto.metrics

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteMetricsRepository(dbPath: String) : MetricsRepository {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val lock = ReentrantLock()

    init {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS issue_metrics (
                    issue_id TEXT PRIMARY KEY,
                    issue_identifier TEXT NOT NULL,
                    project_slug TEXT,
                    total_runs INTEGER DEFAULT 0,
                    total_input_tokens INTEGER DEFAULT 0,
                    total_output_tokens INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    last_result TEXT,
                    last_run_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    override suspend fun updateAfterRun(
        issueId: String,
        issueIdentifier: String,
        projectSlug: String?,
        result: String,
        inputTokens: Long,
        outputTokens: Long,
        totalTokens: Long
    ) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val now = Instant.now().toString()
                val existing = findExisting(issueId)
                if (existing != null) {
                    connection.prepareStatement(
                        """
                        UPDATE issue_metrics SET
                            issue_identifier = ?,
                            project_slug = ?,
                            total_runs = total_runs + 1,
                            total_input_tokens = total_input_tokens + ?,
                            total_output_tokens = total_output_tokens + ?,
                            total_tokens = total_tokens + ?,
                            last_result = ?,
                            last_run_at = ?,
                            updated_at = ?
                        WHERE issue_id = ?
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setString(1, issueIdentifier)
                        stmt.setString(2, projectSlug)
                        stmt.setLong(3, inputTokens)
                        stmt.setLong(4, outputTokens)
                        stmt.setLong(5, totalTokens)
                        stmt.setString(6, result)
                        stmt.setString(7, now)
                        stmt.setString(8, now)
                        stmt.setString(9, issueId)
                        stmt.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        """
                        INSERT INTO issue_metrics
                            (issue_id, issue_identifier, project_slug, total_runs,
                             total_input_tokens, total_output_tokens, total_tokens,
                             last_result, last_run_at, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setString(1, issueId)
                        stmt.setString(2, issueIdentifier)
                        stmt.setString(3, projectSlug)
                        stmt.setLong(4, inputTokens)
                        stmt.setLong(5, outputTokens)
                        stmt.setLong(6, totalTokens)
                        stmt.setString(7, result)
                        stmt.setString(8, now)
                        stmt.setString(9, now)
                        stmt.setString(10, now)
                        stmt.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun findAll(): List<IssueMetrics> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM issue_metrics ORDER BY updated_at DESC"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<IssueMetrics>()
                    while (rs.next()) result.add(rs.toIssueMetrics())
                    result
                }
            }
        }
    }

    override suspend fun findByProject(projectSlug: String?): List<IssueMetrics> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (projectSlug == null) {
                connection.prepareStatement(
                    "SELECT * FROM issue_metrics WHERE project_slug IS NULL ORDER BY updated_at DESC"
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        val result = mutableListOf<IssueMetrics>()
                        while (rs.next()) result.add(rs.toIssueMetrics())
                        result
                    }
                }
            } else {
                connection.prepareStatement(
                    "SELECT * FROM issue_metrics WHERE project_slug = ? ORDER BY updated_at DESC"
                ).use { stmt ->
                    stmt.setString(1, projectSlug)
                    stmt.executeQuery().use { rs ->
                        val result = mutableListOf<IssueMetrics>()
                        while (rs.next()) result.add(rs.toIssueMetrics())
                        result
                    }
                }
            }
        }
    }

    override suspend fun findById(issueId: String): IssueMetrics? = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM issue_metrics WHERE issue_id = ?"
            ).use { stmt ->
                stmt.setString(1, issueId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toIssueMetrics() else null
                }
            }
        }
    }

    override suspend fun tokenHistory(days: Int): List<TokenDaySummary> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                """
                SELECT
                    SUBSTR(last_run_at, 1, 10) AS date,
                    SUM(total_input_tokens) AS input_tokens,
                    SUM(total_output_tokens) AS output_tokens,
                    SUM(total_tokens) AS total_tokens
                FROM issue_metrics
                WHERE last_run_at IS NOT NULL
                  AND last_run_at >= datetime('now', '-' || ? || ' days')
                GROUP BY date
                ORDER BY date ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, days)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<TokenDaySummary>()
                    while (rs.next()) result.add(
                        TokenDaySummary(
                            date = rs.getString("date"),
                            inputTokens = rs.getLong("input_tokens"),
                            outputTokens = rs.getLong("output_tokens"),
                            totalTokens = rs.getLong("total_tokens")
                        )
                    )
                    result
                }
            }
        }
    }

    private fun findExisting(issueId: String): IssueMetrics? {
        return connection.prepareStatement(
            "SELECT * FROM issue_metrics WHERE issue_id = ?"
        ).use { stmt ->
            stmt.setString(1, issueId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toIssueMetrics() else null
            }
        }
    }

    private fun ResultSet.toIssueMetrics() = IssueMetrics(
        issueId = getString("issue_id"),
        issueIdentifier = getString("issue_identifier"),
        projectSlug = getString("project_slug"),
        totalRuns = getInt("total_runs"),
        totalInputTokens = getLong("total_input_tokens"),
        totalOutputTokens = getLong("total_output_tokens"),
        totalTokens = getLong("total_tokens"),
        lastResult = getString("last_result"),
        lastRunAt = getString("last_run_at"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at")
    )
}
