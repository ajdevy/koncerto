package com.flexsentlabs.koncerto.demo.observability

import com.flexsentlabs.koncerto.demo.model.DemoTask
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant

class DemoAuditLogger(
    private val logPath: String = "/tmp/koncerto-demo/audit.log"
) {
    private val lock = Any()

    fun logTaskCreated(task: DemoTask) {
        write("TASK_CREATED", mapOf(
            "task_id" to task.id,
            "issue_id" to task.issueId,
            "platform" to task.platform.name,
            "trigger" to task.trigger.name
        ))
    }

    fun logRecordingStarted(task: DemoTask) {
        write("RECORDING_STARTED", mapOf(
            "task_id" to task.id,
            "platform" to task.platform.name
        ))
    }

    fun logRecordingCompleted(task: DemoTask, durationMs: Long) {
        write("RECORDING_COMPLETED", mapOf(
            "task_id" to task.id,
            "duration_ms" to durationMs.toString(),
            "file_size" to (task.fileSizeBytes?.toString() ?: "unknown")
        ))
    }

    fun logUploadCompleted(task: DemoTask, storageKey: String) {
        write("UPLOAD_COMPLETED", mapOf(
            "task_id" to task.id,
            "storage_key" to storageKey
        ))
    }

    fun logReportPosted(task: DemoTask, recordingUrl: String) {
        write("REPORT_POSTED", mapOf(
            "task_id" to task.id,
            "url" to recordingUrl
        ))
    }

    fun logTaskFailed(task: DemoTask, errorMessage: String) {
        write("TASK_FAILED", mapOf(
            "task_id" to task.id,
            "error" to errorMessage,
            "retry_count" to task.retryCount.toString()
        ))
    }

    fun logFallback(task: DemoTask, fromPlatform: String, toPlatform: String) {
        write("FALLBACK", mapOf(
            "task_id" to task.id,
            "from" to fromPlatform,
            "to" to toPlatform
        ))
    }

    fun logCleanup(deletedCount: Int) {
        write("CLEANUP", mapOf("deleted_count" to deletedCount.toString()))
    }

    fun logQuotaCheck(usedBytes: Long, availableBytes: Long) {
        write("QUOTA_CHECK", mapOf(
            "used_bytes" to usedBytes.toString(),
            "available_bytes" to availableBytes.toString()
        ))
    }

    private fun write(event: String, data: Map<String, String>) {
        val timestamp = Instant.now().toString()
        val dataStr = data.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val line = "[$timestamp] $event $dataStr\n"
        try {
            synchronized(lock) {
                val dir = File(logPath).parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                Files.writeString(
                    File(logPath).toPath(),
                    line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                )
            }
        } catch (_: Exception) {
        }
    }
}
