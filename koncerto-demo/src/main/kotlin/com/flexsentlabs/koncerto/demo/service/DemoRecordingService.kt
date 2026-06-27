package com.flexsentlabs.koncerto.demo.service

import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import com.flexsentlabs.koncerto.demo.report.AiTimelineGenerator
import com.flexsentlabs.koncerto.demo.report.DemoReporter
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import com.flexsentlabs.koncerto.demo.repository.DemoTaskRepository
import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DemoRecordingService(
    private val config: DemoConfig,
    private val taskRepository: DemoTaskRepository,
    private val recorderFactory: RecorderFactory,
    private val storage: DemoStorage,
    private val reporter: DemoReporter,
    private val reportGenerator: DemoReportGenerator,
    private val metrics: DemoMetricsRecorder,
    private val auditLogger: DemoAuditLogger,
    private val aiTimelineGenerator: AiTimelineGenerator? = null
) {
    suspend fun createTask(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        platform: DemoPlatform, trigger: DemoTrigger
    ): DemoResult<DemoTask> {
        return try {
            val taskId = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val task = DemoTask(
                id = taskId, issueId = issueId, issueIdentifier = issueIdentifier,
                projectSlug = projectSlug, platform = platform, status = DemoStatus.PENDING,
                trigger = trigger, createdAt = now, updatedAt = now
            )
            taskRepository.save(task)
            auditLogger.logTaskCreated(task)
            DemoResult.Success(task)
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.RecordingFailed(e))
        }
    }

    suspend fun executeTask(taskId: String, targetUrl: String? = null): DemoResult<DemoTask> {
        val task = taskRepository.findById(taskId)
            ?: return DemoResult.Failure(DemoError.TaskNotFound(taskId))
        if (task.status != DemoStatus.PENDING) {
            return DemoResult.Failure(DemoError.InvalidConfig("Task $taskId is not pending"))
        }

        val preflight = runPreflightChecks(task.platform)
        if (preflight is DemoResult.Failure) {
            taskRepository.updateStatus(taskId, DemoStatus.FAILED, preflight.error.message)
            auditLogger.logTaskFailed(task, preflight.error.message ?: "preflight_failed")
            return preflight as DemoResult.Failure
        }

        return executeWithRetry(task, targetUrl)
    }

    suspend fun requestRecording(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        platform: DemoPlatform?, trigger: DemoTrigger,
        targetUrl: String? = null
    ): DemoResult<DemoTask> {
        val resolvedPlatform = platform ?: resolvePlatform()
        if (resolvedPlatform == null) {
            return DemoResult.Failure(DemoError.RecorderNotAvailable("no_platform_available"))
        }

        val quotaCheck = performQuotaCheck()
        if (quotaCheck is DemoResult.Failure) return quotaCheck as DemoResult<DemoTask>

        val createResult = createTask(issueId, issueIdentifier, projectSlug, resolvedPlatform, trigger)
        if (createResult is DemoResult.Failure) return createResult
        return executeTask((createResult as DemoResult.Success).value.id, targetUrl)
    }

    suspend fun deleteOldRecordings(): Int {
        val cutoff = Instant.now().minusSeconds(config.retentionDays * 86400L).toString()
        val count = taskRepository.deleteOlderThan(cutoff, config.maxRecordingsPerSpace)
        auditLogger.logCleanup(count)
        return count
    }

    suspend fun enforceRetentionPolicy(): DemoResult<Int> {
        val cutoff = Instant.now().minusSeconds(config.retentionDays * 86400L).toString()
        val oldTasks = taskRepository.findOlderThan(cutoff)
        if (oldTasks.isEmpty()) return DemoResult.Success(0)

        val keysToDelete = oldTasks.mapNotNull { it.storageKey }
        val r2Result = storage.deleteBatch(keysToDelete)
        if (r2Result is DemoResult.Failure) {
            return r2Result as DemoResult<Int>
        }
        val deleted = taskRepository.deleteOlderThan(cutoff, oldTasks.size)
        auditLogger.logCleanup(deleted)
        return DemoResult.Success(deleted)
    }

    suspend fun getTask(taskId: String): DemoResult<DemoTask> {
        val task = taskRepository.findById(taskId)
            ?: return DemoResult.Failure(DemoError.TaskNotFound(taskId))
        return DemoResult.Success(task)
    }

    suspend fun getTasksByIssue(issueId: String): List<DemoTask> =
        taskRepository.findByIssue(issueId)

    suspend fun getPendingTasks(): List<DemoTask> =
        taskRepository.findPending()

    suspend fun getMetrics() = metrics.snapshot()

    suspend fun toggleKeep(taskId: String, isKept: Boolean): DemoResult<Unit> {
        val task = taskRepository.findById(taskId)
            ?: return DemoResult.Failure(DemoError.TaskNotFound(taskId))
        taskRepository.updateKeepFlag(taskId, isKept)
        return DemoResult.Success(Unit)
    }

    suspend fun markBlocked(taskId: String): DemoResult<Unit> {
        val task = taskRepository.findById(taskId)
            ?: return DemoResult.Failure(DemoError.TaskNotFound(taskId))
        reporter.reportFailure(task, "BLOCKED: Total demo recording failure after retries")
        return DemoResult.Success(Unit)
    }

    private suspend fun performQuotaCheck(): DemoResult<Unit> {
        val currentBytes = taskRepository.sumFileSizes()
        val limitBytes = config.maxRecordingsPerSpace * 50L * 1024 * 1024
        auditLogger.logQuotaCheck(currentBytes, limitBytes)
        if (currentBytes >= limitBytes) {
            val freed = enforceRetentionPolicy()
            if (freed is DemoResult.Success && freed.value > 0) {
                val newTotal = taskRepository.sumFileSizes()
                if (newTotal >= limitBytes) {
                    return DemoResult.Failure(DemoError.QuotaExceeded(newTotal, limitBytes))
                }
            } else {
                return DemoResult.Failure(DemoError.QuotaExceeded(currentBytes, limitBytes))
            }
        }
        return DemoResult.Success(Unit)
    }

    private suspend fun performIntegrityCheck(task: DemoTask, file: File): DemoResult<Unit> {
        if (!file.exists()) {
            return DemoResult.Failure(DemoError.IntegrityCheckFailed("file_not_found"))
        }
        if (file.length() == 0L) {
            return DemoResult.Failure(DemoError.IntegrityCheckFailed("empty_file"))
        }
        if (task.durationMs != null && task.durationMs < 500) {
            return DemoResult.Failure(DemoError.IntegrityCheckFailed("duration_too_short: ${task.durationMs}ms"))
        }
        return DemoResult.Success(Unit)
    }

    private suspend fun runPreflightChecks(platform: DemoPlatform): DemoResult<Unit> {
        return try {
            val recorderCheck = recorderFactory.findRecorder(platform)
            if (recorderCheck is DemoResult.Failure) {
                return DemoResult.Failure(DemoError.PreflightFailed("recorder_${(recorderCheck as DemoResult.Failure).error.message}"))
            }
            val tempDir = File(config.tempDir)
            if (!tempDir.exists()) withContext(Dispatchers.IO) { Files.createDirectories(tempDir.toPath()) }
            if (!tempDir.canWrite()) return DemoResult.Failure(DemoError.PreflightFailed("temp_dir_not_writable"))

            val storageCheck = storage.checkQuota()
            if (storageCheck is DemoResult.Failure) {
                return DemoResult.Failure(DemoError.PreflightFailed("storage_${(storageCheck as DemoResult.Failure).error.message}"))
            }

            DemoResult.Success(Unit)
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.PreflightFailed(e.message ?: "unknown"))
        }
    }

    private suspend fun executeWithRetry(task: DemoTask, targetUrl: String? = null): DemoResult<DemoTask> {
        var currentTask = task
        var lastError: DemoError? = null

        for (attempt in 0..config.maxRetries) {
            taskRepository.updateStatus(currentTask.id, DemoStatus.RECORDING)
            auditLogger.logRecordingStarted(currentTask)
            metrics.recordAttempt(currentTask.platform.name, DemoStatus.RECORDING, 0)
            val result = performRecording(currentTask, targetUrl)

            when (result) {
                is DemoResult.Success -> {
                    val completed = result.value
                    val integrityCheck = performIntegrityCheck(completed, File(config.tempDir, "${completed.id}.webm"))
                    if (integrityCheck is DemoResult.Failure && attempt < config.maxRetries) {
                        metrics.recordAttempt(currentTask.platform.name, DemoStatus.FAILED, 0)
                        lastError = integrityCheck.error
                        delay(config.retryDelayMs * (1L shl attempt))
                        currentTask = currentTask.copy(retryCount = attempt + 1)
                        taskRepository.save(currentTask)
                        continue
                    }
                    if (integrityCheck is DemoResult.Failure) {
                        val recovered = attemptPartialRecovery(currentTask, integrityCheck.error)
                        if (recovered is DemoResult.Success) return recovered
                    }
                    metrics.recordAttempt(currentTask.platform.name, DemoStatus.COMPLETED, completed.durationMs ?: 0L)
                    return DemoResult.Success(completed)
                }
                is DemoResult.Failure -> {
                    lastError = result.error
                    metrics.recordAttempt(currentTask.platform.name, DemoStatus.FAILED, 0)
                    if (attempt < config.maxRetries) {
                        val backoffMs = config.retryDelayMs * (1L shl attempt)
                        delay(backoffMs)
                        currentTask = currentTask.copy(retryCount = attempt + 1)
                        taskRepository.save(currentTask)
                    }
                }
            }
        }

        val errorMsg = lastError?.message ?: "max_retries_exceeded"
        taskRepository.updateStatus(currentTask.id, DemoStatus.FAILED, errorMsg)
        auditLogger.logTaskFailed(currentTask, errorMsg)
        metrics.recordAttempt(currentTask.platform.name, DemoStatus.FAILED, 0)

        val fallbackResult = attemptFallbackRecording(currentTask, lastError)
        if (fallbackResult is DemoResult.Success) return fallbackResult

        reporter.reportFailure(currentTask, errorMsg)
        return DemoResult.Failure(lastError ?: DemoError.RecordingFailed(RuntimeException("max_retries_exceeded")))
    }

    private suspend fun attemptFallbackRecording(failedTask: DemoTask, originalError: DemoError?): DemoResult<DemoTask> {
        val fallbackPlatform = when (failedTask.platform) {
            DemoPlatform.PLAYWRIGHT -> DemoPlatform.ASCIINEMA
            DemoPlatform.ADB -> DemoPlatform.ASCIINEMA
            DemoPlatform.XCRUN -> DemoPlatform.ASCIINEMA
            DemoPlatform.FFMPEG -> DemoPlatform.ASCIINEMA
            else -> return DemoResult.Failure(originalError ?: DemoError.RecorderNotAvailable("no_fallback"))
        }

        val fallbackRecorder = recorderFactory.findRecorder(fallbackPlatform)
        if (fallbackRecorder is DemoResult.Failure) {
            return DemoResult.Failure(originalError ?: DemoError.RecorderNotAvailable("no_fallback"))
        }

        val fallbackTask = createTask(
            issueId = failedTask.issueId,
            issueIdentifier = failedTask.issueIdentifier,
            projectSlug = failedTask.projectSlug,
            platform = fallbackPlatform,
            trigger = failedTask.trigger
        )

        if (fallbackTask is DemoResult.Failure) {
            return DemoResult.Failure(originalError ?: DemoError.RecorderNotAvailable("fallback_creation_failed"))
        }

        val fallbackId = (fallbackTask as DemoResult.Success).value.id
        taskRepository.updateFallbackFrom(fallbackId, failedTask.platform.name)
        taskRepository.updateStatus(failedTask.id, DemoStatus.FAILED, "fallback_to_${fallbackPlatform.name}")
        auditLogger.logFallback(failedTask, failedTask.platform.name, fallbackPlatform.name)

        return executeWithRetry((fallbackTask as DemoResult.Success).value)
    }

    private suspend fun attemptPartialRecovery(task: DemoTask, integrityError: DemoError): DemoResult<DemoTask> {
        val partialFile = File(config.tempDir, "${task.id}.partial.webm")
        if (!partialFile.exists()) {
            return DemoResult.Failure(DemoError.PartialRecovery("no_partial_file"))
        }

        taskRepository.updateStatus(task.id, DemoStatus.PARTIAL, "partial_recovery: ${integrityError.message}")
        auditLogger.logTaskFailed(task, "partial: ${integrityError.message}")

        val mimeType = "video/webm"
        val uploadResult = storage.upload(task.id, partialFile, mimeType)
        if (uploadResult is DemoResult.Failure) {
            return DemoResult.Failure(DemoError.PartialRecovery("upload_failed: ${(uploadResult as DemoResult.Failure).error.message}"))
        }

        val storageResult = (uploadResult as DemoResult.Success).value
        taskRepository.updateCompleted(
            taskId = task.id, status = DemoStatus.PARTIAL,
            recordingUrl = storageResult.url, storageKey = storageResult.storageKey,
            durationMs = task.durationMs, fileSizeBytes = storageResult.sizeBytes
        )

        partialFile.delete()

        val updatedTask = taskRepository.findById(task.id)
            ?: return DemoResult.Failure(DemoError.TaskNotFound(task.id))
        reporter.report(updatedTask, storageResult.url)
        return DemoResult.Success(updatedTask)
    }

    private suspend fun performRecording(task: DemoTask, targetUrl: String? = null): DemoResult<DemoTask> {
        val recorderResult = recorderFactory.findRecorder(task.platform)
        if (recorderResult is DemoResult.Failure) return recorderResult as DemoResult<DemoTask>

        val recorder = (recorderResult as DemoResult.Success).value
        val effectiveTargetUrl = targetUrl?.takeIf { it.isNotBlank() } ?: config.targetUrl
        val scenarioPath = resolveScenarioPath(task)
        val recordingConfig = RecordingConfig(
            platform = task.platform,
            targetUrl = effectiveTargetUrl,
            scenarioPath = scenarioPath
        )
        val tempFile = File(config.tempDir, "${task.id}.${recordingConfig.outputFormat}")

        val recordResult = recorder.record(recordingConfig, tempFile)
        if (recordResult is DemoResult.Failure) {
            val partialFile = if (tempFile.exists() && tempFile.length() > 0) tempFile else null
            if (partialFile != null) {
                partialFile.renameTo(File(config.tempDir, "${task.id}.partial.webm"))
            }
            return recordResult as DemoResult<DemoTask>
        }

        val recordingResult = (recordResult as DemoResult.Success).value
        taskRepository.updateStatus(task.id, DemoStatus.ENCODING)
        auditLogger.logRecordingCompleted(task, recordingResult.durationMs)

        val mimeType = "video/${recordingConfig.outputFormat}"
        val tags = mapOf(
            "task_id" to task.id,
            "issue_id" to task.issueId,
            "platform" to task.platform.name,
            "trigger" to task.trigger.name
        )

        taskRepository.updateStatus(task.id, DemoStatus.UPLOADING)
        val uploadResult = storage.uploadWithTags(task.id, recordingResult.file, mimeType, tags)
        if (uploadResult is DemoResult.Failure) return uploadResult as DemoResult<DemoTask>

        val storageResult = (uploadResult as DemoResult.Success).value
        metrics.recordStorageResult(true)
        auditLogger.logUploadCompleted(task, storageResult.storageKey)

        val reportFile = File(config.tempDir, "${task.id}.html")
        val timelineEvents = if (config.ai?.timelineEnabled == true && aiTimelineGenerator != null) {
            when (val r = aiTimelineGenerator.generateTimeline(task)) {
                is DemoResult.Success -> r.value
                else -> null
            }
        } else null
        val reproSteps = if (config.ai?.reproStepsEnabled == true && aiTimelineGenerator != null) {
            when (val r = aiTimelineGenerator.generateReproSteps(task)) {
                is DemoResult.Success -> r.value
                else -> null
            }
        } else null
        val reportResult = reportGenerator.generateHtmlReport(
            task, storageResult.url, reportFile,
            timelineEvents = timelineEvents, reproSteps = reproSteps
        )
        if (reportResult is DemoResult.Success) {
            val htmlUploadResult = storage.upload(task.id, reportFile, "text/html")
            if (htmlUploadResult is DemoResult.Success) {
                val htmlKey = (htmlUploadResult as DemoResult.Success).value.storageKey
                taskRepository.updateHtmlReportKey(task.id, htmlKey)
            }
        }

        taskRepository.updateCompleted(
            taskId = task.id, status = DemoStatus.COMPLETED,
            recordingUrl = storageResult.url, storageKey = storageResult.storageKey,
            durationMs = recordingResult.durationMs, fileSizeBytes = recordingResult.fileSizeBytes
        )

        val updatedTask = taskRepository.findById(task.id)
            ?: return DemoResult.Failure(DemoError.TaskNotFound(task.id))

        reporter.report(updatedTask, storageResult.url)
        auditLogger.logReportPosted(updatedTask, storageResult.url)

        return DemoResult.Success(updatedTask)
    }

    private fun resolveScenarioPath(task: DemoTask): String {
        val scenarioFile = File(config.tempDir, "${task.issueId}-scenario.yaml")
        if (scenarioFile.exists()) return scenarioFile.absolutePath
        // Also check for issue-identifier based scenario (for backward compatibility)
        val identFile = File(config.tempDir, "${task.issueIdentifier}-scenario.yaml")
        if (identFile.exists()) return identFile.absolutePath
        return ""
    }

    private suspend fun resolvePlatform(): DemoPlatform? {
        val available = recorderFactory.availablePlatforms()
        return available.firstOrNull { it == DemoPlatform.PLAYWRIGHT }
            ?: available.firstOrNull { it == DemoPlatform.ASCIINEMA }
            ?: available.firstOrNull()
    }
}
