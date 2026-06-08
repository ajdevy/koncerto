package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class RunningEntry(
    val issue: Issue,
    val threadId: String,
    val turnId: String,
    val startedAt: Instant,
    val lastCodexTimestamp: Instant?,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val lastReportedInput: Long = 0,
    val lastReportedOutput: Long = 0,
    val lastReportedTotal: Long = 0,
    val turnCount: Int = 1
)

data class RetryEntry(
    val issueId: String,
    val identifier: String,
    val attempt: Int,
    val dueAtMs: Long,
    val error: String?
)

data class CodexTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    var secondsRunning: Long = 0
)

class RuntimeState {
    val running = ConcurrentHashMap<String, RunningEntry>()
    val claimed: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val retryAttempts = ConcurrentHashMap<String, RetryEntry>()
    val completed: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    var codexTotals = CodexTotals()
    @Volatile
    var codexRateLimits: Map<String, Any?> = emptyMap()
    @Volatile
    var pollIntervalMs: Long = 30_000
    @Volatile
    var maxConcurrentAgents: Int = 10
    @Volatile
    var workspaceRoot: java.nio.file.Path =
        java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "symphony_workspaces")

    fun availableSlots(): Int = (maxConcurrentAgents - running.size).coerceAtLeast(0)
}
