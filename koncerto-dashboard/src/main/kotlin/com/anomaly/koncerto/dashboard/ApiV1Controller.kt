package com.anomaly.koncerto.dashboard

import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.orchestrator.RuntimeState
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.codec.ServerSentEvent
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1")
class ApiV1Controller(
    private val state: RuntimeState,
    private val config: ServiceConfig
) {

    @Serializable
    data class StateSnapshot(
        val running: List<RunningRow>,
        val retrying: List<RetryingRow>,
        val tokenTotals: Totals,
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

    @GetMapping("/running/{identifier}/output/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamOutput(@PathVariable identifier: String): Flux<ServerSentEvent<String>> {
        val entry = state.running.values.firstOrNull { it.issue.identifier == identifier }
            ?: return Flux.empty()
        val flow = state.outputFlow(entry.issue.id) ?: return Flux.empty()
        return Flux.from(flow.asPublisher())
            .map { line: String -> ServerSentEvent.builder(line).event("output").build() }
    }

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
            tokenTotals = Totals(
                state.tokenTotals.inputTokens, state.tokenTotals.outputTokens,
                state.tokenTotals.totalTokens, state.tokenTotals.secondsRunning
            ),
            rateLimits = state.codexRateLimits.mapValues { it.value.toString() }
        )
    )

    @Serializable
    data class IssueDetail(
        val issueId: String? = null,
        val issueIdentifier: String? = null,
        val threadId: String? = null,
        val turnId: String? = null,
        val turnCount: Int? = null,
        val error: String? = null
    )

    @Serializable
    data class RefreshResponse(val status: String)

    @GetMapping("/{identifier}", produces = ["application/json"])
    fun byIdentifier(@PathVariable identifier: String): Mono<IssueDetail> {
        val entry = state.running.values.firstOrNull { it.issue.identifier == identifier }
        return if (entry != null) {
            Mono.just(
                IssueDetail(
                    issueId = entry.issue.id,
                    issueIdentifier = entry.issue.identifier,
                    threadId = entry.threadId,
                    turnId = entry.turnId,
                    turnCount = entry.turnCount
                )
            )
        } else Mono.just(IssueDetail(error = "not_found"))
    }

    @PostMapping("/refresh")
    fun refresh(): Mono<RefreshResponse> = Mono.just(RefreshResponse(status = "ok"))

    @Serializable
    data class StageModel(
        val stage: String,
        val model: String?,
        val agentKind: String?,
        val prompt: String?
    )

    @Serializable
    data class ModelsResponse(
        val agentKind: String,
        val configuredStages: List<StageModel>,
        val totalStages: Int
    )

    @GetMapping("/models", produces = ["application/json"])
    fun models(): Mono<ModelsResponse> = Mono.just(
        ModelsResponse(
            agentKind = config.agentKind,
            configuredStages = config.stages.map { (state, stage) ->
                StageModel(
                    stage = state,
                    model = stage.model,
                    agentKind = stage.agentKind,
                    prompt = stage.prompt
                )
            },
            totalStages = config.stages.size
        )
    )
}
