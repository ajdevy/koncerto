package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.repository.DemoTaskRepository
import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class DemoModelTest {

    @Test
    fun `RecorderNotAvailable error`() {
        val error = DemoError.RecorderNotAvailable("playwright")
        assert(error.message == "recorder_not_available: playwright")
    }

    @Test
    fun `RecordingFailed error`() {
        val error = DemoError.RecordingFailed(RuntimeException("timeout"))
        assert(error.message == "recording_failed: timeout")
    }

    @Test
    fun `StorageFailed error`() {
        val error = DemoError.StorageFailed(RuntimeException("disk full"))
        assert(error.message == "storage_failed: disk full")
    }

    @Test
    fun `ReportFailed error`() {
        val error = DemoError.ReportFailed(RuntimeException("api error"))
        assert(error.message == "report_failed: api error")
    }

    @Test
    fun `PreflightFailed error`() {
        val error = DemoError.PreflightFailed("recorder_not_found")
        assert(error.message == "preflight_failed: recorder_not_found")
    }

    @Test
    fun `TaskNotFound error`() {
        val error = DemoError.TaskNotFound("task-123")
        assert(error.message == "task_not_found: task-123")
    }

    @Test
    fun `QuotaExceeded error`() {
        val error = DemoError.QuotaExceeded(5_000_000L, 10_000_000L)
        assert(error.message == "quota_exceeded: 5MB / 10MB")
    }

    @Test
    fun `InvalidConfig error`() {
        val error = DemoError.InvalidConfig("unknown platform")
        assert(error.message == "invalid_config: unknown platform")
    }

    @Test
    fun `IntegrityCheckFailed error`() {
        val error = DemoError.IntegrityCheckFailed("empty_file")
        assert(error.message == "integrity_failed: empty_file")
    }

    @Test
    fun `AiModelFailed error`() {
        val error = DemoError.AiModelFailed(RuntimeException("model timeout"))
        assert(error.message == "ai_model_failed: model timeout")
    }

    @Test
    fun `LinearApiError error`() {
        val error = DemoError.LinearApiError(RuntimeException("rate limited"))
        assert(error.message == "linear_api_error: rate limited")
    }

    @Test
    fun `PartialRecovery error`() {
        val error = DemoError.PartialRecovery("no_partial_file")
        assert(error.message == "partial_recovery: no_partial_file")
    }

    @Test
    fun `DemoTask constructor with all fields`() {
        val task = DemoTask(
            id = "task-1",
            issueId = "issue-1",
            issueIdentifier = "KONC-123",
            projectSlug = "test-project",
            platform = DemoPlatform.PLAYWRIGHT,
            status = DemoStatus.PENDING,
            recordingUrl = "https://example.com/recording.webm",
            storageKey = "demo-recordings/task-1/recording.webm",
            durationMs = 5000L,
            fileSizeBytes = 1024L,
            errorMessage = null,
            retryCount = 2,
            trigger = DemoTrigger.MANUAL,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:01:00Z",
            completedAt = "2026-01-01T00:01:05Z",
            isKept = true,
            metadata = "{\"source\": \"manual\"}",
            htmlReportKey = "reports/task-1.html",
            fallbackFrom = "ADB"
        )
        assert(task.id == "task-1")
        assert(task.issueId == "issue-1")
        assert(task.issueIdentifier == "KONC-123")
        assert(task.projectSlug == "test-project")
        assert(task.platform == DemoPlatform.PLAYWRIGHT)
        assert(task.status == DemoStatus.PENDING)
        assert(task.recordingUrl == "https://example.com/recording.webm")
        assert(task.storageKey == "demo-recordings/task-1/recording.webm")
        assert(task.durationMs == 5000L)
        assert(task.fileSizeBytes == 1024L)
        assert(task.errorMessage == null)
        assert(task.retryCount == 2)
        assert(task.trigger == DemoTrigger.MANUAL)
        assert(task.createdAt == "2026-01-01T00:00:00Z")
        assert(task.updatedAt == "2026-01-01T00:01:00Z")
        assert(task.completedAt == "2026-01-01T00:01:05Z")
        assert(task.isKept)
        assert(task.metadata == "{\"source\": \"manual\"}")
        assert(task.htmlReportKey == "reports/task-1.html")
        assert(task.fallbackFrom == "ADB")
    }

    @Test
    fun `DemoTask copy preserves fields`() {
        val task = DemoTask(
            id = "task-1", issueId = "issue-1", issueIdentifier = "KONC-123",
            projectSlug = null, platform = DemoPlatform.PLAYWRIGHT, status = DemoStatus.PENDING,
            trigger = DemoTrigger.MANUAL, createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        val copied = task.copy(status = DemoStatus.COMPLETED, retryCount = 1)
        assert(copied.id == task.id)
        assert(copied.status == DemoStatus.COMPLETED)
        assert(copied.retryCount == 1)
    }

    @Test
    fun `DemoTask default values`() {
        val task = DemoTask(
            id = "task-1", issueId = "issue-1", issueIdentifier = "KONC-123",
            projectSlug = null, platform = DemoPlatform.PLAYWRIGHT, status = DemoStatus.PENDING,
            trigger = DemoTrigger.MANUAL, createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        assert(task.recordingUrl == null)
        assert(task.storageKey == null)
        assert(task.durationMs == null)
        assert(task.fileSizeBytes == null)
        assert(task.errorMessage == null)
        assert(task.retryCount == 0)
        assert(task.completedAt == null)
        assert(!task.isKept)
        assert(task.metadata == null)
        assert(task.htmlReportKey == null)
        assert(task.fallbackFrom == null)
    }

    @Test
    fun `DemoPlatform enum values`() {
        assert(DemoPlatform.values().toSet() == setOf(
            DemoPlatform.PLAYWRIGHT, DemoPlatform.ASCIINEMA,
            DemoPlatform.ADB, DemoPlatform.XCRUN, DemoPlatform.FFMPEG
        ))
    }

    @Test
    fun `DemoStatus enum values`() {
        assert(DemoStatus.values().toSet() == setOf(
            DemoStatus.PENDING, DemoStatus.RECORDING, DemoStatus.ENCODING,
            DemoStatus.UPLOADING, DemoStatus.COMPLETED, DemoStatus.FAILED, DemoStatus.PARTIAL
        ))
    }

    @Test
    fun `DemoTrigger enum values`() {
        assert(DemoTrigger.values().toSet() == setOf(
            DemoTrigger.MANUAL, DemoTrigger.REVIEW_PASSED, DemoTrigger.SCHEDULED
        ))
    }

    @Test
    fun `RecordingConfig constructor with defaults`() {
        val config = RecordingConfig(platform = DemoPlatform.PLAYWRIGHT)
        assert(config.platform == DemoPlatform.PLAYWRIGHT)
        assert(config.width == 1280)
        assert(config.height == 720)
        assert(config.frameRate == 10)
        assert(config.maxDurationSeconds == 120)
        assert(config.timestampOverlay)
        assert(config.codec == "vp9")
        assert(config.outputFormat == "webm")
        assert(config.targetUrl == "")
        assert(config.captureInputIndex == "1")
        assert(config.scenarioPath == "")
    }

    @Test
    fun `RecordingConfig maxDuration computed property`() {
        val config = RecordingConfig(platform = DemoPlatform.ASCIINEMA, maxDurationSeconds = 60)
        assert(config.maxDuration == 60.seconds)
    }

    @Test
    fun `RecordingConfig custom values`() {
        val config = RecordingConfig(
            platform = DemoPlatform.FFMPEG, width = 1920, height = 1080,
            frameRate = 30, maxDurationSeconds = 300, timestampOverlay = false,
            codec = "h264", outputFormat = "mp4", targetUrl = "https://example.com",
            captureInputIndex = "0", scenarioPath = "/tmp/scenario.yaml"
        )
        assert(config.width == 1920)
        assert(config.height == 1080)
        assert(config.frameRate == 30)
        assert(config.maxDurationSeconds == 300)
        assert(!config.timestampOverlay)
        assert(config.codec == "h264")
        assert(config.outputFormat == "mp4")
        assert(config.targetUrl == "https://example.com")
        assert(config.captureInputIndex == "0")
        assert(config.scenarioPath == "/tmp/scenario.yaml")
    }

    @Test
    fun `RecordingResult data class`() {
        val file = File("/tmp/test.webm")
        val result = DemoRecorder.RecordingResult(file, 5000L, 1024L, "webm")
        assert(result.file == file)
        assert(result.durationMs == 5000L)
        assert(result.fileSizeBytes == 1024L)
        assert(result.format == "webm")
    }

    @Test
    fun `StorageResult data class`() {
        val result = DemoStorage.StorageResult("storage-key", "https://cdn.example.com/video", 2048L)
        assert(result.storageKey == "storage-key")
        assert(result.url == "https://cdn.example.com/video")
        assert(result.sizeBytes == 2048L)
    }

    @Test
    fun `QuotaInfo data class`() {
        val info = DemoStorage.QuotaInfo(500L, 1000L, 500L)
        assert(info.usedBytes == 500L)
        assert(info.limitBytes == 1000L)
        assert(info.availableBytes == 500L)
    }

    @Test
    fun `StorageItem data class`() {
        val item = DemoStorage.StorageItem("storage-key", 1024L, "2026-01-01T00:00:00Z")
        assert(item.storageKey == "storage-key")
        assert(item.sizeBytes == 1024L)
        assert(item.lastModified == "2026-01-01T00:00:00Z")
    }

    @Test
    fun `DemoTaskRepository is an interface type`() {
        val repo: DemoTaskRepository? = null
        assert(repo == null)
    }
}
