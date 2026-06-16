package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.SubtaskRunner
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import com.flexsentlabs.koncerto.core.config.WorkplanConfig
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.MergeResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SubtaskOrchestratorTest {

    @Test
    fun `sequential mode executes subtasks in dependency order`(@TempDir tempDir: Path) = runTest {
        val executed = mutableListOf<String>()
        val runner = FakeSubtaskRunner { prompt ->
            executed.add(prompt)
            Result.Success(Unit)
        }
        val orchestrator = createOrchestrator(runner = runner)

        val manifest = SubtaskManifest(
            issueId = "TEST-1",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "prompt-a"),
                SubtaskDef(id = "b", description = "B", prompt = "prompt-b", dependsOn = listOf("a")),
                SubtaskDef(id = "c", description = "C", prompt = "prompt-c", dependsOn = listOf("b"))
            )
        )

        val events = orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.SEQUENTIAL)
        ).toList()

        assertThat(executed).containsExactly("prompt-a", "prompt-b", "prompt-c")
        assertThat(events.filterIsInstance<AgentEvent.SubtaskCompleted>().size).isEqualTo(3)
        assertThat(events.filterIsInstance<AgentEvent.SubtaskStarted>().size).isEqualTo(3)
    }

    @Test
    fun `sequential mode stops on failure`(@TempDir tempDir: Path) = runTest {
        val runner = FakeSubtaskRunner { prompt ->
            if (prompt == "prompt-b") Result.Failure(IllegalStateException("fail"))
            else Result.Success(Unit)
        }
        val orchestrator = createOrchestrator(runner = runner)

        val manifest = SubtaskManifest(
            issueId = "TEST-1",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "prompt-a"),
                SubtaskDef(id = "b", description = "B", prompt = "prompt-b"),
                SubtaskDef(id = "c", description = "C", prompt = "prompt-c")
            )
        )

        val events = orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.SEQUENTIAL)
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.SubtaskCompleted>()
        val failed = events.filterIsInstance<AgentEvent.SubtaskFailed>()
        assertThat(completed.size).isEqualTo(1)
        assertThat(failed.size).isEqualTo(1)
        assertThat(failed[0].subtaskId).isEqualTo("b")
    }

    private fun createOrchestrator(
        runner: SubtaskRunner? = null,
        gitWorkflow: GitWorkflow? = null
    ): SubtaskOrchestrator {
        val logger = StructuredLogger(emptyList<LogSink>())
        val actualRunner = runner ?: FakeSubtaskRunner()
        val actualGit = gitWorkflow ?: FakeGitWorkflow()
        return SubtaskOrchestrator(
            subtaskRunner = actualRunner,
            gitWorkflow = actualGit,
            logger = logger
        )
    }
}

class FakeSubtaskRunner(
    private val block: (prompt: String) -> EmptyResult<IllegalStateException> = { Result.Success(Unit) }
) : SubtaskRunner {
    constructor() : this({ Result.Success(Unit) })

    override suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String,
        command: String?,
        turnTimeoutMs: Long,
        stallTimeoutMs: Long
    ): EmptyResult<IllegalStateException> {
        return block(prompt)
    }
}

class FakeGitWorkflow(
    config: com.flexsentlabs.koncerto.core.config.GitConfig = com.flexsentlabs.koncerto.core.config.GitConfig(enabled = false, branchPrefix = "feature/"),
    logger: StructuredLogger = StructuredLogger(emptyList<LogSink>())
) : GitWorkflow(config, logger) {
    override fun subtaskBranchName(issueIdentifier: String, subtaskId: String): String =
        "subtask/$issueIdentifier/$subtaskId"

    override fun createBranchFrom(workspacePath: Path, branchName: String, sourceBranch: String) {}

    override fun mergeBranch(workspacePath: Path, sourceBranch: String, targetBranch: String): MergeResult =
        MergeResult.SUCCESS

    override fun deleteBranch(workspacePath: Path, branchName: String) {}
}