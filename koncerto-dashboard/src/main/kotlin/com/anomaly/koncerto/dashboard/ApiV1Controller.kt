package com.anomaly.koncerto.dashboard

import com.anomaly.koncerto.orchestrator.RuntimeState
import kotlinx.serialization.Serializable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1")
class ApiV1Controller(private val state: RuntimeState) {

    @Serializable
    data class StateSnapshot(
        val running: List<RunningRow>,
        val retrying: List<RetryingRow>,
        val codexTotals: Totals,
        val rateLimits: Map<String, String>
    )

    @Serializable
    data class RunningRow(
        val issueId: String,
        val issueIdentifier: String,
        val threadId: String,
        val turnId: String,
        val turnCount: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val url: String?
    )

    @Serializable
    data class RetryingRow(
        val issueId: String,
        val identifier: String,
        val attempt: Int,
        val dueAtMs: Long,
        val error: String?
    )

    @Serializable
    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val secondsRunning: Long
    )

    @GetMapping("/state", produces = ["application/json"])
    fun state(): Mono<StateSnapshot> = Mono.just(
        StateSnapshot(
            running = state.running.values.map {
                RunningRow(
                    it.issue.id, it.issue.identifier, it.threadId, it.turnId, it.turnCount,
                    it.inputTokens, it.outputTokens, it.totalTokens, it.issue.url
                )
            },
            retrying = state.retryAttempts.values.map {
                RetryingRow(it.issueId, it.identifier, it.attempt, it.dueAtMs, it.error)
            },
            codexTotals = Totals(
                state.codexTotals.inputTokens, state.codexTotals.outputTokens,
                state.codexTotals.totalTokens, state.codexTotals.secondsRunning
            ),
            rateLimits = state.codexRateLimits.mapValues { it.value.toString() }
        )
    )

    @GetMapping("/{identifier}", produces = ["application/json"])
    fun byIdentifier(@PathVariable identifier: String): Mono<Map<String, Any?>> {
        val entry = state.running.values.firstOrNull { it.issue.identifier == identifier }
        return if (entry != null) {
            Mono.just(
                mapOf(
                    "issueId" to entry.issue.id,
                    "issueIdentifier" to entry.issue.identifier,
                    "threadId" to entry.threadId,
                    "turnId" to entry.turnId,
                    "turnCount" to entry.turnCount
                )
            )
        } else Mono.just(mapOf("error" to "not_found"))
    }

    @PostMapping("/refresh")
    fun refresh(): Mono<Map<String, String>> = Mono.just(mapOf("status" to "ok"))
}
