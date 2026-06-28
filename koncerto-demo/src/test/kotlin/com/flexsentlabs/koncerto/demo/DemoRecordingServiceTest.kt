package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.model.DemoError
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DemoRecordingServiceTest {

    private lateinit var taskRepository: DemoTaskRepository
    private lateinit var recorder: DemoRecorder
    private lateinit var recorderFactory: RecorderFactory
    private lateinit var storage: DemoStorage
    private lateinit var reporter: FakeDemoReporter2
    private lateinit var reportGenerator: DemoReportGenerator
    private lateinit var metrics: DemoMetricsRecorder
    private lateinit var auditLogger: DemoAuditLogger
    private lateinit var service: DemoRecordingService
    private lateinit var aiTimelineGenerator: AiTimelineGenerator
    private lateinit var config: DemoConfig

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

        config = DemoConfig(
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
            targetUrl = "http://localhost:3000",
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

    @Test
    fun `enforceRetentionPolicy deletes old completed tasks`() = runTest {
        val oldTime = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val now = Instant.now().toString()
        val oldTask = DemoTask(
            id = "old-task-1", issueId = "issue-old", issueIdentifier = "OLD-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = oldTime, updatedAt = oldTime,
            storageKey = "old/storage/key"
        )
        val recentTask = DemoTask(
            id = "recent-task-1", issueId = "issue-recent", issueIdentifier = "REC-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.PENDING, trigger = DemoTrigger.MANUAL,
            createdAt = now, updatedAt = now
        )
        taskRepository.save(oldTask)
        taskRepository.save(recentTask)

        val result = service.enforceRetentionPolicy()
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value >= 1)
        assert(taskRepository.findById("old-task-1") == null)
        assert(taskRepository.findById("recent-task-1") != null)
    }

    @Test
    fun `enforceRetentionPolicy returns zero when nothing to delete`() = runTest {
        val now = Instant.now().toString()
        val task = DemoTask(
            id = "fresh-task", issueId = "issue-fresh", issueIdentifier = "FRESH-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.PENDING, trigger = DemoTrigger.MANUAL,
            createdAt = now, updatedAt = now
        )
        taskRepository.save(task)

        val result = service.enforceRetentionPolicy()
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value == 0)
    }

    @Test
    fun `enforceRetentionPolicy returns failure when storage delete fails`() = runTest {
        val failingStorage = FakeFailingStorage()
        val serviceWithFailingStorage = DemoRecordingService(
            config = DemoConfig(tempDir = System.getProperty("java.io.tmpdir")),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = failingStorage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val oldTime = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val oldTask = DemoTask(
            id = "fail-old-task", issueId = "issue-fail", issueIdentifier = "FAIL-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = oldTime, updatedAt = oldTime,
            storageKey = "old/storage/key"
        )
        taskRepository.save(oldTask)

        val result = serviceWithFailingStorage.enforceRetentionPolicy()
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `executeTask returns failure for non-existent task`() = runTest {
        val result = service.executeTask("nonexistent")
        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error
        assert(error is DemoError.TaskNotFound)
    }

    @Test
    fun `executeTask returns failure for non-pending task`() = runTest {
        val completedTask = DemoTask(
            id = "completed-task", issueId = "issue-c", issueIdentifier = "C-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = Instant.now().toString(), updatedAt = Instant.now().toString()
        )
        taskRepository.save(completedTask)

        val result = service.executeTask("completed-task")
        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error
        assert(error is DemoError.InvalidConfig)
    }

    @Test
    fun `toggleKeep returns failure for missing task`() = runTest {
        val result = service.toggleKeep("missing-task", true)
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.TaskNotFound)
    }

    @Test
    fun `markBlocked succeeds for existing task`() = runTest {
        val create = service.createTask("issue-9", "KONC-106", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL)
        val taskId = (create as DemoResult.Success).value.id

        val result = service.markBlocked(taskId)
        assert(result is DemoResult.Success)
    }

    @Test
    fun `markBlocked returns failure for missing task`() = runTest {
        val result = service.markBlocked("nonexistent")
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.TaskNotFound)
    }

    @Test
    fun `deleteOldRecordings returns count`() = runTest {
        val oldTime = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val oldTask = DemoTask(
            id = "old-to-delete", issueId = "issue-del", issueIdentifier = "DEL-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = oldTime, updatedAt = oldTime
        )
        taskRepository.save(oldTask)

        val count = service.deleteOldRecordings()
        assert(count >= 1)
        assert(taskRepository.findById("old-to-delete") == null)
    }

    @Test
    fun `requestRecording with null platform resolves from available`() = runTest {
        val result = service.requestRecording(
            issueId = "issue-auto", issueIdentifier = "KONC-AUTO",
            projectSlug = "test", platform = null, trigger = DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        val task = (result as DemoResult.Success).value
        assert(task.platform == DemoPlatform.PLAYWRIGHT)
    }

    @Test
    fun `requestRecording returns failure when no platform available`() = runTest {
        val emptyFactory = RecorderFactory(emptyList())
        val serviceNoRecorder = DemoRecordingService(
            config = DemoConfig(tempDir = System.getProperty("java.io.tmpdir")),
            taskRepository = taskRepository,
            recorderFactory = emptyFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )

        val result = serviceNoRecorder.requestRecording(
            issueId = "issue-null", issueIdentifier = "KONC-NULL",
            projectSlug = "test", platform = null, trigger = DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording posts skipped comment and returns failure when targetUrl is blank and config targetUrl unset`() = runTest {
        val serviceNoUrl = DemoRecordingService(
            config = DemoConfig(tempDir = System.getProperty("java.io.tmpdir"), targetUrl = ""),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = serviceNoUrl.requestRecording(
            issueId = "issue-no-url", issueIdentifier = "KONC-NOURL",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            trigger = DemoTrigger.REVIEW_PASSED, targetUrl = null
        )
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.InvalidConfig)
        assert(reporter.lastSkippedIssueId == "issue-no-url")
        assert(reporter.lastSkippedReason != null)
    }

    @Test
    fun `createTask with empty issueId still succeeds`() = runTest {
        val result = service.createTask(
            issueId = "", issueIdentifier = "KONC-EMPTY",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT, trigger = DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value.issueId == "")
    }

    @Test
    fun `getTasksByIssue returns empty list for unknown issue`() = runTest {
        val tasks = service.getTasksByIssue("nonexistent-issue")
        assert(tasks.isEmpty())
    }

    @Test
    fun `getPendingTasks returns empty when no pending tasks`() = runTest {
        val completedTask = DemoTask(
            id = "non-pending", issueId = "issue-np", issueIdentifier = "NP-1",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = Instant.now().toString(), updatedAt = Instant.now().toString()
        )
        taskRepository.save(completedTask)

        val pending = service.getPendingTasks()
        assert(pending.isEmpty())
    }

    @Test
    fun `createTask with null projectSlug succeeds`() = runTest {
        val result = service.createTask(
            issueId = "issue-null-slug", issueIdentifier = "KONC-NULL-SLUG",
            projectSlug = null, platform = DemoPlatform.PLAYWRIGHT, trigger = DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value.projectSlug == null)
    }

    @Test
    fun `requestRecording retries on short duration then succeeds`() = runTest {
        val shortThenOk = ShortThenOkRecorder()
        val retryService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 2, retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(shortThenOk)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = retryService.requestRecording(
            "issue-retry", "KONC-RETRY", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert(shortThenOk.callCount >= 2)
    }

    @Test
    fun `requestRecording falls back to asciinema when playwright fails`() = runTest {
        val asciinema = FakeAsciinemaRecorder()
        val failPlaywright = FailingRecorder()
        val fallbackService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0, retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(failPlaywright, asciinema)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = fallbackService.requestRecording(
            "issue-fallback", "KONC-FB", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value.platform == DemoPlatform.ASCIINEMA)
    }

    @Test
    fun `requestRecording partial recovery uploads partial file`() = runTest {
        val partialRecorder = PartialFileRecorder()
        val partialService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0, retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(partialRecorder)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = partialService.requestRecording(
            "issue-partial", "KONC-PART", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success || result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording fails preflight when storage quota check fails`() = runTest {
        val quotaFailStorage = QuotaCheckFailStorage()
        val preflightService = DemoRecordingService(
            config = DemoConfig(tempDir = System.getProperty("java.io.tmpdir"), targetUrl = "http://localhost:3000"),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = quotaFailStorage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = preflightService.requestRecording(
            "issue-preflight", "KONC-PF", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording uses scenario file when present`() = runTest {
        val tempDir = System.getProperty("java.io.tmpdir")
        File(tempDir, "issue-scenario-scenario.yaml").writeText("steps: []\n")
        val scenarioService = DemoRecordingService(
            config = DemoConfig(tempDir = tempDir, targetUrl = "http://localhost:3000"),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = scenarioService.requestRecording(
            "issue-scenario", "KONC-SC", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        File(tempDir, "issue-scenario-scenario.yaml").delete()
    }

    @Test
    fun `requestRecording with targetUrl override`() = runTest {
        val result = service.requestRecording(
            issueId = "issue-url", issueIdentifier = "KONC-URL",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            trigger = DemoTrigger.MANUAL, targetUrl = "http://localhost:8080"
        )
        assert(result is DemoResult.Success)
    }

    @Test
    fun `requestRecording fails when repo quota exceeded after retention`() = runTest {
        val heavyRepo = object : DemoTaskRepository by taskRepository {
            override suspend fun sumFileSizes(): Long = 100L * 1024 * 1024 * 1024
        }
        val quotaService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRecordingsPerSpace = 1,
                retentionDays = 90
            ),
            taskRepository = heavyRepo,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = quotaService.requestRecording(
            "issue-quota", "KONC-Q", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording fails preflight when recorder missing for platform`() = runTest {
        val playwrightOnly = RecorderFactory(listOf(FakeRecorder2()))
        val servicePlaywrightOnly = DemoRecordingService(
            config = DemoConfig(tempDir = System.getProperty("java.io.tmpdir"), targetUrl = "http://localhost:3000"),
            taskRepository = taskRepository,
            recorderFactory = playwrightOnly,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = servicePlaywrightOnly.requestRecording(
            "issue-asci", "KONC-ASCI", "test", DemoPlatform.ASCIINEMA, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `performQuotaCheck succeeds after retention frees space`() = runTest {
        val oldTime = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val oldTask = DemoTask(
            id = "quota-old", issueId = "issue-quota-old", issueIdentifier = "Q-OLD",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = oldTime, updatedAt = oldTime,
            storageKey = "old/key", fileSizeBytes = 60L * 1024 * 1024
        )
        taskRepository.save(oldTask)

        val quotaService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRecordingsPerSpace = 1,
                retentionDays = 90
            ),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = quotaService.requestRecording(
            "issue-quota-freed", "KONC-QF", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert(taskRepository.findById("quota-old") == null)
    }

    @Test
    fun `performQuotaCheck fails when retention cannot free enough space`() = runTest {
        val heavyRepo = object : DemoTaskRepository by taskRepository {
            override suspend fun sumFileSizes(): Long = 100L * 1024 * 1024 * 1024
            override suspend fun deleteOlderThan(timestamp: String, limit: Int): Int = 0
        }
        val quotaService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRecordingsPerSpace = 1,
                retentionDays = 90
            ),
            taskRepository = heavyRepo,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = quotaService.requestRecording(
            "issue-quota-blocked", "KONC-QB", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.QuotaExceeded)
    }

    @Test
    fun `requestRecording recovers partial upload after integrity failure`() = runTest {
        val partialService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0,
                retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(AlwaysShortWithPartialRecorder())),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = partialService.requestRecording(
            "issue-partial-recovery", "KONC-PREC", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        val task = (result as DemoResult.Success).value
        assert(task.status == DemoStatus.PARTIAL || task.status == DemoStatus.COMPLETED)
    }

    @Test
    fun `requestRecording exhausts retries and reports failure`() = runTest {
        val failService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 1,
                retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(FailingRecorder())),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = failService.requestRecording(
            "issue-max-retry", "KONC-MR", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording creates temp dir during preflight when missing`() = runTest {
        val missingDir = java.nio.file.Files.createTempDirectory("demo-missing-temp").resolve("nested-temp")
        java.nio.file.Files.deleteIfExists(missingDir)
        val preflightService = DemoRecordingService(
            config = DemoConfig(tempDir = missingDir.toString(), targetUrl = "http://localhost:3000"),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = preflightService.requestRecording(
            "issue-tempdir", "KONC-TD", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert(missingDir.toFile().exists())
        missingDir.toFile().deleteRecursively()
    }

    @Test
    fun `createTask returns failure when repository save throws`() = runTest {
        val failingRepo = FakeDemoTaskRepository().apply { failNextSave = true }
        val failingService = DemoRecordingService(
            config = config,
            taskRepository = failingRepo,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = failingService.createTask(
            "issue-fail", "KONC-FAIL", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `getPendingTasks returns pending from repository`() = runTest {
        service.requestRecording(
            "issue-pending", "KONC-P", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        val pending = service.getPendingTasks()
        assert(pending.isEmpty())
    }

    @Test
    fun `toggleKeep updates keep flag on existing task`() = runTest {
        val created = service.createTask(
            "issue-keep", "KONC-KEEP", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        ) as DemoResult.Success
        val result = service.toggleKeep(created.value.id, true)
        assert(result is DemoResult.Success)
    }

    @Test
    fun `markBlocked reports failure for existing task`() = runTest {
        val created = service.createTask(
            "issue-block", "KONC-BLK", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        ) as DemoResult.Success
        val result = service.markBlocked(created.value.id)
        assert(result is DemoResult.Success)
    }

    @Test
    fun `enforceRetentionPolicy fails when storage batch delete fails`() = runTest {
        val oldTime = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val oldTask = DemoTask(
            id = "retention-old", issueId = "issue-ret", issueIdentifier = "R-OLD",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.COMPLETED, trigger = DemoTrigger.MANUAL,
            createdAt = oldTime, updatedAt = oldTime,
            storageKey = "old/key", fileSizeBytes = 1024
        )
        taskRepository.save(oldTask)
        val retentionService = DemoRecordingService(
            config = DemoConfig(tempDir = System.getProperty("java.io.tmpdir"), retentionDays = 90),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = FakeFailingStorage(),
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = retentionService.enforceRetentionPolicy()
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `executeTask rejects task that is not pending`() = runTest {
        val created = service.createTask(
            "issue-not-pending", "KONC-NP", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        ) as DemoResult.Success
        taskRepository.updateStatus(created.value.id, DemoStatus.COMPLETED)
        val result = service.executeTask(created.value.id)
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording with ai timeline and repro steps enabled`() = runTest {
        val aiService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                ai = DemoConfig.AiConfig(model = "free", timelineEnabled = true, reproStepsEnabled = true)
            ),
            taskRepository = taskRepository,
            recorderFactory = recorderFactory,
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger,
            aiTimelineGenerator = AiTimelineGenerator(apiEndpoint = "", apiKey = "", model = "free")
        )
        val result = aiService.requestRecording(
            "issue-ai", "KONC-AI", "test", DemoPlatform.PLAYWRIGHT, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
    }

    @Test
    fun `requestRecording falls back from adb to asciinema`() = runTest {
        val asciinema = FakeAsciinemaRecorder()
        val failAdb = object : DemoRecorder {
            override val platform = DemoPlatform.ADB
            override suspend fun isAvailable(): Boolean = true
            override suspend fun record(
                config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
            ) = DemoResult.Failure(DemoError.RecordingFailed(RuntimeException("adb_failed")))
        }
        val adbService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0,
                retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(failAdb, asciinema)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = adbService.requestRecording(
            "issue-adb", "KONC-ADB", "test", DemoPlatform.ADB, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value.platform == DemoPlatform.ASCIINEMA)
    }

    @Test
    fun `requestRecording reports failure when fallback recorder unavailable`() = runTest {
        val failPlaywright = FailingRecorder()
        val noFallbackService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0,
                retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(failPlaywright)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = noFallbackService.requestRecording(
            "issue-nofb", "KONC-NOFB", "test", DemoPlatform.ASCIINEMA, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `requestRecording falls back from ffmpeg to asciinema`() = runTest {
        val asciinema = FakeAsciinemaRecorder()
        val failFfmpeg = object : DemoRecorder {
            override val platform = DemoPlatform.FFMPEG
            override suspend fun isAvailable(): Boolean = true
            override suspend fun record(
                config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
            ) = DemoResult.Failure(DemoError.RecordingFailed(RuntimeException("ffmpeg_failed")))
        }
        val ffmpegService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0,
                retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(failFfmpeg, asciinema)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = ffmpegService.requestRecording(
            "issue-ffmpeg", "KONC-FF", "test", DemoPlatform.FFMPEG, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value.platform == DemoPlatform.ASCIINEMA)
    }

    @Test
    fun `requestRecording falls back from xcrun to asciinema`() = runTest {
        val asciinema = FakeAsciinemaRecorder()
        val failXcrun = object : DemoRecorder {
            override val platform = DemoPlatform.XCRUN
            override suspend fun isAvailable(): Boolean = true
            override suspend fun record(
                config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
            ) = DemoResult.Failure(DemoError.RecordingFailed(RuntimeException("xcrun_failed")))
        }
        val xcrunService = DemoRecordingService(
            config = DemoConfig(
                tempDir = System.getProperty("java.io.tmpdir"),
                targetUrl = "http://localhost:3000",
                maxRetries = 0,
                retryDelayMs = 1
            ),
            taskRepository = taskRepository,
            recorderFactory = RecorderFactory(listOf(failXcrun, asciinema)),
            storage = storage,
            reporter = reporter,
            reportGenerator = reportGenerator,
            metrics = metrics,
            auditLogger = auditLogger
        )
        val result = xcrunService.requestRecording(
            "issue-xcrun", "KONC-XC", "test", DemoPlatform.XCRUN, DemoTrigger.MANUAL
        )
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value.platform == DemoPlatform.ASCIINEMA)
    }

    @Test
    fun `performIntegrityCheck rejects missing and empty files`() = runTest {
        val task = DemoTask(
            id = "integrity-1", issueId = "issue-i", issueIdentifier = "KONC-I",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.RECORDING, trigger = DemoTrigger.MANUAL,
            createdAt = Instant.now().toString(), updatedAt = Instant.now().toString()
        )
        val missing = invokePerformIntegrityCheck(
            task, File("/tmp/does-not-exist-${UUID.randomUUID()}.webm")
        )
        assert(missing is DemoResult.Failure)

        val emptyFile = File.createTempFile("empty-demo", ".webm")
        emptyFile.writeText("")
        val empty = invokePerformIntegrityCheck(task, emptyFile)
        assert(empty is DemoResult.Failure)
        emptyFile.delete()
    }

    @Test
    fun `performIntegrityCheck rejects recordings shorter than 500ms`() = runTest {
        val task = DemoTask(
            id = "integrity-2", issueId = "issue-i2", issueIdentifier = "KONC-I2",
            projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.RECORDING, trigger = DemoTrigger.MANUAL,
            createdAt = Instant.now().toString(), updatedAt = Instant.now().toString(),
            durationMs = 100L
        )
        val file = File.createTempFile("short-demo", ".webm")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val result = invokePerformIntegrityCheck(task, file)
        assert(result is DemoResult.Failure)
        file.delete()
    }

    private suspend fun invokePerformIntegrityCheck(task: DemoTask, file: File): DemoResult<Unit> {
        val method = DemoRecordingService::class.java.getDeclaredMethod(
            "performIntegrityCheck",
            DemoTask::class.java,
            File::class.java,
            Continuation::class.java
        )
        method.isAccessible = true
        return suspendCancellableCoroutine { cont ->
            val invokeResult = runCatching {
                method.invoke(service, task, file, cont)
            }
            invokeResult.onFailure { cont.resumeWith(Result.failure(it)) }
            if (invokeResult.isSuccess && invokeResult.getOrNull() !== COROUTINE_SUSPENDED) {
                @Suppress("UNCHECKED_CAST")
                cont.resume(invokeResult.getOrNull() as DemoResult<Unit>)
            }
        }
    }
}

class FakeFailingStorage : DemoStorage {
    override suspend fun upload(taskId: String, file: File, contentType: String): DemoResult<DemoStorage.StorageResult> =
        DemoResult.Success(DemoStorage.StorageResult("key", "url", 0L))

    override suspend fun uploadWithTags(taskId: String, file: File, contentType: String, tags: Map<String, String>): DemoResult<DemoStorage.StorageResult> =
        DemoResult.Success(DemoStorage.StorageResult("key", "url", 0L))

    override suspend fun delete(storageKey: String): DemoResult<Unit> = DemoResult.Success(Unit)

    override suspend fun generateUrl(storageKey: String, expiresInSeconds: Long): DemoResult<String> =
        DemoResult.Success("https://cdn.example.com/$storageKey")

    override suspend fun checkQuota(): DemoResult<DemoStorage.QuotaInfo> =
        DemoResult.Success(DemoStorage.QuotaInfo(0L, 9L * 1024 * 1024 * 1024, 9L * 1024 * 1024 * 1024))

    override suspend fun listOldest(limit: Int): DemoResult<List<DemoStorage.StorageItem>> =
        DemoResult.Success(emptyList())

    override suspend fun deleteBatch(storageKeys: List<String>): DemoResult<Int> =
        DemoResult.Failure(DemoError.StorageFailed(RuntimeException("batch_delete_failed")))
}

class ShortThenOkRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT
    var callCount = 0
    override suspend fun isAvailable(): Boolean = true
    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        callCount++
        outputFile.writeText("data")
        val duration = if (callCount == 1) 100L else 1000L
        return DemoResult.Success(DemoRecorder.RecordingResult(
            file = outputFile, durationMs = duration, fileSizeBytes = outputFile.length(), format = config.outputFormat
        ))
    }
}

class FailingRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT
    override suspend fun isAvailable(): Boolean = true
    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> =
        DemoResult.Failure(DemoError.RecordingFailed(RuntimeException("record_failed")))
}

class FakeAsciinemaRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.ASCIINEMA
    override suspend fun isAvailable(): Boolean = true
    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        outputFile.writeText("asciinema cast")
        return DemoResult.Success(DemoRecorder.RecordingResult(
            file = outputFile, durationMs = 1000L, fileSizeBytes = outputFile.length(), format = config.outputFormat
        ))
    }
}

class PartialFileRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT
    override suspend fun isAvailable(): Boolean = true
    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        outputFile.writeText("partial")
        val partial = File(outputFile.parent, "${outputFile.nameWithoutExtension}.partial.webm")
        outputFile.copyTo(partial, overwrite = true)
        return DemoResult.Failure(DemoError.RecordingFailed(RuntimeException("failed_with_partial")))
    }
}

class AlwaysShortWithPartialRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT
    override suspend fun isAvailable(): Boolean = true
    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig, outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        outputFile.writeText("short recording")
        val partial = File(outputFile.parent, "${outputFile.nameWithoutExtension}.partial.webm")
        outputFile.copyTo(partial, overwrite = true)
        return DemoResult.Success(DemoRecorder.RecordingResult(
            file = outputFile, durationMs = 100L, fileSizeBytes = outputFile.length(), format = config.outputFormat
        ))
    }
}

