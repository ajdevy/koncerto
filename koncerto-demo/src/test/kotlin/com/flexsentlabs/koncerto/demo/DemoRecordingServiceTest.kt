package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import com.flexsentlabs.koncerto.demo.report.AiTimelineGenerator
import com.flexsentlabs.koncerto.demo.report.DemoReporter
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import com.flexsentlabs.koncerto.demo.repository.DemoTaskRepository
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService
import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DemoRecordingServiceTest {

    private lateinit var taskRepository: DemoTaskRepository
    private lateinit var recorder: DemoRecorder
    private lateinit var recorderFactory: RecorderFactory
    private lateinit var storage: DemoStorage
    private lateinit var reporter: DemoReporter
    private lateinit var reportGenerator: DemoReportGenerator
    private lateinit var metrics: DemoMetricsRecorder
    private lateinit var auditLogger: DemoAuditLogger
    private lateinit var service: DemoRecordingService
    private lateinit var aiTimelineGenerator: AiTimelineGenerator

    @BeforeEach
    fun setUp() {
        taskRepository = FakeDemoTaskRepository()
        recorder = FakeRecorder2()
        recorderFactory = RecorderFactory(listOf(recorder))
        storage = FakeDemoStorage2()
        reporter = FakeDemoReporter2()
        reportGenerator = DemoReportGenerator()
        metrics = DemoMetricsRecorder()
        auditLogger = DemoAuditLogger("/tmp/koncerto-test-audit.log")
        aiTimelineGenerator = AiTimelineGenerator()

        val config = DemoConfig(
            enabled = true,
            tempDir = System.getProperty("java.io.tmpdir"),
            maxRetries = 2,
            retryDelayMs = 1,
            retentionDays = 90,
            maxRecordingsPerSpace = 100,
            defaultPlatform = "playwright"
        )

        service = DemoRecordingService(
            config = config,
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger,
            aiTimelineGenerator = aiTimelineGenerator
        )
    }

    @Test
    fun `createTask returns success and persists`() = runTest {
        val result = service.createTask(
            issueId = "issue-1", issueIdentifier = "KONC-123",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT, trigger = DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        val task = (result as DemoResult.Success).value
        assert(task.issueId == "issue-1")
        assert(task.platform == DemoPlatform.PLAYWRIGHT)
        assert(task.status == DemoStatus.PENDING)
        val found = taskRepository.findById(task.id)
        assert(found != null)
    }

    @Test
    fun `requestRecording succeeds end-to-end`() = runTest {
        val result = service.requestRecording(
            issueId = "issue-2", issueIdentifier = "KONC-456",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT, trigger = DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        val task = (result as DemoResult.Success).value
        assert(task.status == DemoStatus.COMPLETED)
        assert(task.recordingUrl != null)
        assert(task.durationMs != null)
    }

    @Test
    fun `getTask returns task by id`() = runTest {
        val createResult = service.createTask(
            issueId = "issue-3", issueIdentifier = "KONC-789",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT, trigger = DemoTrigger.MANUAL
        )
        val taskId = (createResult as DemoResult.Success).value.id
        val getResult = service.getTask(taskId)
        assert(getResult is DemoResult.Success)
        assert((getResult as DemoResult.Success).value.id == taskId)
    }

    @Test
    fun `getTask returns failure for missing task`() = runTest {
        val result = service.getTask("nonexistent")
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `getTasksByIssue returns tasks for issue`() = runTest {
        service.createTask("issue-4", "KONC-101", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        service.createTask("issue-4", "KONC-101", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        service.createTask("issue-5", "KONC-102", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        val tasks = service.getTasksByIssue("issue-4")
        assert(tasks.size == 2)
    }

    @Test
    fun `getMetrics returns snapshot after recordings`() = runTest {
        service.requestRecording("issue-6", "KONC-103", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        val snapshot = service.getMetrics()
        assert(snapshot.attemptsByPlatform.containsKey("PLAYWRIGHT"))
        assert(snapshot.storageUploadCount >= 1)
    }

    @Test
    fun `getPendingTasks returns only pending`() = runTest {
        service.createTask("issue-7", "KONC-104", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        val pending = service.getPendingTasks()
        assert(pending.all { it.status == DemoStatus.PENDING })
    }

    @Test
    fun `toggleKeep updates keep flag`() = runTest {
        val create = service.createTask("issue-8", "KONC-105", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        val taskId = (create as DemoResult.Success).value.id
        service.toggleKeep(taskId, true)
        val task = service.getTask(taskId)
        assert((task as DemoResult.Success).value.isKept)
    }

    @Test
    fun `requestRecording with AI config generates report with AI timeline`() = runTest {
        val configWithAi = DemoConfig(
            enabled = true,
            tempDir = System.getProperty("java.io.tmpdir"),
            maxRetries = 2,
            retryDelayMs = 1,
            retentionDays = 90,
            maxRecordingsPerSpace = 100,
            defaultPlatform = "playwright",
            ai = DemoConfig.AiConfig(
                timelineEnabled = true,
                reproStepsEnabled = true
            )
        )
        val serviceWithAi = DemoRecordingService(
            config = configWithAi,
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger,
            aiTimelineGenerator = AiTimelineGenerator()
        )

        val result = serviceWithAi.requestRecording(
            issueId = "issue-10", issueIdentifier = "KONC-200",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            trigger = DemoTrigger.REVIEW_PASSED
        )
        assert(result is DemoResult.Success)
        val task = (result as DemoResult.Success).value
        assert(task.status == DemoStatus.COMPLETED)
    }
}

class FakeRecorder2 : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT
    override suspend fun isAvailable(): Boolean = true
    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        outputFile.writeText("fake recording data")
        return DemoResult.Success(DemoRecorder.RecordingResult(
            file = outputFile, durationMs = 1000L, fileSizeBytes = outputFile.length(), format = config.outputFormat
        ))
    }
}

class FakeDemoStorage2 : DemoStorage {
    override suspend fun upload(taskId: String, file: File, contentType: String): DemoResult<DemoStorage.StorageResult> =
        uploadWithTags(taskId, file, contentType, emptyMap())

    override suspend fun uploadWithTags(taskId: String, file: File, contentType: String, tags: Map<String, String>): DemoResult<DemoStorage.StorageResult> {
        return DemoResult.Success(DemoStorage.StorageResult(
            storageKey = "demo-recordings/$taskId/${file.name}",
            url = "https://cdn.example.com/demo-recordings/$taskId/${file.name}",
            sizeBytes = file.length()
        ))
    }

    override suspend fun delete(storageKey: String): DemoResult<Unit> = DemoResult.Success(Unit)
    override suspend fun generateUrl(storageKey: String, expiresInSeconds: Long): DemoResult<String> =
        DemoResult.Success("https://cdn.example.com/$storageKey")
    override suspend fun checkQuota(): DemoResult<DemoStorage.QuotaInfo> =
        DemoResult.Success(DemoStorage.QuotaInfo(0L, 9L * 1024 * 1024 * 1024, 9L * 1024 * 1024 * 1024))
    override suspend fun listOldest(limit: Int): DemoResult<List<DemoStorage.StorageItem>> =
        DemoResult.Success(emptyList())
    override suspend fun deleteBatch(storageKeys: List<String>): DemoResult<Int> =
        DemoResult.Success(storageKeys.size)
}

class FakeDemoReporter2 : DemoReporter {
    var lastReportedTask: DemoTask? = null
    var lastReportedUrl: String? = null
    override suspend fun report(task: DemoTask, recordingUrl: String): DemoResult<Unit> {
        lastReportedTask = task; lastReportedUrl = recordingUrl
        return DemoResult.Success(Unit)
    }
    override suspend fun reportFailure(task: DemoTask, errorMessage: String): DemoResult<Unit> {
        lastReportedTask = task
        return DemoResult.Success(Unit)
    }
}

class FakeDemoTaskRepository : DemoTaskRepository {
    private val tasks = mutableMapOf<String, DemoTask>()

    override suspend fun save(task: DemoTask) { tasks[task.id] = task }
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
            updatedAt = now, completedAt = if (status in setOf(DemoStatus.COMPLETED, DemoStatus.FAILED, DemoStatus.PARTIAL)) now else task.completedAt
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
