package com.flexsentlabs.koncerto.demo.repository

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteDemoTaskRepository(dbPath: String) : DemoTaskRepository {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val lock = ReentrantLock()

    init {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS demo_tasks (
                    id TEXT PRIMARY KEY,
                    issue_id TEXT NOT NULL,
                    issue_identifier TEXT NOT NULL,
                    project_slug TEXT,
                    platform TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    recording_url TEXT,
                    storage_key TEXT,
                    duration_ms INTEGER,
                    file_size_bytes INTEGER,
                    error_message TEXT,
                    retry_count INTEGER DEFAULT 0,
                    trigger TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    completed_at TEXT,
                    is_kept INTEGER DEFAULT 0,
                    metadata TEXT,
                    html_report_key TEXT,
                    fallback_from TEXT
                )
                """.trimIndent()
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_demo_tasks_issue_id ON demo_tasks(issue_id)"
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_demo_tasks_status ON demo_tasks(status)"
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_demo_tasks_created_at ON demo_tasks(created_at)"
            )
        }
    }

    override suspend fun save(task: DemoTask) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO demo_tasks
                    (id, issue_id, issue_identifier, project_slug, platform, status,
                     recording_url, storage_key, duration_ms, file_size_bytes,
                     error_message, retry_count, trigger, created_at, updated_at,
                     completed_at, is_kept, metadata, html_report_key, fallback_from)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, task.id)
                stmt.setString(2, task.issueId)
                stmt.setString(3, task.issueIdentifier)
                stmt.setString(4, task.projectSlug)
                stmt.setString(5, task.platform.name)
                stmt.setString(6, task.status.name)
                stmt.setString(7, task.recordingUrl)
                stmt.setString(8, task.storageKey)
                task.durationMs?.let { stmt.setLong(9, it) } ?: stmt.setNull(9, java.sql.Types.INTEGER)
                task.fileSizeBytes?.let { stmt.setLong(10, it) } ?: stmt.setNull(10, java.sql.Types.INTEGER)
                stmt.setString(11, task.errorMessage)
                stmt.setInt(12, task.retryCount)
                stmt.setString(13, task.trigger.name)
                stmt.setString(14, task.createdAt)
                stmt.setString(15, task.updatedAt)
                stmt.setString(16, task.completedAt)
                stmt.setInt(17, if (task.isKept) 1 else 0)
                stmt.setString(18, task.metadata)
                stmt.setString(19, task.htmlReportKey)
                stmt.setString(20, task.fallbackFrom)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun findById(taskId: String): DemoTask? = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement("SELECT * FROM demo_tasks WHERE id = ?").use { stmt ->
                stmt.setString(1, taskId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toDemoTask() else null }
            }
        }
    }

    override suspend fun findByIssue(issueId: String): List<DemoTask> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM demo_tasks WHERE issue_id = ? ORDER BY created_at DESC"
            ).use { stmt ->
                stmt.setString(1, issueId)
                stmt.executeQuery().use { rs -> rs.toDemoTaskList() }
            }
        }
    }

    override suspend fun findAll(): List<DemoTask> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT * FROM demo_tasks ORDER BY created_at DESC").use { rs ->
                    rs.toDemoTaskList()
                }
            }
        }
    }

    override suspend fun findPending(): List<DemoTask> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM demo_tasks WHERE status = 'PENDING' ORDER BY created_at ASC"
            ).use { stmt ->
                stmt.executeQuery().use { rs -> rs.toDemoTaskList() }
            }
        }
    }

    override suspend fun findByStatus(status: DemoStatus): List<DemoTask> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM demo_tasks WHERE status = ? ORDER BY created_at DESC"
            ).use { stmt ->
                stmt.setString(1, status.name)
                stmt.executeQuery().use { rs -> rs.toDemoTaskList() }
            }
        }
    }

    override suspend fun updateStatus(taskId: String, status: DemoStatus, errorMessage: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = Instant.now().toString()
            connection.prepareStatement(
                """
                UPDATE demo_tasks SET status = ?, error_message = ?, updated_at = ?,
                    completed_at = CASE WHEN ? IN ('COMPLETED', 'FAILED', 'PARTIAL') THEN ? ELSE completed_at END
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, status.name)
                stmt.setString(2, errorMessage)
                stmt.setString(3, now)
                stmt.setString(4, status.name)
                stmt.setString(5, now)
                stmt.setString(6, taskId)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun updateCompleted(
        taskId: String, status: DemoStatus, recordingUrl: String?, storageKey: String?,
        durationMs: Long?, fileSizeBytes: Long?
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = Instant.now().toString()
            connection.prepareStatement(
                """
                UPDATE demo_tasks SET status = ?, recording_url = ?, storage_key = ?,
                    duration_ms = ?, file_size_bytes = ?, completed_at = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, status.name)
                stmt.setString(2, recordingUrl)
                stmt.setString(3, storageKey)
                stmt.setString(4, durationMs?.toString())
                stmt.setString(5, fileSizeBytes?.toString())
                stmt.setString(6, now)
                stmt.setString(7, now)
                stmt.setString(8, taskId)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun deleteOlderThan(timestamp: String, limit: Int): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "DELETE FROM demo_tasks WHERE rowid IN (SELECT rowid FROM demo_tasks WHERE created_at < ? AND status IN ('COMPLETED', 'FAILED', 'PARTIAL') AND is_kept = 0 LIMIT ?)"
            ).use { stmt ->
                stmt.setString(1, timestamp)
                stmt.setInt(2, limit)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun countByStatus(status: DemoStatus): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement("SELECT COUNT(*) FROM demo_tasks WHERE status = ?").use { stmt ->
                stmt.setString(1, status.name)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override suspend fun sumFileSizes(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COALESCE(SUM(file_size_bytes), 0) FROM demo_tasks").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    override suspend fun updateKeepFlag(taskId: String, isKept: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "UPDATE demo_tasks SET is_kept = ?, updated_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setInt(1, if (isKept) 1 else 0)
                stmt.setString(2, Instant.now().toString())
                stmt.setString(3, taskId)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun findOlderThan(timestamp: String): List<DemoTask> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM demo_tasks WHERE created_at < ? AND is_kept = 0 ORDER BY created_at ASC"
            ).use { stmt ->
                stmt.setString(1, timestamp)
                stmt.executeQuery().use { rs -> rs.toDemoTaskList() }
            }
        }
    }

    override suspend fun updateHtmlReportKey(taskId: String, htmlReportKey: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "UPDATE demo_tasks SET html_report_key = ?, updated_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, htmlReportKey)
                stmt.setString(2, Instant.now().toString())
                stmt.setString(3, taskId)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun updateFallbackFrom(taskId: String, fallbackFrom: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "UPDATE demo_tasks SET fallback_from = ?, updated_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, fallbackFrom)
                stmt.setString(2, Instant.now().toString())
                stmt.setString(3, taskId)
                stmt.executeUpdate()
            }
        }
        Unit
    }

    private fun ResultSet.toDemoTask() = DemoTask(
        id = getString("id"),
        issueId = getString("issue_id"),
        issueIdentifier = getString("issue_identifier"),
        projectSlug = getString("project_slug"),
        platform = DemoPlatform.valueOf(getString("platform")),
        status = DemoStatus.valueOf(getString("status")),
        recordingUrl = getString("recording_url"),
        storageKey = getString("storage_key"),
        durationMs = getLong("duration_ms"),
        fileSizeBytes = getLong("file_size_bytes"),
        errorMessage = getString("error_message"),
        retryCount = getInt("retry_count"),
        trigger = DemoTrigger.valueOf(getString("trigger")),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        completedAt = getString("completed_at"),
        isKept = getInt("is_kept") == 1,
        metadata = getString("metadata"),
        htmlReportKey = getString("html_report_key"),
        fallbackFrom = getString("fallback_from")
    )

    private fun ResultSet.toDemoTaskList(): List<DemoTask> {
        val results = mutableListOf<DemoTask>()
        while (next()) results.add(toDemoTask())
        return results
    }
}