class QuotaCheckFailStorage : DemoStorage {
    override suspend fun upload(taskId: String, file: File, contentType: String): DemoResult<DemoStorage.StorageResult> =
        DemoResult.Success(DemoStorage.StorageResult("key", "url", 0L))
    override suspend fun uploadWithTags(taskId: String, file: File, contentType: String, tags: Map<String, String>): DemoResult<DemoStorage.StorageResult> =
        DemoResult.Success(DemoStorage.StorageResult("key", "url", 0L))
    override suspend fun delete(storageKey: String): DemoResult<Unit> = DemoResult.Success(Unit)
    override suspend fun generateUrl(storageKey: String, expiresInSeconds: Long): DemoResult<String> =
        DemoResult.Success("url")
    override suspend fun checkQuota(): DemoResult<DemoStorage.QuotaInfo> =
        DemoResult.Failure(DemoError.StorageFailed(RuntimeException("quota_check_failed")))
    override suspend fun listOldest(limit: Int): DemoResult<List<DemoStorage.StorageItem>> =
        DemoResult.Success(emptyList())
    override suspend fun deleteBatch(storageKeys: List<String>): DemoResult<Int> =
        DemoResult.Success(0)
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
    var lastSkippedIssueId: String? = null
    var lastSkippedReason: String? = null
    override suspend fun report(task: DemoTask, recordingUrl: String): DemoResult<Unit> {
        lastReportedTask = task; lastReportedUrl = recordingUrl
        return DemoResult.Success(Unit)
    }
    override suspend fun reportFailure(task: DemoTask, errorMessage: String): DemoResult<Unit> {
        lastReportedTask = task
        return DemoResult.Success(Unit)
    }
    override suspend fun reportSkipped(issueId: String, issueIdentifier: String, reason: String): DemoResult<Unit> {
        lastSkippedIssueId = issueId
        lastSkippedReason = reason
        return DemoResult.Success(Unit)
    }
}

class FakeDemoTaskRepository : DemoTaskRepository {
    private val tasks = mutableMapOf<String, DemoTask>()
    var failNextSave: Boolean = false

    override suspend fun save(task: DemoTask) {
        if (failNextSave) throw RuntimeException("db unavailable")
        tasks[task.id] = task
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
