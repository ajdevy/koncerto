package com.anomaly.koncerto.orchestrator

class RetryExecutor(private val maxBackoffMs: Long) {
    fun computeDelay(previousAttempt: Int): Long {
        val nextAttempt = previousAttempt + 1
        return (10_000L * (1L shl (nextAttempt - 1).coerceAtMost(20)))
            .coerceAtMost(maxBackoffMs)
    }

    fun createEntry(issueId: String, identifier: String, previousAttempt: Int, error: String): RetryEntry {
        val nextAttempt = previousAttempt + 1
        val delayMs = computeDelay(previousAttempt)
        return RetryEntry(
            issueId = issueId,
            identifier = identifier,
            attempt = nextAttempt,
            dueAtMs = System.currentTimeMillis() + delayMs,
            error = error
        )
    }
}
