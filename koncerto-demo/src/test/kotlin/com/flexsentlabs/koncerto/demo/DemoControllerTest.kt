package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DemoControllerTest {

    private lateinit var recordingService: DemoRecordingService
    private lateinit var controller: DemoController

    private val sampleTask = DemoTask(
        id = "task-1",
        issueId = "issue-1",
        issueIdentifier = "KONC-1",
        projectSlug = "demo",
        platform = DemoPlatform.PLAYWRIGHT,
        status = DemoStatus.PENDING,
        trigger = DemoTrigger.MANUAL,
        createdAt = Instant.now().toString(),
        updatedAt = Instant.now().toString()
    )

    @BeforeEach
    fun setUp() {
        recordingService = mockk()
        controller = DemoController(recordingService)
    }

    @Test
    fun `requestRecording delegates to service with resolved platform`() = runTest {
        coEvery {
            recordingService.requestRecording(
                issueId = "issue-1",
                issueIdentifier = "KONC-1",
                projectSlug = "demo",
                platform = DemoPlatform.PLAYWRIGHT,
                trigger = DemoTrigger.MANUAL
            )
        } returns DemoResult.Success(sampleTask)

        val result = controller.requestRecording("issue-1", "KONC-1", "demo", "playwright")

        assert(result is DemoResult.Success)
        coVerify(exactly = 1) {
            recordingService.requestRecording(
                issueId = "issue-1",
                issueIdentifier = "KONC-1",
                projectSlug = "demo",
                platform = DemoPlatform.PLAYWRIGHT,
                trigger = DemoTrigger.MANUAL
            )
        }
    }

    @Test
    fun `requestRecording accepts uppercase platform names`() = runTest {
        coEvery {
            recordingService.requestRecording(any(), any(), any(), DemoPlatform.FFMPEG, DemoTrigger.MANUAL)
        } returns DemoResult.Success(sampleTask.copy(platform = DemoPlatform.FFMPEG))

        val result = controller.requestRecording("issue-1", "KONC-1", "demo", "FFMPEG")

        assert(result is DemoResult.Success)
    }

    @Test
    fun `requestRecording returns failure for unknown platform`() = runTest {
        val result = controller.requestRecording("issue-1", "KONC-1", "demo", "unknown-platform")

        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error
        assert(error is DemoError.InvalidConfig)
        assert(error.message!!.contains("unknown_platform"))
    }

    @Test
    fun `requestRecording passes null platform when omitted`() = runTest {
        coEvery {
            recordingService.requestRecording(
                issueId = "issue-1",
                issueIdentifier = "KONC-1",
                projectSlug = null,
                platform = null,
                trigger = DemoTrigger.MANUAL
            )
        } returns DemoResult.Success(sampleTask)

        controller.requestRecording("issue-1", "KONC-1", null, null)

        coVerify(exactly = 1) {
            recordingService.requestRecording(
                issueId = "issue-1",
                issueIdentifier = "KONC-1",
                projectSlug = null,
                platform = null,
                trigger = DemoTrigger.MANUAL
            )
        }
    }

    @Test
    fun `getTask delegates to service`() = runTest {
        coEvery { recordingService.getTask("task-1") } returns DemoResult.Success(sampleTask)

        val result = controller.getTask("task-1")

        assert(result is DemoResult.Success)
        coVerify { recordingService.getTask("task-1") }
    }

    @Test
    fun `listByIssue delegates to service`() = runTest {
        coEvery { recordingService.getTasksByIssue("issue-1") } returns listOf(sampleTask)

        val tasks = controller.listByIssue("issue-1")

        assert(tasks.size == 1)
        coVerify { recordingService.getTasksByIssue("issue-1") }
    }

    @Test
    fun `listPending delegates to service`() = runTest {
        coEvery { recordingService.getPendingTasks() } returns listOf(sampleTask)

        val tasks = controller.listPending()

        assert(tasks.size == 1)
        coVerify { recordingService.getPendingTasks() }
    }

    @Test
    fun `retryTask delegates to executeTask`() = runTest {
        coEvery { recordingService.executeTask("task-1") } returns DemoResult.Success(sampleTask)

        val result = controller.retryTask("task-1")

        assert(result is DemoResult.Success)
        coVerify { recordingService.executeTask("task-1") }
    }

    @Test
    fun `getMetrics delegates to service`() = runTest {
        val snapshot = DemoMetricsRecorder.MetricsSnapshot(
            attemptsByPlatform = mapOf("PLAYWRIGHT" to 1),
            errorsByPlatform = emptyMap(),
            averageDurationMs = 1000L,
            storageUploadCount = 1,
            storageErrorCount = 0
        )
        coEvery { recordingService.getMetrics() } returns snapshot

        val metrics = controller.getMetrics()

        assert(metrics.storageUploadCount == 1)
        coVerify { recordingService.getMetrics() }
    }

    @Test
    fun `cleanup delegates to deleteOldRecordings`() = runTest {
        coEvery { recordingService.deleteOldRecordings() } returns 3

        val count = controller.cleanup()

        assert(count == 3)
        coVerify { recordingService.deleteOldRecordings() }
    }

    @Test
    fun `enforceRetention delegates to service`() = runTest {
        coEvery { recordingService.enforceRetentionPolicy() } returns DemoResult.Success(2)

        val result = controller.enforceRetention()

        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value == 2)
    }

    @Test
    fun `toggleKeep delegates to service`() = runTest {
        coEvery { recordingService.toggleKeep("task-1", true) } returns DemoResult.Success(Unit)

        val result = controller.toggleKeep("task-1", true)

        assert(result is DemoResult.Success)
        coVerify { recordingService.toggleKeep("task-1", true) }
    }

    @Test
    fun `markBlocked delegates to service`() = runTest {
        coEvery { recordingService.markBlocked("task-1") } returns DemoResult.Success(Unit)

        val result = controller.markBlocked("task-1")

        assert(result is DemoResult.Success)
        coVerify { recordingService.markBlocked("task-1") }
    }
}
