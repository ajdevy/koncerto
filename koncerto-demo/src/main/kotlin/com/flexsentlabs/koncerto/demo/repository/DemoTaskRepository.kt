package com.flexsentlabs.koncerto.demo.repository

import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask

interface DemoTaskRepository {
    suspend fun save(task: DemoTask)
    suspend fun findById(taskId: String): DemoTask?
    suspend fun findByIssue(issueId: String): List<DemoTask>
    suspend fun findAll(): List<DemoTask>
    suspend fun findPending(): List<DemoTask>
    suspend fun findByStatus(status: DemoStatus): List<DemoTask>
    suspend fun updateStatus(taskId: String, status: DemoStatus, errorMessage: String? = null)
    suspend fun updateCompleted(
        taskId: String,
        status: DemoStatus,
        recordingUrl: String?,
        storageKey: String?,
        durationMs: Long?,
        fileSizeBytes: Long?
    )
    suspend fun deleteOlderThan(timestamp: String, limit: Int): Int
    suspend fun countByStatus(status: DemoStatus): Int
    suspend fun sumFileSizes(): Long
    suspend fun updateKeepFlag(taskId: String, isKept: Boolean)
    suspend fun findOlderThan(timestamp: String): List<DemoTask>
    suspend fun updateHtmlReportKey(taskId: String, htmlReportKey: String)
    suspend fun updateFallbackFrom(taskId: String, fallbackFrom: String)
}
