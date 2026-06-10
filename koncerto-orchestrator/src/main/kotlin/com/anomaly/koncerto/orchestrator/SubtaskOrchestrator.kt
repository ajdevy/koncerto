package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.SubtaskRunner
import com.anomaly.koncerto.core.config.SubtaskManifest
import com.anomaly.koncerto.core.config.SubtaskState
import com.anomaly.koncerto.core.config.SubtaskStatus
import com.anomaly.koncerto.core.config.WorkplanConfig
import com.anomaly.koncerto.core.config.ExecutionMode
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.GitWorkflow
import com.anomaly.koncerto.workspace.MergeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.time.Instant

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
            ExecutionMode.SEQUENTIAL -> {
                val ordered = frontier.topologicalSort(states.toList())
                for (state in ordered) {
                    val result = runSingleSubtask(workspacePath, state, manifest)
                    val idx = states.indexOfFirst { it.def.id == state.def.id }
                    states[idx] = result.state
                    emitAll(result.events)
                    if (result.state.status == SubtaskStatus.FAILED) break
                }
            }
            ExecutionMode.PARALLEL -> {
                while (states.any { it.status == SubtaskStatus.PENDING || it.status == SubtaskStatus.RUNNING }) {
                    val ready = frontier.compute(states.toList())
                    val running = states.count { it.status == SubtaskStatus.RUNNING }
                    val toLaunch = ready.take((config.maxParallelSubagents - running).coerceAtLeast(0))

                    if (toLaunch.isEmpty() && running == 0) break

                    coroutineScope {
                        toLaunch.map { state ->
                            async {
                                val idx = states.indexOfFirst { it.def.id == state.def.id }
                                states[idx] = state.copy(status = SubtaskStatus.RUNNING)

                                val branchName = gitWorkflow.subtaskBranchName(issueId, state.def.id)
                                gitWorkflow.createBranchFrom(
                                    workspacePath, branchName, manifest.integrationBranch
                                )

                                val result = runSingleSubtask(workspacePath, state, manifest)

                                val mergeResult = gitWorkflow.mergeBranch(
                                    workspacePath, branchName, manifest.integrationBranch
                                )

                                gitWorkflow.deleteBranch(workspacePath, branchName)

                                if (mergeResult is MergeResult.CONFLICT) {
                                    states[idx] = state.copy(
                                        status = SubtaskStatus.FAILED,
                                        branchName = branchName
                                    )
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
                                    Pair(result.events, false)
                                }
                            }
                        }.forEach { deferred ->
                            val (events, _) = deferred.await()
                            for (event in events) emit(event)
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
            turnTimeoutMs = 3_600_000L,
            stallTimeoutMs = 300_000L
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