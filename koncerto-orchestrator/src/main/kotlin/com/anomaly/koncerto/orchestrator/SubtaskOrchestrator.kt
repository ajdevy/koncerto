package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.SubtaskRunner
import com.anomaly.koncerto.core.config.SubtaskManifest
import com.anomaly.koncerto.core.config.SubtaskState
import com.anomaly.koncerto.core.config.SubtaskStatus
import com.anomaly.koncerto.core.config.WorkplanConfig
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.GitWorkflow
import com.anomaly.koncerto.workspace.MergeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import java.nio.file.Path
import java.time.Instant

private const val DEFAULT_TURN_TIMEOUT_MS = 3_600_000L
private const val DEFAULT_STALL_TIMEOUT_MS = 300_000L

class SubtaskOrchestrator(
    private val subtaskRunner: SubtaskRunner,
    private val gitWorkflow: GitWorkflow,
    private val logger: StructuredLogger,
    private val frontier: SubtaskFrontier = SubtaskFrontier()
) {
    suspend fun execute(
        workspacePath: Path,
        manifest: SubtaskManifest,
        config: WorkplanConfig
    ): Flow<AgentEvent> = flow {
        val issueId = manifest.issueId
        val states = manifest.subtasks.map { SubtaskState(def = it) }.toMutableList()

        when (config.executionMode) {
            WorkplanConfig.ExecutionMode.SEQUENTIAL -> {
                val ordered = frontier.topologicalSort(states.toList())
                for (state in ordered) {
                    logger.info("Subtask started", mapOf("issueId" to issueId, "subtaskId" to state.def.id))
                    val result = runSingleSubtask(workspacePath, state, manifest)
                    val idx = states.indexOfFirst { it.def.id == state.def.id }
                    states[idx] = result.state
                    logger.info("Subtask completed", mapOf("issueId" to issueId, "subtaskId" to state.def.id, "status" to result.state.status.name))
                    result.events.forEach { emit(it) }
                    if (result.state.status == SubtaskStatus.FAILED) break
                }
            }
            WorkplanConfig.ExecutionMode.PARALLEL -> {
                val semaphore = Semaphore(config.maxParallelSubagents)
                val integrationBranch = "main"
                while (states.any { it.status == SubtaskStatus.PENDING || it.status == SubtaskStatus.RUNNING }) {
                    val ready = frontier.compute(states.toList())

                    if (ready.isEmpty() && states.none { it.status == SubtaskStatus.RUNNING }) break

                    coroutineScope {
                        val deferredList = mutableListOf<kotlinx.coroutines.Deferred<Pair<List<AgentEvent>, Boolean>>>()
                        for (state in ready) {
                            deferredList.add(async {
                                semaphore.acquire()
                                try {
                                    val idx = states.indexOfFirst { it.def.id == state.def.id }
                                    states[idx] = state.copy(status = SubtaskStatus.RUNNING)
                                    logger.info("Subtask started", mapOf("issueId" to issueId, "subtaskId" to state.def.id))

                                    val branchName = gitWorkflow.subtaskBranchName(issueId, state.def.id)
                                    gitWorkflow.createBranchFrom(
                                        workspacePath, branchName, integrationBranch
                                    )

                                    val result = runSingleSubtask(workspacePath, state, manifest)

                                    val mergeResult = gitWorkflow.mergeBranch(
                                        workspacePath, branchName, integrationBranch
                                    )

                                    gitWorkflow.deleteBranch(workspacePath, branchName)

                                    if (mergeResult is MergeResult.CONFLICT) {
                                        states[idx] = state.copy(
                                            status = SubtaskStatus.FAILED,
                                            branchName = branchName
                                        )
                                        logger.error("Subtask merge conflict", mapOf("issueId" to issueId, "subtaskId" to state.def.id, "branch" to branchName))
                                        Pair(
                                            listOf(AgentEvent.MergeConflict(
                                                subtaskId = state.def.id,
                                                branch = branchName,
                                                issueId = issueId
                                            )),
                                            true
                                        )
                                    } else {
                                        states[idx] = result.state
                                        logger.info("Subtask completed", mapOf("issueId" to issueId, "subtaskId" to state.def.id, "status" to result.state.status.name))
                                        Pair(result.events, false)
                                    }
                                } finally {
                                    semaphore.release()
                                }
                            })
                        }
                        for (deferred in deferredList) {
                            val result = deferred.await()
                            result.first.forEach { emit(it) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runSingleSubtask(
        workspacePath: Path,
        state: SubtaskState,
        manifest: SubtaskManifest
    ): SubtaskResult {
        val events = mutableListOf<AgentEvent>()

        events.add(AgentEvent.SubtaskStarted(
            subtaskId = state.def.id, issueId = manifest.issueId
        ))

        val result = subtaskRunner.runSubtask(
            workspacePath = workspacePath,
            prompt = state.def.prompt,
            kind = "opencode",
            command = null,
            turnTimeoutMs = DEFAULT_TURN_TIMEOUT_MS,
            stallTimeoutMs = DEFAULT_STALL_TIMEOUT_MS
        )

        return when (result) {
            is com.anomaly.koncerto.core.result.Result.Success -> {
                val completed = state.copy(
                    status = SubtaskStatus.SUCCEEDED,
                    completedAt = Instant.now()
                )
                events.add(AgentEvent.SubtaskCompleted(
                    subtaskId = state.def.id, issueId = manifest.issueId
                ))
                SubtaskResult(completed, events)
            }
            is com.anomaly.koncerto.core.result.Result.Failure -> {
                val failed = state.copy(
                    status = SubtaskStatus.FAILED,
                    completedAt = Instant.now()
                )
                events.add(AgentEvent.SubtaskFailed(
                    subtaskId = state.def.id,
                    issueId = manifest.issueId,
                    error = result.error.message ?: "unknown"
                ))
                SubtaskResult(failed, events)
            }
        }
    }

    private data class SubtaskResult(
        val state: SubtaskState,
        val events: List<AgentEvent>
    )
}