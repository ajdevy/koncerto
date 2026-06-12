package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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
    private val _blocked = ConcurrentHashMap<String, Boolean>()
    private val _tokenTotals = AtomicReference(TokenTotals())
    val tokenTotals: TokenTotals get() = _tokenTotals.get()
    private val _codexRateLimits = ConcurrentHashMap<String, Any?>()
    val codexRateLimits: Map<String, Any?> get() = _codexRateLimits
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
        return running.computeIfPresent(issueId) { _, entry ->
            if (!entry.paused) entry.copy(paused = true) else entry
        } != null
    }

    fun resumeAgent(issueId: String): Boolean {
        return running.computeIfPresent(issueId) { _, entry ->
            if (entry.paused) entry.copy(paused = false) else entry
        } != null
    }

    fun cancelAgent(issueId: String): Boolean {
        val entry = running.remove(issueId) ?: return false
        claimed.remove(issueId)
        removeOutput(issueId)
        return true
    }

    fun clearAll() {
        running.clear()
        claimed.clear()
        retryAttempts.clear()
        completed.clear()
        _blocked.clear()
        _tokenTotals.set(TokenTotals())
        _codexRateLimits.clear()
        // Not atomic - should only be called during shutdown
    }

    fun updateIssueTokens(issueId: String, inputTokens: Long, outputTokens: Long, totalTokens: Long, turnCountInc: Int = 1): Boolean {
        val updated = running.computeIfPresent(issueId) { _, entry ->
            entry.copy(
                inputTokens = entry.inputTokens + inputTokens,
                outputTokens = entry.outputTokens + outputTokens,
                totalTokens = entry.totalTokens + totalTokens,
                turnCount = entry.turnCount + turnCountInc
            )
        }
        if (updated != null) {
            _tokenTotals.updateAndGet { totals ->
                totals.copy(
                    inputTokens = totals.inputTokens + inputTokens,
                    outputTokens = totals.outputTokens + outputTokens,
                    totalTokens = totals.totalTokens + totalTokens
                )
            }
        }
        return updated != null
    }

    fun addTokenTotals(inputTokens: Long, outputTokens: Long, totalTokens: Long) {
        _tokenTotals.updateAndGet { it.copy(
            inputTokens = it.inputTokens + inputTokens,
            outputTokens = it.outputTokens + outputTokens,
            totalTokens = it.totalTokens + totalTokens
        ) }
    }

    fun updateCodexRateLimits(limits: Map<String, Any?>) {
        _codexRateLimits.clear()
        _codexRateLimits.putAll(limits)
        // Not atomic - concurrent reads may see partial state
    }

    fun tryClaim(issueId: String): Boolean = claimed.putIfAbsent(issueId, true) == null

    fun releaseClaim(issueId: String) = claimed.remove(issueId)

    fun isClaimed(issueId: String): Boolean = claimed.containsKey(issueId)

    fun addBlocked(issueId: String): Boolean = _blocked.putIfAbsent(issueId, true) == null

    fun removeBlocked(issueId: String) = _blocked.remove(issueId)

    fun isBlocked(issueId: String): Boolean = _blocked.containsKey(issueId)

    val blockedKeys: Set<String> get() = _blocked.keys
}
