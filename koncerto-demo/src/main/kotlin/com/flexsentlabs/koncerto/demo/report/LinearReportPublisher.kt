package com.flexsentlabs.koncerto.demo.report

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.linear.LinearClient

class LinearReportPublisher(
    private val linearClient: LinearClient
) : DemoReporter {

    override suspend fun report(task: DemoTask, recordingUrl: String): DemoResult<Unit> {
        return try {
            val body = buildCommentBody(task, recordingUrl)
            linearClient.createComment(task.issueId, body)
            DemoResult.Success(Unit)
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.ReportFailed(e))
        }
    }

    override suspend fun reportFailure(task: DemoTask, errorMessage: String): DemoResult<Unit> {
        return try {
            val body = buildFailureBody(task, errorMessage)
            linearClient.createComment(task.issueId, body)
            DemoResult.Success(Unit)
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.ReportFailed(e))
        }
    }

    private fun buildCommentBody(task: DemoTask, recordingUrl: String): String {
        return """
            |## Demo Recording — ${task.platform.name}
            |
            |**Issue:** ${task.issueIdentifier}
            |**Platform:** ${task.platform.name}
            |**Duration:** ${formatDuration(task.durationMs)}
            |**File Size:** ${formatFileSize(task.fileSizeBytes)}
            |
            |**Recording URL:** [View Recording]($recordingUrl)
            |
            |_Recorded at ${task.completedAt}_
        """.trimMargin()
    }

    private fun buildFailureBody(task: DemoTask, errorMessage: String): String {
        return """
            |## Demo Recording Failed — ${task.platform.name}
            |
            |**Issue:** ${task.issueIdentifier}
            |**Error:** $errorMessage
            |**Retry Count:** ${task.retryCount}
            |
            |_Failed at ${task.updatedAt}_
        """.trimMargin()
    }

    private fun formatDuration(ms: Long?): String {
        if (ms == null) return "N/A"
        val seconds = ms / 1000
        return "${seconds / 60}m ${seconds % 60}s"
    }

    private fun formatFileSize(bytes: Long?): String {
        if (bytes == null) return "N/A"
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        }
    }
}
