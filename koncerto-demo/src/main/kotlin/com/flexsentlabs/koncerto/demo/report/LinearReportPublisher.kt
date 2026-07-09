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

    override suspend fun reportSkipped(issueId: String, issueIdentifier: String, reason: String): DemoResult<Unit> {
        return try {
            val body = buildSkippedBody(issueIdentifier, reason)
            linearClient.createComment(issueId, body)
            DemoResult.Success(Unit)
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.ReportFailed(e))
        }
    }

    private fun buildCommentBody(task: DemoTask, recordingUrl: String): String {
        return """
            |### 🎥 Demo Recorded — ✅ ${task.platform.name}
            |
            |> ${formatDuration(task.durationMs)} · ${formatFileSize(task.fileSizeBytes)} · [▶ Watch recording]($recordingUrl)
            |
            |`${task.issueIdentifier}` · recorded ${task.completedAt}
            |
            |_Recorded by koncerto_
        """.trimMargin()
    }

    private fun buildSkippedBody(issueIdentifier: String, reason: String): String {
        return """
            |### 🎥 Demo Skipped — ⏭️
            |
            |${blockquote(reason)}
            |
            |`$issueIdentifier`
            |
            |_Recorded by koncerto_
        """.trimMargin()
    }

    private fun buildFailureBody(task: DemoTask, errorMessage: String): String {
        return """
            |### 🎥 Demo Failed — ❌ ${task.platform.name}
            |
            |${blockquote(errorMessage)}
            |
            |`${task.issueIdentifier}` · retry ${task.retryCount} · failed ${task.updatedAt}
            |
            |_Recorded by koncerto_
        """.trimMargin()
    }

    private fun blockquote(text: String): String {
        if (text.isBlank()) return "> _no details provided_"
        return text.trimEnd('\n').lineSequence().joinToString("\n") { "> $it" }
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
