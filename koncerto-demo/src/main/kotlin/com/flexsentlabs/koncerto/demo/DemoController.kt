package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService

class DemoController(
    private val recordingService: DemoRecordingService
) {
    suspend fun requestRecording(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        platform: String? = null
    ): DemoResult<DemoTask> {
        val resolvedPlatform = platform?.let {
            try { DemoPlatform.valueOf(it.uppercase()) }
            catch (_: IllegalArgumentException) {
                return DemoResult.Failure(
                    com.flexsentlabs.koncerto.demo.model.DemoError.InvalidConfig("unknown_platform: $platform")
                )
            }
        }
        return recordingService.requestRecording(
            issueId = issueId, issueIdentifier = issueIdentifier,
            projectSlug = projectSlug, platform = resolvedPlatform, trigger = DemoTrigger.MANUAL
        )
    }

    suspend fun getTask(taskId: String): DemoResult<DemoTask> =
        recordingService.getTask(taskId)

    suspend fun listByIssue(issueId: String): List<DemoTask> =
        recordingService.getTasksByIssue(issueId)

    suspend fun listPending(): List<DemoTask> =
        recordingService.getPendingTasks()

    suspend fun retryTask(taskId: String): DemoResult<DemoTask> =
        recordingService.executeTask(taskId)

    suspend fun getMetrics() = recordingService.getMetrics()

    suspend fun cleanup() = recordingService.deleteOldRecordings()

    suspend fun enforceRetention() = recordingService.enforceRetentionPolicy()

    suspend fun toggleKeep(taskId: String, isKept: Boolean) =
        recordingService.toggleKeep(taskId, isKept)

    suspend fun markBlocked(taskId: String) =
        recordingService.markBlocked(taskId)
}
