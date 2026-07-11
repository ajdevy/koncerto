package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DemoAuditLoggerTest {

    private val tempDir = createTempDir(prefix = "demo-audit-")
    private val logFile = File(tempDir, "audit.log")
    private val zone = ZoneId.of("UTC")
    private val logger = DemoAuditLogger(logFile.absolutePath, retentionDays = 7, zoneId = zone)

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createTask(
        id: String = "task-1",
        issueId: String = "issue-1",
        issueIdentifier: String = "KONC-123",
        platform: DemoPlatform = DemoPlatform.PLAYWRIGHT,
        status: DemoStatus = DemoStatus.COMPLETED,
        trigger: DemoTrigger = DemoTrigger.MANUAL,
        retryCount: Int = 0,
        fileSizeBytes: Long? = 2048L,
        completedAt: String? = "2026-01-01T00:01:05Z"
    ): DemoTask = DemoTask(
        id = id, issueId = issueId, issueIdentifier = issueIdentifier,
        projectSlug = null, platform = platform, status = status,
        trigger = trigger, createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        completedAt = completedAt, fileSizeBytes = fileSizeBytes,
        retryCount = retryCount
    )

    @Test
    fun `logTaskCreated writes proper format`() {
        val task = createTask()
        logger.logTaskCreated(task)
        val current = datedLogFile()
        val line = current.readLines().last()
        assert(line.contains("TASK_CREATED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("issue_id=${task.issueId}"))
        assert(line.contains("platform=${task.platform.name}"))
        assert(line.contains("trigger=${task.trigger.name}"))
    }

    @Test
    fun `logRecordingStarted writes proper format`() {
        val task = createTask()
        logger.logRecordingStarted(task)
        val line = datedLogFile().readLines().last()
        assert(line.contains("RECORDING_STARTED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("platform=${task.platform.name}"))
    }

    @Test
    fun `logRecordingCompleted writes proper format`() {
        val task = createTask(fileSizeBytes = 4096L)
        logger.logRecordingCompleted(task, 3000L)
        val line = datedLogFile().readLines().last()
        assert(line.contains("RECORDING_COMPLETED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("duration_ms=3000"))
        assert(line.contains("file_size=4096"))
    }

    @Test
    fun `logUploadCompleted writes proper format`() {
        val task = createTask()
        logger.logUploadCompleted(task, "demo-recordings/task-1/video.webm")
        val line = datedLogFile().readLines().last()
        assert(line.contains("UPLOAD_COMPLETED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("storage_key=demo-recordings/task-1/video.webm"))
    }

    @Test
    fun `logReportPosted writes proper format`() {
        val task = createTask()
        logger.logReportPosted(task, "https://example.com/recording.webm")
        val line = datedLogFile().readLines().last()
        assert(line.contains("REPORT_POSTED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("url=https://example.com/recording.webm"))
    }

    @Test
    fun `logReportFailed writes proper format`() {
        val task = createTask()
        logger.logReportFailed(task, "https://example.com/recording.webm", "report_failed: 500 boom")
        val line = datedLogFile().readLines().last()
        assert(line.contains("REPORT_FAILED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("url=https://example.com/recording.webm"))
        assert(line.contains("error=report_failed: 500 boom"))
    }

    @Test
    fun `logTaskFailed writes proper format`() {
        val task = createTask(retryCount = 3)
        logger.logTaskFailed(task, "recording_failed: timeout")
        val line = datedLogFile().readLines().last()
        assert(line.contains("TASK_FAILED"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("error=recording_failed: timeout"))
        assert(line.contains("retry_count=3"))
    }

    @Test
    fun `logFallback writes proper format`() {
        val task = createTask()
        logger.logFallback(task, "PLAYWRIGHT", "ASCIINEMA")
        val line = datedLogFile().readLines().last()
        assert(line.contains("FALLBACK"))
        assert(line.contains("task_id=${task.id}"))
        assert(line.contains("from=PLAYWRIGHT"))
        assert(line.contains("to=ASCIINEMA"))
    }

    @Test
    fun `logCleanup writes proper format`() {
        logger.logCleanup(5)
        val line = datedLogFile().readLines().last()
        assert(line.contains("CLEANUP"))
        assert(line.contains("deleted_count=5"))
    }

    @Test
    fun `logQuotaCheck writes proper format`() {
        logger.logQuotaCheck(1000L, 9000L)
        val line = datedLogFile().readLines().last()
        assert(line.contains("QUOTA_CHECK"))
        assert(line.contains("used_bytes=1000"))
        assert(line.contains("available_bytes=9000"))
    }

    private fun datedLogFile(): File {
        val date = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return File(tempDir, "audit-$date.log")
    }
}
