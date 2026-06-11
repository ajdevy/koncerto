package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import com.anomaly.koncerto.core.tenant.TenantContext

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
    val turnCount: Int = 1,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    val tenantContext: TenantContext? = null
)

data class RetryEntry(
    val issueId: String,
    val identifier: String,
    val attempt: Int,
    val dueAtMs: Long,
    val error: String?
)

data class TokenTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val secondsRunning: Long = 0
)

class RuntimeState {
    val running = ConcurrentHashMap<String, RunningEntry>()
    val claimed = ConcurrentHashMap<String, Boolean>()
    val retryAttempts = ConcurrentHashMap<String, RetryEntry>()
    val completed = ConcurrentHashMap<String, Boolean>()
    val blocked = ConcurrentHashMap<String, Boolean>()
    var tokenTotals = TokenTotals()
    @Volatile
    var codexRateLimits: Map<String, Any?> = emptyMap()
    @Volatile
    var pollIntervalMs: Long = 30_000
    @Volatile
    var maxConcurrentAgents: Int = 10
    @Volatile
    var workspaceRoot: java.nio.file.Path =
        java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "symphony_workspaces")

    private val outputBuffers = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    fun appendOutput(issueId: String, line: String) {
        val flow = outputBuffers.getOrPut(issueId) {
            MutableSharedFlow(replay = 2000, extraBufferCapacity = 500)
        }
        flow.tryEmit(line)
    }

    fun outputFlow(issueId: String): SharedFlow<String>? = outputBuffers[issueId]?.asSharedFlow()

    fun removeOutput(issueId: String) {
        outputBuffers.remove(issueId)
    }

    fun availableSlots(): Int = (maxConcurrentAgents - running.size).coerceAtLeast(0)

    fun pauseAgent(issueId: String): Boolean {
        val entry = running[issueId] ?: return false
        running[issueId] = entry.copy(paused = true)
        return true
    }

    fun resumeAgent(issueId: String): Boolean {
        val entry = running[issueId] ?: return false
        running[issueId] = entry.copy(paused = false)
        return true
    }

    fun cancelAgent(issueId: String): Boolean {
        val entry = running.remove(issueId) ?: return false
        val cancelledEntry = entry.copy(cancelled = true)
        claimed.remove(issueId)
        removeOutput(issueId)
        return true
    }

    fun clearAll() {
        running.clear()
        claimed.clear()
        retryAttempts.clear()
        completed.clear()
        blocked.clear()
        tokenTotals = TokenTotals()
    }

    fun updateTokenTotals(inputTokens: Long = 0, outputTokens: Long = 0, totalTokens: Long = 0, secondsRunning: Long = 0): TokenTotals {
        tokenTotals = tokenTotals.copy(
            inputTokens = tokenTotals.inputTokens + inputTokens,
            outputTokens = tokenTotals.outputTokens + outputTokens,
            totalTokens = tokenTotals.totalTokens + totalTokens,
            secondsRunning = secondsRunning
        )
        return tokenTotals
    }

    fun tryClaim(issueId: String): Boolean = claimed.putIfAbsent(issueId, true) == null

    fun releaseClaim(issueId: String) = claimed.remove(issueId)

    fun isClaimed(issueId: String): Boolean = claimed.containsKey(issueId)

    fun addBlocked(issueId: String) = blocked.putIfAbsent(issueId, true) != null

    fun removeBlocked(issueId: String) = blocked.remove(issueId)

    fun isBlocked(issueId: String): Boolean = blocked.containsKey(issueId)
}
