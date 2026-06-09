package com.anomaly.koncerto.dashboard

import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.metrics.IssueMetrics
import com.anomaly.koncerto.metrics.MetricsRepository
import com.anomaly.koncerto.orchestrator.Orchestrator
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.codec.ServerSentEvent
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1")
class ApiV1Controller(
    private val config: ServiceConfig,
    private val orchestrator: Orchestrator,
    private val metricsRepository: MetricsRepository? = null
) {
    private val projects: Map<String, Orchestrator.ProjectRuntime>
        get() = orchestrator.projects

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
    fun streamOutput(
        @PathVariable identifier: String,
        @RequestParam(defaultValue = "") project: String
    ): Flux<ServerSentEvent<String>> {
        val pr = if (project.isNotBlank()) projects[project] else null
        val state = pr?.state ?: projects.values.firstOrNull()?.state ?: return Flux.empty()
        val entry = state.running.values.firstOrNull { it.issue.identifier == identifier }
            ?: return Flux.empty()
        val flow = state.outputFlow(entry.issue.id) ?: return Flux.empty()
        return Flux.from(flow.asPublisher())
            .map { line: String -> ServerSentEvent.builder(line).event("output").build() }
    }

    @GetMapping("/state", produces = ["application/json"])
    fun state(): Mono<StateSnapshot> {
        val allRunning = projects.values.flatMap { pr ->
            pr.state.running.values.map {
                RunningRow(
                    it.issue.id, it.issue.identifier, it.threadId, it.turnId, it.turnCount,
                    it.inputTokens, it.outputTokens, it.totalTokens, it.issue.url
                )
            }
        }
        val allRetrying = projects.values.flatMap { pr ->
            pr.state.retryAttempts.values.map {
                RetryingRow(it.issueId, it.identifier, it.attempt, it.dueAtMs, it.error)
            }
        }
        val tokens = projects.values.fold(Totals(0, 0, 0, 0)) { acc, pr ->
            val t = pr.state.tokenTotals
            Totals(
                acc.inputTokens + t.inputTokens,
                acc.outputTokens + t.outputTokens,
                acc.totalTokens + t.totalTokens,
                acc.secondsRunning + t.secondsRunning
            )
        }
        val firstState = projects.values.firstOrNull()?.state
        val limits = firstState?.codexRateLimits?.mapValues { it.value.toString() } ?: emptyMap()
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
        val entry = projects.values.asSequence().flatMap { pr ->
            pr.state.running.values.asSequence()
        }.firstOrNull { it.issue.identifier == identifier }
        return if (entry != null) {
            Mono.just(IssueDetail(
                issueId = entry.issue.id,
                issueIdentifier = entry.issue.identifier,
                threadId = entry.threadId,
                turnId = entry.turnId,
                turnCount = entry.turnCount
            ))
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
    fun models(@RequestParam(defaultValue = "") project: String): Mono<ModelsResponse> {
        val pr = if (project.isNotBlank()) projects[project]
                 else projects.values.firstOrNull()
        val pc = pr?.config ?: return Mono.just(ModelsResponse("opencode", emptyList(), 0))
        return Mono.just(ModelsResponse(
            agentKind = pc.agent.kind,
            configuredStages = pc.agent.stages.map { (stageState, stage) ->
                StageModel(stage = stageState, model = stage.model, agentKind = stage.agentKind, prompt = stage.prompt)
            },
            totalStages = pc.agent.stages.size
        ))
    }

    @GetMapping("/history", produces = ["application/json"])
    suspend fun history(
        @RequestParam project: String? = null,
        @RequestParam(defaultValue = "50") limit: Int = 50
    ): List<IssueMetrics> {
        return if (project != null) {
            metricsRepository?.findByProject(project) ?: emptyList()
        } else {
            metricsRepository?.findAll() ?: emptyList()
        }
    }

    @GetMapping("/stages", produces = ["application/json"])
    fun stages(): Map<String, Any> {
        return config.projects.mapValues { (slug, pc) ->
            mapOf(
                "agent" to mapOf("kind" to pc.agent.kind, "maxConcurrent" to pc.agent.maxConcurrentAgents),
                "stages" to pc.agent.stages.mapValues { (_, sc) ->
                    mapOf(
                        "maxConcurrent" to sc.maxConcurrent,
                        "onCompleteState" to sc.onCompleteState,
                        "prompt" to sc.prompt
                    )
                }
            )
        }
    }

    @PutMapping("/running/{identifier}/pause")
    fun pauseAgent(@PathVariable identifier: String): ResponseEntity<Unit> {
        val found = projects.values.any { pr ->
            val id = pr.state.running.entries.firstOrNull { it.value.issue.identifier == identifier }?.key
            id != null && pr.state.pauseAgent(id)
        }
        return if (found) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
    }

    @PutMapping("/running/{identifier}/resume")
    fun resumeAgent(@PathVariable identifier: String): ResponseEntity<Unit> {
        val found = projects.values.any { pr ->
            val id = pr.state.running.entries.firstOrNull { it.value.issue.identifier == identifier }?.key
            id != null && pr.state.resumeAgent(id)
        }
        return if (found) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
    }

    @PutMapping("/running/{identifier}/cancel")
    fun cancelAgent(@PathVariable identifier: String): ResponseEntity<Unit> {
        val found = projects.values.any { pr ->
            val id = pr.state.running.entries.firstOrNull { it.value.issue.identifier == identifier }?.key
            id != null && pr.state.cancelAgent(id)
        }
        return if (found) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
    }
}
