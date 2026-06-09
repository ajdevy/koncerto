package com.anomaly.koncerto.dashboard

import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
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
    private val config: ServiceConfig,
    private val runtimeStates: Map<String, RuntimeState> = emptyMap()
) {
    private val primaryProjectConfig: com.anomaly.koncerto.core.config.ProjectConfig?
        get() = config.projects.values.firstOrNull()
    private val primaryProjectStages: Map<String, StageAgentConfig>
        get() = primaryProjectConfig?.agent?.stages ?: emptyMap()
    private val state: RuntimeState?
        get() = runtimeStates.values.firstOrNull()

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
        val s = state ?: return Flux.empty()
        val entry = s.running.values.firstOrNull { it.issue.identifier == identifier }
            ?: return Flux.empty()
        val flow = s.outputFlow(entry.issue.id) ?: return Flux.empty()
        return Flux.from(flow.asPublisher())
            .map { line: String -> ServerSentEvent.builder(line).event("output").build() }
    }

    @GetMapping("/state", produces = ["application/json"])
    fun state(): Mono<StateSnapshot> {
        val s = state ?: return Mono.just(StateSnapshot(emptyList(), emptyList(), Totals(0, 0, 0, 0), emptyMap()))
        val allRunning = runtimeStates.values.flatMap { rs ->
            rs.running.values.map {
                RunningRow(
                    it.issue.id, it.issue.identifier, it.threadId, it.turnId, it.turnCount,
                    it.inputTokens, it.outputTokens, it.totalTokens, it.issue.url
                )
            }
        }
        val allRetrying = runtimeStates.values.flatMap { rs ->
            rs.retryAttempts.values.map {
                RetryingRow(it.issueId, it.identifier, it.attempt, it.dueAtMs, it.error)
            }
        }
        val tokens = runtimeStates.values.fold(Totals(0, 0, 0, 0)) { acc, rs ->
            Totals(
                acc.inputTokens + rs.tokenTotals.inputTokens,
                acc.outputTokens + rs.tokenTotals.outputTokens,
                acc.totalTokens + rs.tokenTotals.totalTokens,
                acc.secondsRunning + rs.tokenTotals.secondsRunning
            )
        }
        val limits = s.codexRateLimits.mapValues { it.value.toString() }
        return Mono.just(StateSnapshot(allRunning, allRetrying, tokens, limits))
    }

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
        val s = state ?: return Mono.just(IssueDetail(error = "not_found"))
        val entry = s.running.values.firstOrNull { it.issue.identifier == identifier }
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
            agentKind = primaryProjectConfig?.agent?.kind ?: "opencode",
            configuredStages = primaryProjectStages.map { (state, stage) ->
                StageModel(
                    stage = state,
                    model = stage.model,
                    agentKind = stage.agentKind,
                    prompt = stage.prompt
                )
            },
            totalStages = primaryProjectStages.size
        )
    )
}
