package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.integration.DemoEventListener
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import com.flexsentlabs.koncerto.demo.report.DemoReporter
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import com.flexsentlabs.koncerto.demo.repository.DemoTaskRepository
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService
import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.assertThrows

class DemoEventListenerTest {

    private lateinit var service: DemoRecordingService
    private lateinit var listener: DemoEventListener
    private lateinit var spyTaskRepo: SpyDemoTaskRepository

    @BeforeEach
    fun setUp() {
        spyTaskRepo = SpyDemoTaskRepository()
        val recorder = FakeRecorder2()
        val recorderFactory = RecorderFactory(listOf(recorder))
        val storage = FakeDemoStorage2()
        val reporter = FakeDemoReporter2()
        val reportGenerator = DemoReportGenerator()
        val metrics = DemoMetricsRecorder()
        val auditLogger = DemoAuditLogger("/tmp/koncerto-test-audit.log")

        val config = DemoConfig(
            enabled = true,
            tempDir = System.getProperty("java.io.tmpdir"),
            targetUrl = "http://localhost:3000",
            maxRetries = 2,
            retryDelayMs = 1,
            retentionDays = 90,
            maxRecordingsPerSpace = 100,
            defaultPlatform = "playwright"
        )

        service = DemoRecordingService(
            config = config,
            taskRepository = spyTaskRepo,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )

        listener = DemoEventListener(recordingService = service, enabled = true)
    }

    @Test
    fun `onReviewPassed triggers recording with REVIEW_PASSED trigger`() = runTest {
        val result = listener.onReviewPassed(
            issueId = "issue-1",
            issueIdentifier = "KONC-123",
            projectSlug = "test"
        )
        assertNotNull(result)
        assert(result!!.startsWith("http"))
    }

    @Test
    fun `onRecordDemoAction triggers recording with MANUAL trigger`() = runTest {
        val result = listener.onRecordDemoAction(
            issueId = "issue-2",
            issueIdentifier = "KONC-456",
            projectSlug = "test",
            platform = DemoPlatform.PLAYWRIGHT
        )
        assert(result is DemoResult.Success<*>)
        val task = (result as DemoResult.Success<*>).value as DemoTask
        assert(task.issueId == "issue-2")
        assert(task.trigger == DemoTrigger.MANUAL)
        assert(task.platform == DemoPlatform.PLAYWRIGHT)
    }

    @Test
    fun `onReviewPassed returns null when disabled`() = runTest {
        val disabledListener = DemoEventListener(recordingService = service, enabled = false)
        val result = disabledListener.onReviewPassed(
            issueId = "issue-3",
            issueIdentifier = "KONC-789",
            projectSlug = "test"
        )
        assert(result == null)
    }

    @Test
    fun `onRecordDemoAction returns success when disabled`() = runTest {
        val disabledListener = DemoEventListener(recordingService = service, enabled = false)
        val result = disabledListener.onRecordDemoAction(
            issueId = "issue-4",
            issueIdentifier = "KONC-101",
            projectSlug = "test"
        )
        assert(result is DemoResult.Success<*>)
        assert((result as DemoResult.Success<*>).value == Unit)
    }

    @Test
    fun `onReviewPassed throws when recording fails`() = runTest {
        val failingService = DemoRecordingService(
            config = DemoConfig(
                enabled = true,
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                defaultPlatform = "playwright"
            ),
            taskRepository = spyTaskRepo,
            recorderFactory = RecorderFactory(emptyList()),
            storage = FakeDemoStorage2(),
            reporter = FakeDemoReporter2(),
            reportGenerator = DemoReportGenerator(),
            metrics = DemoMetricsRecorder(),
            auditLogger = DemoAuditLogger("/tmp/koncerto-test-audit.log")
        )
        val failingListener = DemoEventListener(recordingService = failingService, enabled = true)

        val error = assertThrows<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                failingListener.onReviewPassed("issue-5", "KONC-FAIL", "test")
            }
        }

        assert(error.message?.isNotBlank() == true)
    }
}

class SpyDemoTaskRepository : DemoTaskRepository {
    val savedTasks = mutableListOf<DemoTask>()
    private val tasks = mutableMapOf<String, DemoTask>()

    override suspend fun save(task: DemoTask) {
        tasks[task.id] = task
        savedTasks.add(task)
    }
    override suspend fun findById(taskId: String): DemoTask? = tasks[taskId]
    override suspend fun findByIssue(issueId: String): List<DemoTask> =
        tasks.values.filter { it.issueId == issueId }.sortedByDescending { it.createdAt }
    override suspend fun findAll(): List<DemoTask> = tasks.values.toList().sortedByDescending { it.createdAt }
    override suspend fun findPending(): List<DemoTask> =
        tasks.values.filter { it.status == DemoStatus.PENDING }.sortedBy { it.createdAt }

    override suspend fun findByStatus(status: DemoStatus): List<DemoTask> =
        tasks.values.filter { it.status == status }.sortedByDescending { it.createdAt }

    override suspend fun updateStatus(taskId: String, status: DemoStatus, errorMessage: String?) {
        val task = tasks[taskId] ?: return
        val now = Instant.now().toString()
        tasks[taskId] = task.copy(
            status = status, errorMessage = errorMessage ?: task.errorMessage,
            updatedAt = now,
            completedAt = if (status in setOf(DemoStatus.COMPLETED, DemoStatus.FAILED, DemoStatus.PARTIAL)) now else task.completedAt
        )
    }

    override suspend fun updateCompleted(taskId: String, status: DemoStatus, recordingUrl: String?, storageKey: String?, durationMs: Long?, fileSizeBytes: Long?) {
        val task = tasks[taskId] ?: return
        val now = Instant.now().toString()
        tasks[taskId] = task.copy(
            status = status, recordingUrl = recordingUrl, storageKey = storageKey,
            durationMs = durationMs, fileSizeBytes = fileSizeBytes, completedAt = now, updatedAt = now
        )
    }

    override suspend fun deleteOlderThan(timestamp: String, limit: Int): Int {
        val toDelete = tasks.filter { (_, t) -> t.createdAt < timestamp && t.status in setOf(DemoStatus.COMPLETED, DemoStatus.FAILED, DemoStatus.PARTIAL) }.entries.take(limit)
        toDelete.forEach { tasks.remove(it.key) }
        return toDelete.size
    }

    override suspend fun countByStatus(status: DemoStatus): Int =
        tasks.values.count { it.status == status }

    override suspend fun sumFileSizes(): Long =
        tasks.values.sumOf { it.fileSizeBytes ?: 0L }

    override suspend fun updateKeepFlag(taskId: String, isKept: Boolean) {
        val task = tasks[taskId] ?: return
        tasks[taskId] = task.copy(isKept = isKept, updatedAt = Instant.now().toString())
    }

    override suspend fun findOlderThan(timestamp: String): List<DemoTask> =
        tasks.values.filter { it.createdAt < timestamp && !it.isKept }.sortedBy { it.createdAt }

    override suspend fun updateHtmlReportKey(taskId: String, htmlReportKey: String) {
        val task = tasks[taskId] ?: return
        tasks[taskId] = task.copy(htmlReportKey = htmlReportKey, updatedAt = Instant.now().toString())
    }

    override suspend fun updateFallbackFrom(taskId: String, fallbackFrom: String) {
        val task = tasks[taskId] ?: return
        tasks[taskId] = task.copy(fallbackFrom = fallbackFrom, updatedAt = Instant.now().toString())
    }
}
